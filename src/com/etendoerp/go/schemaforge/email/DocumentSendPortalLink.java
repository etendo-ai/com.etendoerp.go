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

package com.etendoerp.go.schemaforge.email;

import java.time.Instant;
import java.util.Optional;

import org.apache.commons.lang3.StringUtils;

import com.etendoerp.go.portal.PortalDocumentKind;
import com.etendoerp.go.portal.PortalLinkService;
import com.etendoerp.go.portal.PortalLogoReference;
import com.etendoerp.go.schemaforge.email.render.EmailContent;
import com.etendoerp.go.schemaforge.email.render.EmailDates;
import com.etendoerp.go.schemaforge.email.render.EmailMessages;
import com.etendoerp.go.schemaforge.email.render.ValidityWindow;

/**
 * The customer portal link of a document email (ETP-6209): minting it, the sentence that announces
 * it and the button it turns into. Kept out of {@link DefaultDocumentSendEmailContract} so the
 * shared send path only asks whether its contract declared a portal document kind.
 */
public final class DocumentSendPortalLink {

  /**
   * The {@code SENT} response field saying whether the email carried the portal link
   * ({@code DefaultDocumentSendEmailContract#deliveredFacts}); read by the MCP send tool.
   */
  public static final String FACT_PORTAL_LINK = "portalLink";

  /** The resolver's label key of the document's total, which the card shows as «Importe». */
  private static final String TOTAL_KEY = "document.detail.total";

  private DocumentSendPortalLink() {
  }

  /**
   * Mints the pending link of this send, when the contract declared a portal document kind. It
   * becomes usable only in {@code onDelivered}, i.e. only if the email leaves.
   *
   * @param service the portal link service
   * @param kind the contract's declared kind, {@code null} for a contract with no portal link
   * @param recordId the document
   * @param recipients the send's recipients (To and CC renew the link later)
   * @param language the email's language
   * @param contractName the sending contract
   * @return the minted link, or empty
   */
  static Optional<PortalLinkService.MintedLink> mint(PortalLinkService service,
      PortalDocumentKind kind, String recordId, EmailRecipientSet recipients, String language,
      String contractName) {
    if (kind == null) {
      return Optional.empty();
    }
    return service.mintForSend(kind, recordId, recipients.getTo(), recipients.getCc(),
        language, contractName);
  }

  /**
   * The email's one button: the portal when a link was minted, else the document download
   * (ETP-6209 Part B: with a link, the portal shows the same printable).
   *
   * @param link the minted link, or {@code null}
   * @param downloadLink the document's download link
   * @param language the email's language
   * @return the button
   */
  static CallToAction callToAction(PortalLinkService.MintedLink link, String downloadLink,
      String language) {
    return new CallToAction(
        EmailMessages.get(link != null ? "document.portal.cta" : "document.cta", language),
        link != null ? link.getUrl() : downloadLink, link);
  }

  /**
   * Starts the card design of a document email that carries a portal link (ETP-6209 QA CP-48): the
   * same centred card as the portal decision confirmations ({@code EmailCardLayout}), so the two
   * customer emails look alike. The issuer block shows the company's public logo (token-free,
   * {@link PortalLogoReference}) or, without one, its initials; the title names the document.
   *
   * @param link the minted link, which carries the issuing tenant and organization
   * @param title the card's headline
   * @param language the email's language
   * @return a builder with the issuer, the title and the language set
   */
  static EmailContent.Builder cardBuilder(PortalLinkService.MintedLink link, String title,
      String language) {
    EmailContent.Builder content = EmailContent.builder();
    String company = StringUtils.defaultString(link.getClientName());
    Optional<PortalLogoReference.PublicLogo> logo = PortalLogoReference.find(link.getClientId(),
        link.getOrganizationId());
    if (logo.isPresent()) {
      content.issuer(company, logo.get().getUrl())
          .issuerLogoSize(logo.get().getWidth(), logo.get().getHeight());
    } else {
      content.issuer(company, null);
    }
    return content.title(title).language(language);
  }

  /**
   * The card's summary box, «Datos del documento»: the company, the document number and its amount
   * (Empresa · Nº · Importe, the confirmation's columns). A document without an amount (a goods
   * shipment) shows its first date instead, so the box keeps three facts.
   *
   * @param content the card being built
   * @param link the minted link (its tenant names the company)
   * @param document the resolved document
   * @param language the email's language
   */
  static void appendCardSummary(EmailContent.Builder content, PortalLinkService.MintedLink link,
      EmailDocumentRecord document, String language) {
    content.summaryTitle(EmailMessages.get("document.card.summary.title", language))
        .detail(EmailMessages.get("document.card.summary.company", language),
            StringUtils.defaultString(link.getClientName()))
        .detail(EmailMessages.get("document.card.summary.number", language),
            document.getDocumentNumber());
    EmailDocumentDetail third = null;
    for (EmailDocumentDetail detail : document.getDetails()) {
      if (TOTAL_KEY.equals(detail.getLabelKey())) {
        third = detail;
        break;
      }
      if (third == null) {
        third = detail;
      }
    }
    if (third == null) {
      return;
    }
    boolean amount = TOTAL_KEY.equals(third.getLabelKey());
    String label = amount ? EmailMessages.get("document.card.summary.amount", language)
        : EmailMessages.get(third.getLabelKey(), language);
    String value = third.isDate() ? EmailDates.format(third.getDate(), language) : third.getText();
    if (StringUtils.isNotBlank(value)) {
      content.detail(label, value);
    }
  }

  /**
   * The portal sentence as the line above the card's button: the same copy as the letter's
   * paragraph, as plain text (the card escapes it; its bold markers are dropped).
   *
   * @param link the minted link
   * @param language the email's language
   * @return the sentence
   */
  static String ctaNote(PortalLinkService.MintedLink link, String language) {
    Instant now = Instant.now();
    long hours = ValidityWindow.hoursUntil(now, now.plus(link.getValidity()));
    return EmailMessages.get("document.portal.intro", language, hours).replace("**", "");
  }

  /** The email's one button: its label, where it leads and the portal link it is, if any. */
  static final class CallToAction {
    private final String label;
    private final String url;
    private final PortalLinkService.MintedLink portalLink;

    CallToAction(String label, String url, PortalLinkService.MintedLink portalLink) {
      this.label = label;
      this.url = url;
      this.portalLink = portalLink;
    }

    /** @return the portal link the button leads to, or {@code null} for the download button */
    PortalLinkService.MintedLink getPortalLink() {
      return portalLink;
    }

    /**
     * Whether the button leads to the customer portal, i.e. the email carries a portal link. Only
     * then does it close with the Etendo footer (ETP-6209); with the {@code bp-portal-link} flag
     * off no link is minted and the email stays exactly as it was.
     *
     * @return {@code true} for the portal button
     */
    boolean isPortal() {
      return portalLink != null;
    }

    String getLabel() {
      return label;
    }

    String getUrl() {
      return url;
    }
  }
}
