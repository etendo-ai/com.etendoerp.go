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
 * All portions are Copyright © 2021–2026 FUTIT SERVICES, S.L
 * All Rights Reserved.
 * Contributor(s): Futit Services S.L.
 * *************************************************************************
 */

package com.etendoerp.go.schemaforge;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONObject;
import org.openbravo.dal.service.OBDal;
import org.openbravo.dal.service.OBQuery;
import org.openbravo.model.financialmgmt.payment.FIN_BankStatementLine;

/**
 * {@code counts.discardedPairs} of {@code pendingLines}: how many discarded PAIRS the discarded
 * lines of the answer hold — the figure of the panel's "Descartadas (N)" filter — next to
 * {@code counts.discarded}, which counts lines.
 *
 * <p><b>Pairing rule</b>, the same as the SPA's {@code buildDiscardedPairRows}: two discarded lines
 * of the answer form ONE pair when each one's {@code EM_ETGO_Bounce_Pair_Line_ID} points at the
 * other. Any other discarded line counts as one on its own: no link (discarded alone in Classic),
 * its partner not among the answer's discarded lines (restored, or outside the date range), or a
 * link that does not point back. Lines are taken in answer order and each joins at most one pair.
 *
 * <p>Only reached with {@code bank-bounce-suggestions} on ({@link StatementLineFilter.Discards}).
 * The link column is read by its column name ({@link StatementLineDiscard#propertyName}); while
 * the running model does not map it, every line counts on its own.
 */
final class BankBounceDiscardedPairs {

  /** Key of the count in {@code counts}. */
  static final String KEY_DISCARDED_PAIRS = "discardedPairs";

  private static final String ALIAS = "bsl";
  private static final String PARAM_LINE_IDS = "etgoLineIds";

  /** Utility class: static members only, never instantiated. */
  private BankBounceDiscardedPairs() {
  }

  /**
   * The pair count of the discarded rows of a {@code pendingLines} answer, reading their links in
   * ONE query. No query for fewer than two lines, or when the link column is not mapped.
   *
   * @param rows the discarded rows, each with an {@code id}
   * @return how many pairs (or lone lines) they hold
   */
  static int count(JSONArray rows) {
    List<String> ids = rowIds(rows);
    if (ids.size() < 2) {
      return ids.size();
    }
    String pairProperty = StatementLineDiscard.propertyName(BankBouncePairService.COL_PAIR_LINE);
    return countPairs(ids, pairProperty == null ? Map.of() : readLinks(ids, pairProperty));
  }

  /**
   * The pair count of discarded rows whose lines are already loaded (the {@code status=discarded}
   * listing), with no further query.
   *
   * @param rows      the discarded rows, each with an {@code id}
   * @param linesById the loaded lines, by id
   * @return how many pairs (or lone lines) they hold
   */
  static int count(JSONArray rows, Map<String, FIN_BankStatementLine> linesById) {
    List<String> ids = rowIds(rows);
    Map<String, String> links = new HashMap<>();
    for (String id : ids) {
      Object partner =
          StatementLineDiscard.get(linesById.get(id), BankBouncePairService.COL_PAIR_LINE);
      if (partner instanceof FIN_BankStatementLine) {
        links.put(id, ((FIN_BankStatementLine) partner).getId());
      }
    }
    return countPairs(ids, links);
  }

  /**
   * The pairing rule over plain ids: a line and the line its link names are one entry when that
   * line is among {@code ids}, is not taken yet and links back; every other line is an entry of
   * its own. A repeated id counts once.
   *
   * @param ids      the discarded line ids, in answer order
   * @param linkById the counterpart each line links to, by line id (absent when none)
   * @return the number of entries
   */
  static int countPairs(List<String> ids, Map<String, String> linkById) {
    Set<String> present = new HashSet<>(ids);
    Set<String> taken = new HashSet<>();
    int entries = 0;
    for (String id : ids) {
      if (!taken.add(id)) {
        continue;
      }
      entries++;
      String partner = linkById.get(id);
      if (partner != null && present.contains(partner) && !taken.contains(partner)
          && id.equals(linkById.get(partner))) {
        taken.add(partner);
      }
    }
    return entries;
  }

  /**
   * The counterpart each of the lines links to. One OBQuery, inactive lines included as in the
   * {@code status=discarded} listing; only linked lines are returned.
   *
   * @param ids          the line ids
   * @param pairProperty the DAL property of {@code EM_ETGO_Bounce_Pair_Line_ID}
   * @return the counterpart id by line id
   */
  private static Map<String, String> readLinks(Collection<String> ids, String pairProperty) {
    String link = ALIAS + "." + pairProperty;
    OBQuery<FIN_BankStatementLine> query = OBDal.getInstance().createQuery(
        FIN_BankStatementLine.class, "as " + ALIAS + " where " + ALIAS + ".id in (:"
            + PARAM_LINE_IDS + ") and " + link + " is not null");
    query.setNamedParameter(PARAM_LINE_IDS, ids);
    query.setFilterOnActive(false);
    query.setSelectClause(ALIAS + ".id, " + link + ".id");
    Map<String, String> links = new HashMap<>();
    for (Object[] row : query.createQuery(Object[].class).list()) {
      links.put((String) row[0], (String) row[1]);
    }
    return links;
  }

  /**
   * The non-blank ids of the rows, in order.
   *
   * @param rows the rows
   * @return the ids
   */
  private static List<String> rowIds(JSONArray rows) {
    List<String> ids = new ArrayList<>();
    for (int i = 0; i < rows.length(); i++) {
      JSONObject row = rows.optJSONObject(i);
      String id = row != null ? row.optString(ReconciliationHandler.KEY_ID, "") : "";
      if (!id.isEmpty()) {
        ids.add(id);
      }
    }
    return ids;
  }
}
