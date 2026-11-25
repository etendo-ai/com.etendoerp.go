/*
 * *************************************************************************
 * The contents of this file are subject to the Etendo License
 * (the "License"), you may not use this file except in compliance with
 * the License.
 * You may obtain a copy of the License at
 * https://github.com/etendosoftware/etendo_core/blob/main/legal/Etendo_license.txt
 * Software distributed under the License is distributed on an
 * "AS IS" basis, WITHOUT WARRANTY OF ANY KIND, either express or
 * implied. See the License for the specific language governing rights
 * and limitations under the License.
 * All portions are Copyright (C) 2021-2026 FUTIT SERVICES, S.L
 * All Rights Reserved.
 * Contributor(s): Futit Services S.L.
 * *************************************************************************
 */

package com.etendoerp.go.schemaforge.email.contracts;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.BiFunction;
import java.util.function.BooleanSupplier;

import org.apache.commons.lang3.StringUtils;
import org.codehaus.jettison.json.JSONException;
import org.codehaus.jettison.json.JSONObject;
import org.openbravo.base.exception.OBException;

import com.etendoerp.go.portal.PortalDocumentKind;
import com.etendoerp.go.portal.PortalLink;
import com.etendoerp.go.portal.PortalLinkService;
import com.etendoerp.go.portal.PortalLogoReference;
import com.etendoerp.go.portal.PortalRenewalScope;
import com.etendoerp.go.schemaforge.email.EmailAuthorizationResult;
import com.etendoerp.go.schemaforge.email.EmailContract;
import com.etendoerp.go.schemaforge.email.EmailContractCommand;
import com.etendoerp.go.schemaforge.email.EmailContractCommandSupport;
import com.etendoerp.go.schemaforge.email.EmailContractResolution;
import com.etendoerp.go.schemaforge.email.EmailDeliveryPolicy;
import com.etendoerp.go.schemaforge.email.EmailProviderRequest;
import com.etendoerp.go.schemaforge.email.EmailRecipientResolution;
import com.etendoerp.go.schemaforge.email.EmailRecipientSet;
import com.etendoerp.go.schemaforge.email.EmailSendContext;
import com.etendoerp.go.schemaforge.email.EmailThrottleRule;
import com.etendoerp.go.schemaforge.email.TransactionalEmailService;
import com.etendoerp.go.schemaforge.email.render.EmailContent;
import com.etendoerp.go.schemaforge.email.render.EmailEscape;
import com.etendoerp.go.schemaforge.email.render.EmailFooter;
import com.etendoerp.go.schemaforge.email.render.EmailLayout;
import com.etendoerp.go.schemaforge.email.render.EmailMessages;
import com.etendoerp.go.schemaforge.email.render.ValidityWindow;

/**
 * Emails a new customer portal link when an expired one was opened (ETP-6209, plan §A.4).
 *
 * <p><b>Recipients, language, tenant and document are all re-derived from the expired link</b>:
 * the command carries only that link's id ({@code linkId}), the document id (so the send files
 * under the document's history) and the tenant. The new link goes to exactly the expired link's
 * To and CC — never to whoever opened it — so a leaked link can at worst re-mail the original
 * customer, never hand its holder a fresh one.
 *
 * <p><b>Only {@code PortalLinkService#renew} may trigger it.</b> This contract sits in the same
 * registry the {@code /sws/neo/email-contracts/{contract}/send} endpoint dispatches to; without a
 * caller check any signed-in tenant user could ask for a renewal by naming it. The check is
 * {@link PortalRenewalScope}, which the service sets only after validating the expired link and
 * applying the blocking checks and limits.
 *
 * <p>Like the document emails, a renewal is recorded in the document's own send history, so the
 * tenant sees it in the document's Emails card next to the original send (decision D-3).
 */
final class PortalLinkRenewalEmailContract implements EmailContract {

  static final String MESSAGE_RENEWAL_ONLY =
      "Portal link renewals are sent by the customer portal only";
  static final String MESSAGE_NO_LINK = "The portal link to renew was not found";
  private static final String CONTENT_TEMPLATE = "custom";
  private static final String KEY_PREFIX = "portal-link-renewal.";

  private final PortalLinkService linkService;
  private final BooleanSupplier renewalCaller;
  private final BiFunction<String, String, Optional<PortalLogoReference.PublicLogo>> logoFinder;

  PortalLinkRenewalEmailContract() {
    this(new PortalLinkService(), PortalRenewalScope::isActive);
  }

  PortalLinkRenewalEmailContract(PortalLinkService linkService, BooleanSupplier renewalCaller) {
    this(linkService, renewalCaller, PortalLogoReference::find);
  }

  /**
   * Full constructor: the public logo lookup is a seam, so a test pins it instead of depending on
   * runtime configuration or the DAL.
   *
   * @param logoFinder the tenant's public logo, by client id and organization id
   */
  PortalLinkRenewalEmailContract(PortalLinkService linkService, BooleanSupplier renewalCaller,
      BiFunction<String, String, Optional<PortalLogoReference.PublicLogo>> logoFinder) {
    this.linkService = linkService;
    this.renewalCaller = renewalCaller;
    this.logoFinder = logoFinder;
  }

  @Override
  public String getName() {
    return PortalLinkService.RENEWAL_CONTRACT;
  }

  @Override
  public boolean logsSendHistory() {
    return true;
  }

  /**
   * The spec of the renewed link's document, so the renewal files under that document's history.
   */
  @Override
  public String getSpecName(EmailSendContext context) {
    return findLink(context == null ? null : context.getCommand())
        .map(PortalLink::getDocumentKind)
        .map(PortalDocumentKind::getSpecName)
        .orElse(null);
  }

  @Override
  public EmailAuthorizationResult authorize(EmailContractCommand command) {
    EmailAuthorizationResult editsRejection =
        EmailContractCommandSupport.rejectRecipientEditsIfPresent(command);
    if (!editsRejection.isAllowed()) {
      return editsRejection;
    }
    EmailAuthorizationResult messageRejection =
        EmailContractCommandSupport.rejectMessageEditsIfPresent(command);
    if (!messageRejection.isAllowed()) {
      return messageRejection;
    }
    if (!renewalCaller.getAsBoolean()) {
      return EmailAuthorizationResult.rejected(403, MESSAGE_RENEWAL_ONLY);
    }
    return findLink(command).filter(PortalLink::isSent).isPresent()
        ? EmailAuthorizationResult.allowed()
        : EmailAuthorizationResult.rejected(409, MESSAGE_NO_LINK);
  }

  @Override
  public EmailRecipientResolution resolveRecipient(EmailContractCommand command) {
    Optional<PortalLink> link = findLink(command);
    if (!link.isPresent()) {
      return EmailRecipientResolution.rejected(409, MESSAGE_NO_LINK);
    }
    List<String> to = validOnly(link.get().getRecipientsTo());
    if (to.isEmpty()) {
      return EmailRecipientResolution.noRecipient("The portal link has no valid recipient");
    }
    return EmailRecipientResolution.serverResolved(
        EmailRecipientSet.of(to, validOnly(link.get().getRecipientsCc())));
  }

  @Override
  public EmailContractResolution resolve(EmailContractCommand command,
      EmailRecipientResolution recipient) {
    Optional<PortalLink> expired = findLink(command);
    if (!expired.isPresent() || expired.get().getDocumentKind() == null) {
      return EmailContractResolution.rejected(409,
          TransactionalEmailService.STATUS_VALIDATION_FAILED, MESSAGE_NO_LINK);
    }
    // Minted pending here, like a document send's link: it becomes usable only in onDelivered.
    Optional<PortalLinkService.MintedLink> minted = linkService.mintRenewal(expired.get().getId());
    if (!minted.isPresent()) {
      return EmailContractResolution.rejected(400,
          TransactionalEmailService.STATUS_VALIDATION_FAILED,
          "A portal link could not be built: the app base URL may not be configured");
    }
    PortalLink link = expired.get();
    String language = link.getLanguage();
    String tenantName = StringUtils.defaultString(link.getClientName());
    // "la factura", "el presupuesto"… — article and lower case, so the sentence reads naturally.
    String documentType = EmailMessages.get(KEY_PREFIX + "document."
        + link.getDocumentKind().copyKey(link.getRecordId()), language);
    String documentNumber = link.getDocumentKind().documentNumber(link.getRecordId()).orElse("");
    Instant now = Instant.now();
    long hours = ValidityWindow.hoursUntil(now, now.plus(minted.get().getValidity()));
    // ETP-6209 QA: the card design of the other customer emails (issuer logo or initials, title,
    // message, button, link fallback, the validity note, the etendo.ai footer). No "Saludos,
    // Equipo de Etendo": it is sent on behalf of our customer, not by Etendo.
    EmailContent content = issuer(link, tenantName)
        .title(EmailMessages.get(KEY_PREFIX + "title", language))
        .language(language)
        .paragraphHtml(EmailEscape.applyBold(EmailMessages.get(KEY_PREFIX + "body", language,
            EmailEscape.escapeHtml(tenantName), EmailEscape.escapeHtml(documentType),
            EmailEscape.escapeHtml(documentNumber))))
        .cta(EmailMessages.get(KEY_PREFIX + "cta", language), minted.get().getUrl())
        .linkFallbackText(EmailMessages.get("link.fallback", language))
        .note(EmailMessages.get(KEY_PREFIX + "note.expiry", language, hours))
        .footer(EmailFooter.etendo(EmailFooter.Piece.LINK_RENEWAL, language))
        .build();
    try {
      JSONObject data = new JSONObject();
      // Gateway traceability only — the "custom" template renders subject and body, never this —
      // so it is sent when there is a name and omitted otherwise, rather than a made-up one.
      String businessPartnerName = StringUtils.trimToNull(link.getBusinessPartnerName());
      if (businessPartnerName != null) {
        data.put("name", businessPartnerName);
      }
      data.put("link", minted.get().getUrl());
      data.put("subject", EmailMessages.get(KEY_PREFIX + "subject", language, tenantName));
      data.put("body", EmailLayout.render(content));
      EmailRecipientSet recipients = recipient.getRecipientSet() != null
          ? recipient.getRecipientSet()
          : EmailRecipientSet.singleTo(recipient.getRecipient());
      return EmailContractResolution.ready(new EmailProviderRequest(recipients, CONTENT_TEMPLATE,
          data, null).withSendCorrelationId(minted.get().getId()));
    } catch (JSONException e) {
      throw new OBException("Could not build the portal link renewal email payload", e);
    }
  }

  /**
   * One email per new link. The product's renewal limit (one per link per cool-down) is applied
   * by {@code PortalLinkService#renew} before this contract runs; these throttles are only the
   * email system's own anti-abuse backstop, at the same ceilings as the document emails.
   */
  @Override
  public EmailDeliveryPolicy deliveryPolicy(EmailContractCommand command,
      EmailRecipientResolution recipient, EmailProviderRequest providerRequest) {
    String tenantId = EmailContractCommandSupport.text(command,
        EmailContractCommandSupport.FIELD_TENANT_ID);
    String newLinkId = providerRequest == null ? null : providerRequest.getSendCorrelationId();
    String key = EmailContractCommandSupport.idempotencyKey(getName(), tenantId,
        StringUtils.defaultIfBlank(newLinkId, linkId(command)));
    return EmailContractCommandSupport.deliveryPolicy(key,
        EmailThrottleRule.perRecipient(20, 3600),
        EmailThrottleRule.perTenant(100, 3600),
        EmailThrottleRule.global(2000, 60));
  }

  @Override
  public void onDelivered(EmailSendContext context, String sendLogId) {
    String newLinkId = context == null || context.getProviderRequest() == null ? null
        : context.getProviderRequest().getSendCorrelationId();
    if (newLinkId == null) {
      return;
    }
    EmailRecipientSet recipients = context.getRecipientSet();
    linkService.confirm(newLinkId, sendLogId, recipients == null ? 0 : recipients.totalCount());
  }

  /**
   * Starts the card with its issuer: the company name and, when the tenant has a PNG/JPEG logo,
   * its token-free public URL; no logo, the initials. As the decision confirmation's.
   */
  private EmailContent.Builder issuer(PortalLink link, String tenantName) {
    EmailContent.Builder content = EmailContent.builder();
    Optional<PortalLogoReference.PublicLogo> logo = logoFinder.apply(link.getClientId(),
        link.getOrganizationId());
    if (logo.isPresent()) {
      content.issuer(tenantName, logo.get().getUrl())
          .issuerLogoSize(logo.get().getWidth(), logo.get().getHeight());
    } else {
      content.issuer(tenantName, null);
    }
    return content;
  }

  private Optional<PortalLink> findLink(EmailContractCommand command) {
    String linkId = linkId(command);
    return linkId == null ? Optional.empty() : linkService.findLink(linkId);
  }

  private static String linkId(EmailContractCommand command) {
    return StringUtils.trimToNull(
        EmailContractCommandSupport.text(command, PortalLinkService.FIELD_LINK_ID));
  }

  private static List<String> validOnly(List<String> addresses) {
    List<String> valid = new ArrayList<>();
    for (String address : addresses) {
      if (EmailContractCommandSupport.isValidEmail(address)) {
        valid.add(address.trim());
      }
    }
    return valid;
  }
}
