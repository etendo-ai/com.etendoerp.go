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

package com.etendoerp.go.schemaforge;

import java.io.IOException;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

import javax.servlet.http.HttpServletResponse;

import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.openbravo.base.exception.OBException;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.ad.utility.Attachment;

import com.etendoerp.go.schemaforge.email.DocShareLinkService;
import com.etendoerp.go.schemaforge.email.DocumentDownloadTokenService;
import com.etendoerp.go.schemaforge.email.DocumentDownloadTokenService.Claims;

/**
 * Serves email document downloads from share links and signed links.
 * <p>
 * The lookup and the streaming are {@link AnonymousAttachmentStreamer}'s, shared with the customer
 * portal's PDF (ETP-6209): both run without an Etendo session.
 * <p>
 * Package-private by design: only NeoServlet exposes this behavior over HTTP.
 */
final class NeoDocumentDownloadService {

  private static final Logger log = LogManager.getLogger(NeoDocumentDownloadService.class);

  // ETP-4315 — the attachment (C_File) is bound to the table of the spec's header: its included
  // entity on a level-0 tab, first by SeqNo (ETP-5588: read from the spec's own metadata instead
  // of a per-window map, so no window is named here). A spec without one, or a record whose
  // "main" attachment was since replaced, resolves to 404 rather than falling back to the
  // retired legacy preview-file cache (retired, ETP-4315 Phase 9). Native SQL for the same reason
  // as the attachment lookup: this endpoint runs without a session, so no client filter applies.
  private static final String HEADER_TABLE_SQL =
      "SELECT tb.ad_table_id FROM etgo_sf_spec s"
          + " JOIN etgo_sf_entity e ON e.etgo_sf_spec_id = s.etgo_sf_spec_id"
          + " JOIN ad_tab tb ON tb.ad_tab_id = e.ad_tab_id"
          + " WHERE LOWER(s.name) = LOWER(:spec) AND s.isactive = 'Y' AND e.isactive = 'Y'"
          + " AND e.isincluded = 'Y' AND tb.tablevel = 0"
          + " ORDER BY e.seqno LIMIT 1";

  private static final String NOT_FOUND_MESSAGE = "Document not found";

  /**
   * User-Agent tokens of email security scanners and link-preview fetchers that announce
   * themselves (ETP-5035): a download from one of them is served but not counted as an open of the
   * share link. Matched as case-insensitive substrings, so each token must be one no browser
   * sends. Conservative on purpose: many scanners present a plain browser User-Agent, and those
   * still count.
   * <ul>
   * <li>Microsoft: Defender for Office 365 Safe Links ({@code SafeLinks}), Bing/Outlook link
   * preview ({@code BingPreview}).</li>
   * <li>Google: Gmail image proxy ({@code GoogleImageProxy}), Safe Browsing fetcher
   * ({@code Google-Safety}).</li>
   * <li>Gateways naming themselves: Mimecast, Proofpoint, Barracuda, Symantec / Broadcom
   * (MessageLabs), Cisco IronPort, Trend Micro, Sophos.</li>
   * </ul>
   */
  static final List<String> EMAIL_SCANNER_USER_AGENT_TOKENS = List.of("safelinks", "bingpreview",
      "googleimageproxy", "google-safety", "mimecast", "proofpoint", "barracuda", "symantec",
      "broadcom", "messagelabs", "ironport", "trendmicro", "trend micro", "sophos");

  private NeoDocumentDownloadService() {
  }

  /**
   * Serves the document a download link points to.
   *
   * @param token the path segment after the download path: a share code or a signed token
   * @param userAgent the request's {@code User-Agent} header, may be {@code null}
   * @param response the response the file or the error is written to
   * @throws IOException when the response cannot be written
   */
  static void handle(String token, String userAgent, HttpServletResponse response)
      throws IOException {
    // ETP-5035: a short share code is looked up in ETGO_Doc_Share_Link; anything else is a signed
    // token from a link already in an inbox. An unknown, expired or revoked code answers the same
    // 404 as a missing file, so the response does not tell whether the record exists.
    boolean shareCode = DocShareLinkService.isShareCode(token);
    Optional<Claims> claims = shareCode ? DocShareLinkService.resolve(token)
        : DocumentDownloadTokenService.validate(token);
    if (!claims.isPresent()) {
      if (shareCode) {
        writePlainError(response, HttpServletResponse.SC_NOT_FOUND, NOT_FOUND_MESSAGE);
      } else {
        writePlainError(response, HttpServletResponse.SC_FORBIDDEN, "Invalid or expired link");
      }
      return;
    }
    Claims validated = claims.get();
    Attachment attachment = resolveAttachment(validated);
    if (attachment == null) {
      writePlainError(response, HttpServletResponse.SC_NOT_FOUND,
          shareCode ? NOT_FOUND_MESSAGE : "Document file not found");
      return;
    }
    if (shareCode && !isEmailScanner(userAgent)) {
      recordOpenQuietly(token, validated);
    }
    AnonymousAttachmentStreamer.stream(attachment, response,
        "spec=" + validated.getSpecName() + " record=" + validated.getRecordId());
  }

  /**
   * Whether a request comes from an email security scanner or link-preview fetcher that names
   * itself in its User-Agent ({@link #EMAIL_SCANNER_USER_AGENT_TOKENS}).
   *
   * @param userAgent the request's {@code User-Agent} header, may be {@code null}
   * @return {@code true} when the User-Agent holds a known scanner token
   */
  static boolean isEmailScanner(String userAgent) {
    if (StringUtils.isBlank(userAgent)) {
      return false;
    }
    String normalized = userAgent.toLowerCase(Locale.ROOT);
    return EMAIL_SCANNER_USER_AGENT_TOKENS.stream().anyMatch(normalized::contains);
  }

  /**
   * Counts an open of a share link without ever blocking the download: a failure is logged and the
   * document is still served. The row is committed with the request's own transaction (the DAL
   * request filter). The log names the document, never the code: the code is a live credential.
   */
  private static void recordOpenQuietly(String code, Claims validated) {
    try {
      DocShareLinkService.recordOpen(code);
    } catch (RuntimeException e) {
      log.warn("Could not record the open of a document share link (spec={} record={}): {}",
          validated.getSpecName(), validated.getRecordId(), e.getMessage(), e);
    }
  }

  /**
   * The attachment a validated token serves: the one it names when it carries an attachment id
   * (the MCP's download link, ETP-5715), otherwise the record's main attachment (the email links).
   */
  private static Attachment resolveAttachment(Claims validated) {
    if (validated.getAttachmentId() == null) {
      return resolveMainAttachment(validated);
    }
    return resolveNamedAttachment(validated);
  }

  /**
   * Resolves the exact attachment an attachment token names, or {@code null} when it no longer
   * exists, belongs to another record than the one signed, or to another client than the signed one
   * (the same client check as {@link #resolveMainAttachment}, since this lookup is not filtered by
   * client either). The signature already proves the id was authorized when the link was issued;
   * this makes a deleted or foreign attachment answer the same 404 as an unknown one.
   */
  private static Attachment resolveNamedAttachment(Claims validated) {
    Attachment attachment = OBDal.getInstance().get(Attachment.class, validated.getAttachmentId());
    if (attachment == null || attachment.getClient() == null
        || !attachment.getClient().getId().equals(validated.getClientId())
        || !StringUtils.equals(attachment.getRecord(), validated.getRecordId())) {
      return null;
    }
    return attachment;
  }

  /**
   * Resolves the attachment currently marked as "main" for the token's
   * (specName, recordId) — the same file the sidebar/preview would show —
   * or {@code null} if the spec has no header entity, no attachment is
   * currently marked (e.g. it was replaced since the link was sent), or the
   * resolved attachment does not belong to the token's client.
   */
  private static Attachment resolveMainAttachment(Claims validated) {
    try {
      String tableId = resolveHeaderTableId(validated.getSpecName());
      if (tableId == null) {
        return null;
      }
      return AnonymousAttachmentStreamer.findMainAttachmentOfClientByTableId(tableId,
          validated.getRecordId(), validated.getClientId());
    } catch (OBException e) {
      log.warn("Could not resolve the attachment table for spec={}: {}",
          validated.getSpecName(), e.getMessage());
      return null;
    }
  }

  /** The AD_Table id of the spec's header entity, or {@code null} when it has none. */
  private static String resolveHeaderTableId(String specName) {
    if (StringUtils.isBlank(specName)) {
      return null;
    }
    List<?> rows = OBDal.getInstance().getSession()
        .createNativeQuery(HEADER_TABLE_SQL)
        .setParameter("spec", specName)
        .list();
    return rows.isEmpty() ? null : (String) rows.get(0);
  }

  private static void writePlainError(HttpServletResponse response, int status, String message)
      throws IOException {
    AnonymousAttachmentStreamer.writePlainError(response, status, message);
  }
}
