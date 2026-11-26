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

package com.etendoerp.go.schemaforge.ocr;

import static com.etendoerp.go.schemaforge.ocr.InboxDocument.K_CANDIDATES;
import static com.etendoerp.go.schemaforge.ocr.InboxDocument.K_ID;
import static com.etendoerp.go.schemaforge.ocr.InboxDocument.K_MATCHED_BY;
import static com.etendoerp.go.schemaforge.ocr.InboxDocument.K_NAME;
import static com.etendoerp.go.schemaforge.ocr.InboxDocument.K_STATE;
import static com.etendoerp.go.schemaforge.ocr.InboxDocument.K_TAX_ID;
import static com.etendoerp.go.schemaforge.ocr.InboxDocument.S_AMBIGUOUS;
import static com.etendoerp.go.schemaforge.ocr.InboxDocument.S_LINKED;
import static com.etendoerp.go.schemaforge.ocr.InboxDocument.S_NEW;
import static com.etendoerp.go.schemaforge.ocr.InboxDocument.S_NONE;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONException;
import org.codehaus.jettison.json.JSONObject;

import com.etendoerp.go.schemaforge.ocr.OcrLookups.Party;

/**
 * Finds the vendor of an extracted document without asking anyone (ETP-6048).
 *
 * <p>By tax id first: one vendor with that id (with or without its VAT prefix) is the match, several
 * are ambiguous. Then by name, where only an exact normalized name held by a single vendor with no
 * conflicting tax id is linked automatically; a name that merely <i>contains</i> the text, even with
 * a single hit, stays ambiguous, because "Acme" and "Acme Logistics" are different companies.
 * Nothing found is {@code new} when the document names the vendor, {@code none} when it does not.</p>
 */
final class InboxVendorMatcher {

  /** Shortest cleaned tax id worth searching by. */
  static final int MIN_TAX_ID_LENGTH = 5;
  private static final int TAX_ID_LIMIT = 10;
  private static final int NAME_SEARCH_LIMIT = 100;
  private static final int CANDIDATE_LIMIT = 10;
  private static final String BY_TAX_ID = "taxId";
  private static final String BY_NAME = "name";
  /** Candidate flag: the one the panel tags as the most probable (ETP-6051). */
  private static final String K_PROBABLE = "probable";

  private final OcrLookups lookups;

  InboxVendorMatcher(OcrLookups lookups) {
    this.lookups = lookups;
  }

  /**
   * Matches one vendor.
   *
   * @param name  the vendor name as read, may be empty
   * @param taxId the vendor tax id as read, may be empty
   * @return {@code {state, matchedBy?, id?, name?, candidates}}
   * @throws JSONException when the object cannot be built
   */
  JSONObject match(String name, String taxId) throws JSONException {
    String clean = TaxIdNormalizer.clean(taxId);
    if (clean.length() >= MIN_TAX_ID_LENGTH) {
      List<Party> byTax = lookups.vendorsByTaxId(clean, TAX_ID_LIMIT);
      if (!byTax.isEmpty()) {
        return resolved(byTax, BY_TAX_ID);
      }
    }
    return matchByName(name, clean);
  }

  private JSONObject resolved(List<Party> parties, String matchedBy) throws JSONException {
    if (parties.size() == 1) {
      return linked(parties.get(0), matchedBy);
    }
    return ambiguous(withTaxIds(parties), matchedBy, false);
  }

  private JSONObject matchByName(String name, String cleanTaxId) throws JSONException {
    if (!OcrJson.filled(name)) {
      return result(S_NONE);
    }
    List<Party> rows = lookups.searchVendors(name, NAME_SEARCH_LIMIT);
    String wanted = OcrProductMatch.normalize(name);
    List<Party> exact = rows.stream()
        .filter(row -> OcrProductMatch.normalize(row.name()).equals(wanted))
        .collect(Collectors.toList());
    if (exact.size() == 1) {
      return exactName(exact.get(0), cleanTaxId);
    }
    JSONObject found = exact.isEmpty() ? null : tiered(exact, wanted, cleanTaxId);
    if (found == null) {
      // No exact name, or all the exact ones contradict the tax id: partial rows may still be fine.
      found = tiered(rows, wanted, cleanTaxId);
    }
    return found == null ? result(S_NEW) : found;
  }

  /**
   * Partial or repeated name matches in three tiers. Strong: no tax id conflict (one side may lack
   * it), listed best first (exact name, then closest name) and the best one flagged probable. Weak:
   * both tax ids present and different, listed after, never probable. All weak or no rows: null (the caller decides, finally new).
   */
  private JSONObject tiered(List<Party> rows, String wanted, String cleanTaxId)
      throws JSONException {
    List<Cand> strong = new ArrayList<>();
    List<Cand> weak = new ArrayList<>();
    for (Party party : rows) {
      Cand cand = new Cand(party, lookups.vendorTaxId(party.id()));
      (conflicts(cleanTaxId, cand.taxId) ? weak : strong).add(cand);
    }
    if (strong.isEmpty()) {
      return null;
    }
    Comparator<Cand> byProximity = Comparator
        .comparingInt((Cand c) -> OcrProductMatch.normalize(c.party.name()).equals(wanted) ? 0 : 1)
        .thenComparingInt(c -> Math.abs(OcrProductMatch.normalize(c.party.name()).length()
            - wanted.length()));
    strong.sort(byProximity);
    weak.sort(byProximity);
    List<Cand> ordered = new ArrayList<>(strong);
    ordered.addAll(weak);
    // Probable only when the best strong candidate is strictly better than the next one.
    boolean unique = strong.size() == 1 || byProximity.compare(strong.get(0), strong.get(1)) < 0;
    return ambiguous(ordered, BY_NAME, unique);
  }

  private static boolean conflicts(String cleanTaxId, String storedTaxId) {
    String stored = TaxIdNormalizer.clean(storedTaxId);
    return !cleanTaxId.isEmpty() && !stored.isEmpty()
        && !TaxIdNormalizer.idsMatch(cleanTaxId, stored);
  }

  /** The one vendor of that exact name: taken unless both sides carry a tax id and they differ. */
  private JSONObject exactName(Party party, String cleanTaxId) throws JSONException {
    String stored = lookups.vendorTaxId(party.id());
    return conflicts(cleanTaxId, stored) ? ambiguous(withTaxIds(List.of(party)), BY_NAME, false)
        : linked(party, BY_NAME);
  }

  private static JSONObject result(String state) throws JSONException {
    return new JSONObject().put(K_STATE, state).put(K_CANDIDATES, new JSONArray());
  }

  private static JSONObject linked(Party party, String matchedBy) throws JSONException {
    return result(S_LINKED).put(K_MATCHED_BY, matchedBy).put(K_ID, party.id())
        .put(K_NAME, party.name());
  }

  private List<Cand> withTaxIds(List<Party> parties) {
    return parties.stream().map(p -> new Cand(p, lookups.vendorTaxId(p.id())))
        .collect(Collectors.toList());
  }

  /**
   * Candidates in the given order. Every candidate carries {@code probable} explicitly: true on the
   * first only when {@code flagFirst} (the best is uniquely best), false on all others (the key is
   * absent only on legacy documents).
   */
  private static JSONObject ambiguous(List<Cand> cands, String matchedBy, boolean flagFirst)
      throws JSONException {
    JSONArray candidates = new JSONArray();
    List<Cand> kept = cands.subList(0, Math.min(cands.size(), CANDIDATE_LIMIT));
    for (int i = 0; i < kept.size(); i++) {
      Cand cand = kept.get(i);
      JSONObject json = new JSONObject().put(K_ID, cand.party.id()).put(K_NAME, cand.party.name())
          .put(K_TAX_ID, cand.taxId);
      json.put(K_PROBABLE, flagFirst && i == 0);
      candidates.put(json);
    }
    return result(S_AMBIGUOUS).put(K_MATCHED_BY, matchedBy).put(K_CANDIDATES, candidates);
  }

  /** A vendor with its stored tax id, read once. */
  private static final class Cand {
    private final Party party;
    private final String taxId;

    Cand(Party party, String taxId) {
      this.party = party;
      this.taxId = taxId;
    }
  }
}
