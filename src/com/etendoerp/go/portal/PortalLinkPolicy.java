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

package com.etendoerp.go.portal;

import java.util.Optional;

import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.etendoerp.go.common.GoAccountResolver;
import com.etendoerp.go.common.PublicUrlResolver;
import com.etendoerp.go.featureflags.FeatureFlagContext;
import com.etendoerp.go.featureflags.GoFeatureFlags;

/**
 * The single gate that decides whether a sales document email carries a portal link, and the
 * builder of the link itself.
 *
 * <p><b>The gate does not protect the portal.</b> The {@code /portal/:token} route, the
 * {@code /sws/portal/*} endpoints and the {@code etgo_portal_link} table all ship unconditionally —
 * what protects them is the token (plan §5). The gate decides one thing only: whether a quotation,
 * order, shipment or invoice email carries the link (ETP-6209; the MVP plan §2.5 covered the
 * invoice only). A renewal is not gated: it replaces a link the gate already let out.
 *
 * <p><b>One gate: the {@link GoFeatureFlags#FLAG_BP_PORTAL_LINK} flag, evaluated for the account
 * sending the invoice.</b> Enabling one person is a ConfigCat targeting rule on that account's
 * email; with nothing configured the answer is {@code false} for everyone. There is no second
 * condition to satisfy, and deliberately so — the feature was briefly built with an environment
 * flag AND a per-sender {@code AD_Preference}, which meant nothing worked until two unrelated
 * things were configured.
 *
 * <p>With no ConfigCat SDK key the flag degrades to a plain per-environment boolean, so a shared
 * environment needs ConfigCat configured before the link is switched on for anyone. A dev box has
 * one user, so the boolean is enough there.
 *
 * <p><b>The identity is the {@code ETGO_ACCOUNT} email, not {@code AD_User.email}.</b> That is not
 * interchangeable: onboarding never writes {@code AD_User.email} — {@code InitialSetupUtility}
 * only writes {@code username} — so targeting on the AD user's email would read {@code null} for
 * essentially every Etendo Go user and the flag would never match anyone. The account is therefore
 * resolved from the AD username through {@link GoAccountResolver}, which is also what handles the
 * {@code <accountEmail>+<clientName>} username a second environment gets. Same resolution
 * {@code NeoSessionService} uses to put the account identity on a session.
 *
 * <p>No account, no email on it, or any failure resolving it ⇒ <b>no link</b>. There is no fallback
 * to "allow": every unknown answers the same way an unlisted account does.
 */
public final class PortalLinkPolicy {

  /** Path the SPA registers for the public portal route. */
  static final String PORTAL_ROUTE = "portal";

  private static final Logger log = LogManager.getLogger(PortalLinkPolicy.class);

  private PortalLinkPolicy() {
  }

  /**
   * Evaluates the gate: is the portal link switched on for the account sending this document.
   *
   * <p>The account is resolved by {@link GoAccountResolver#currentAccountEmail()}; no account, or
   * any failure resolving it, answers "no link", never "allow".
   *
   * @return {@code true} only when the flag positively resolves true for the sending account
   */
  public static boolean isLinkEnabled() {
    return GoFeatureFlags.isEnabled(GoFeatureFlags.FLAG_BP_PORTAL_LINK,
        FeatureFlagContext.forAccount(GoAccountResolver.currentAccountEmail()));
  }

  /**
   * Builds the customer-facing portal URL for a token.
   *
   * <p>The base URL is the tenant's own already-deployed app shell
   * ({@code etendo.go.app.baseUrl}), which is what makes "portal per tenant" fall out of the
   * existing deployment with no extra mechanism. With no configured app base URL there is no link
   * to build — the request that would need one has no {@code HttpServletRequest} to derive it from,
   * since it originates in the invoice-send path rather than in a browser navigation.
   *
   * <p><b>Shape: {@code /portal/{AD_Client_ID}/{token}}</b> (ETP-6209 QA CP-43). The tenant id is
   * in the path so the SPA can replace the browser URL with {@code /portal/{AD_Client_ID}} (no
   * token) before any third-party tag (GTM, Google Analytics, HubSpot) reads it: the token is the
   * credential and must never leave the page. The tenant id is not a secret and grants nothing on
   * its own; the server only checks it against the token's own client
   * ({@link PortalLinkService#validate(String, String)}). Links sent in the MVP shape
   * {@code /portal/{token}} keep working: the client is then simply not presented.
   *
   * @param clientId the tenant the link belongs to ({@code AD_Client_ID})
   * @param token the opaque token
   * @return the absolute link, or empty when the client, the token or the configured app base URL
   *     is missing
   */
  public static Optional<String> buildLink(String clientId, String token) {
    return buildPath(clientId, null, token);
  }

  /**
   * Builds a customer-facing URL under the tenant's portal route:
   * {@code /portal/{AD_Client_ID}[/{segment}]/{token}}. The decision confirmation link uses it with
   * the {@code decision} segment ({@code /portal/{AD_Client_ID}/decision/{token}}), so every
   * portal URL a customer receives shares the {@code /portal/{AD_Client_ID}} prefix the SPA strips
   * the credential after.
   *
   * @param clientId the tenant ({@code AD_Client_ID})
   * @param segment an optional route segment between the tenant and the token, or {@code null}
   * @param token the opaque token
   * @return the absolute link, or empty when the client, the token or the configured app base URL
   *     is missing
   */
  static Optional<String> buildPath(String clientId, String segment, String token) {
    String normalizedToken = StringUtils.trimToNull(token);
    String normalizedClient = StringUtils.trimToNull(clientId);
    if (normalizedToken == null || normalizedClient == null) {
      return Optional.empty();
    }
    String baseUrl = PublicUrlResolver.resolveConfiguredAppBaseUrl();
    if (StringUtils.isBlank(baseUrl)) {
      log.warn("A portal link was requested but {} is not configured; no link will be sent",
          PublicUrlResolver.APP_BASE_URL_PROPERTY);
      return Optional.empty();
    }
    String middle = StringUtils.isBlank(segment) ? "" : segment.trim() + "/";
    return Optional.ofNullable(PublicUrlResolver.appendPath(baseUrl,
        PORTAL_ROUTE + "/" + normalizedClient + "/" + middle + normalizedToken));
  }

  /**
   * Whether a client id presented next to a token (the {@code /portal/{AD_Client_ID}} part of the
   * URL) is compatible with the token's own client: absent (a link in the MVP shape) or equal.
   * A mismatch makes the token invalid — the same answer as an unknown token, so the check is no
   * oracle for which tenant a token belongs to.
   *
   * @param presentedClientId the client id the caller presented, or {@code null}
   * @param linkClientId the client of the link the token resolved to
   * @return {@code true} when the presented client is absent or matches
   */
  static boolean clientMatches(String presentedClientId, String linkClientId) {
    String presented = StringUtils.trimToNull(presentedClientId);
    return presented == null || presented.equals(linkClientId);
  }
}
