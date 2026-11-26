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

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

import org.apache.commons.lang3.StringUtils;
import org.openbravo.dal.service.OBDal;

import com.etendoerp.go.schemaforge.email.EmailContractCommandSupport;
import com.etendoerp.go.schemaforge.email.EmailRecipientSet;
import com.etendoerp.go.schemaforge.email.TransactionalEmailService;

/**
 * The emails a quotation was really sent with, read from its send history
 * ({@code etgo_email_send_log}), and the one a portal session may decide it from (ETP-6209 QA
 * CP-49 and CP-45).
 *
 * <h2>The rule (CP-49)</h2>
 * A quotation can be accepted or rejected from the portal only when it was <b>sent to the email the
 * portal was accessed with</b>. Precisely:
 * <ul>
 *   <li><b>The accessing email</b> is the To and CC of the email that carried the session's link
 *       ({@link PortalSession#getAccessRecipients()}). Who of them opened it cannot be known, so
 *       the set stands for it.</li>
 *   <li><b>The quotation's sends</b> are the rows of its send history written by the quotation's
 *       own document-send contract ({@code sales-quotation-send}, from the app or the MCP) with
 *       status {@code SENT}: To and CC of each. A portal renewal or a decision confirmation filed
 *       under the quotation is not a send of the quotation.</li>
 *   <li>The session may decide when <b>at least one address is in both</b> (compared
 *       case-insensitively). The newest such send is <b>the original send</b> CP-45 answers in: the
 *       confirmation goes to every To of it, never its CC ({@link #recipientsOf}).</li>
 * </ul>
 * A link to the quotation itself always qualifies (its own send is one of the quotation's sends).
 * A quotation never emailed, or emailed only to other addresses, does not: it stays readable,
 * without buttons.
 *
 * <p>Admin mode by the caller; every query states its tenant, as the rest of the portal.
 */
public final class PortalQuotationSends {

  /** Suffix of every document-send contract: {@code <spec>-send}. */
  private static final String SEND_CONTRACT_SUFFIX = "-send";
  private static final String RECIPIENT_SEPARATOR = ",";

  private PortalQuotationSends() {
  }

  /** One send of a quotation: its recipients and subject. */
  public static final class Send {
    private final String id;
    private final List<String> to;
    private final List<String> cc;
    private final String subject;

    /**
     * Creates one send of a quotation, as its history row recorded it.
     *
     * @param id the send history row
     * @param to its To addresses
     * @param cc its CC addresses
     * @param subject its subject, or {@code null}
     */
    public Send(String id, List<String> to, List<String> cc, String subject) {
      this.id = id;
      this.to = to;
      this.cc = cc;
      this.subject = subject;
    }

    /** @return the {@code etgo_email_send_log} row */
    public String getId() {
      return id;
    }

    public List<String> getTo() {
      return to;
    }

    public List<String> getCc() {
      return cc;
    }

    /** @return the subject the customer received, or {@code null} */
    public String getSubject() {
      return subject;
    }
  }

  /**
   * The newest send of the quotation that reached one of the session's access addresses.
   *
   * @param session the validated session
   * @param quotationId the quotation, already in the session's scope
   * @return the send, or empty when the quotation was never sent to the accessing email
   */
  static Optional<Send> originalSend(PortalSession session, String quotationId) {
    return originalSend(session.getClientId(), quotationId, session.getAccessRecipients());
  }

  /**
   * As {@link #originalSend(PortalSession, String)}, from a link's own recipients: the
   * confirmation contract resolves its recipients from the request's link.
   *
   * @param clientId the tenant
   * @param quotationId the quotation
   * @param accessRecipients the To and CC of the access link's email
   * @return the send, or empty
   */
  public static Optional<Send> originalSend(String clientId, String quotationId,
      Collection<String> accessRecipients) {
    Set<String> access = keys(accessRecipients);
    if (access.isEmpty() || StringUtils.isAnyBlank(clientId, quotationId)) {
      return Optional.empty();
    }
    for (Send send : sendsOf(clientId, quotationId)) {
      if (intersects(access, send.getTo()) || intersects(access, send.getCc())) {
        return Optional.of(send);
      }
    }
    return Optional.empty();
  }

  /**
   * The recipients of a decision's confirmation: the valid To addresses of the quotation's original
   * send, every one of them and never its CC (ETP-6209 QA CP-45, as revised by the PO). The CC
   * still counts for deciding who may decide ({@link #originalSend}); it is only not mailed.
   *
   * @param send the quotation's original send
   * @return the recipients, To only; empty when no To is a valid address
   */
  public static EmailRecipientSet recipientsOf(Send send) {
    return EmailRecipientSet.of(validOnly(send.getTo()), Collections.emptyList());
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

  /** The quotation's successful sends, newest first. */
  private static List<Send> sendsOf(String clientId, String quotationId) {
    List<?> rows = OBDal.getInstance().getSession().createNativeQuery(
        "SELECT etgo_email_send_log_id, recipients_to, recipients_cc, subject"
            + " FROM etgo_email_send_log WHERE ad_client_id = :client AND record_id = :record"
            + " AND contract_name = :contract AND status = :status AND isactive = 'Y'"
            + " ORDER BY sent_at DESC, created DESC")
        .setParameter("client", clientId)
        .setParameter("record", quotationId)
        .setParameter("contract",
            PortalDocumentKind.QUOTATION.getSpecName() + SEND_CONTRACT_SUFFIX)
        .setParameter("status", TransactionalEmailService.STATUS_SENT)
        .list();
    List<Send> sends = new ArrayList<>();
    for (Object item : rows) {
      Object[] row = (Object[]) item;
      sends.add(new Send((String) row[0], split((String) row[1]), split((String) row[2]),
          StringUtils.trimToNull((String) row[3])));
    }
    return sends;
  }

  private static boolean intersects(Set<String> access, List<String> addresses) {
    for (String address : addresses) {
      if (access.contains(key(address))) {
        return true;
      }
    }
    return false;
  }

  private static Set<String> keys(Collection<String> addresses) {
    Set<String> keys = new HashSet<>();
    if (addresses != null) {
      for (String address : addresses) {
        String key = key(address);
        if (key != null) {
          keys.add(key);
        }
      }
    }
    return keys;
  }

  private static String key(String address) {
    String trimmed = StringUtils.trimToNull(address);
    return trimmed == null ? null : trimmed.toLowerCase(Locale.ROOT);
  }

  /** The history stores recipients joined by {@code ", "}; addresses never contain a comma. */
  static List<String> split(String joined) {
    if (StringUtils.isBlank(joined)) {
      return Collections.emptyList();
    }
    List<String> addresses = new ArrayList<>();
    for (String part : joined.split(RECIPIENT_SEPARATOR)) {
      String address = StringUtils.trimToNull(part);
      if (address != null) {
        addresses.add(address);
      }
    }
    return addresses;
  }
}
