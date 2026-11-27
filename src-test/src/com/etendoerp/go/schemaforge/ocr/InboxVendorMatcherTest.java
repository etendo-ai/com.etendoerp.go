/*
 *************************************************************************
 * The contents of this file are subject to the Etendo License
 * (the "License"), you may not use this file except in compliance
 * with the License.
 * You may obtain a copy of the License at
 * https://github.com/etendosoftware/etendo_core/blob/main/legal/Etendo_license.txt
 * Software distributed under the License is distributed on an
 * "AS IS" basis, WITHOUT WARRANTY OF ANY KIND, either express or
 * implied. See the License for the specific language governing rights
 * and limitations under the License.
 * All portions are Copyright (C) 2021-2026 FUTIT SERVICES, S.L
 * All Rights Reserved.
 * Contributor(s): Futit Services S.L.
 *************************************************************************
 */

package com.etendoerp.go.schemaforge.ocr;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONObject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.etendoerp.go.schemaforge.ocr.OcrLookups.Party;

/**
 * How the inbox finds the vendor of a document with nobody to ask: by tax id (with or without its
 * VAT prefix) first, then by an exact name that no tax id contradicts. A name that only contains the
 * text never links, even with one hit.
 *
 * @covers com.etendoerp.go.schemaforge.ocr.InboxVendorMatcher
 */
class InboxVendorMatcherTest {

  private static final String STATE = "state";
  private static final String MATCHED_BY = "matchedBy";
  private static final String CANDIDATES = "candidates";
  private static final String TAX_ID = "B81639719";
  private static final Party ACME = new Party("V1", "Acme S.L.");
  private static final Party ACME_TWIN = new Party("V2", "Acme S.L.");

  private InboxFakeLookups lookups;
  private InboxVendorMatcher matcher;

  @BeforeEach
  void setUp() {
    lookups = new InboxFakeLookups();
    matcher = new InboxVendorMatcher(lookups);
  }

  // ---------------------------------------------------------------- by tax id

  @Test
  @DisplayName("one vendor with the tax id is the match, linked by tax id, without asking by name")
  void oneVendorByTaxIdIsLinked() throws Exception {
    lookups.byTaxId.put(TAX_ID, List.of(ACME));

    JSONObject vendor = matcher.match("Another Name", TAX_ID);

    assertEquals("linked", vendor.getString(STATE));
    assertEquals("taxId", vendor.getString(MATCHED_BY));
    assertEquals("V1", vendor.getString("id"));
    assertEquals("Acme S.L.", vendor.getString("name"));
    assertEquals(0, vendor.getJSONArray(CANDIDATES).length());
    assertTrue(lookups.nameSearches.isEmpty(), "the tax id settled it");
  }

  @Test
  @DisplayName("the id is searched cleaned (upper case, no separators), prefix included as read")
  void theTaxIdIsSearchedClean() throws Exception {
    lookups.byTaxId.put("ESB81639719", List.of(ACME));

    JSONObject vendor = matcher.match("Acme", " es-b.816 39719 ");

    assertEquals(List.of("ESB81639719"), lookups.taxIdSearches);
    assertEquals("V1", vendor.getString("id"));
  }

  @Test
  @DisplayName("two vendors with the same tax id are ambiguous, with both as candidates and their tax ids")
  void twoVendorsWithTheSameTaxIdAreAmbiguous() throws Exception {
    lookups.byTaxId.put(TAX_ID, List.of(ACME, new Party("V2", "Acme Logistics")));
    lookups.storedTaxIds.put("V1", TAX_ID);
    lookups.storedTaxIds.put("V2", "ES" + TAX_ID);

    JSONObject vendor = matcher.match("Acme", TAX_ID);

    assertEquals("ambiguous", vendor.getString(STATE));
    assertEquals("taxId", vendor.getString(MATCHED_BY));
    assertFalse(vendor.has("id"), "no vendor is chosen");
    JSONArray candidates = vendor.getJSONArray(CANDIDATES);
    assertEquals(2, candidates.length());
    assertEquals("V1", candidates.getJSONObject(0).getString("id"));
    assertEquals(TAX_ID, candidates.getJSONObject(0).getString("taxId"));
    assertEquals("ES" + TAX_ID, candidates.getJSONObject(1).getString("taxId"));
  }

  @Test
  @DisplayName("no more than ten candidates are kept")
  void candidatesAreCapped() throws Exception {
    List<Party> many = new ArrayList<>();
    for (int i = 0; i < 14; i++) {
      many.add(new Party("V" + i, "Vendor " + i));
    }
    lookups.byTaxId.put(TAX_ID, many);

    assertEquals(10, matcher.match("x", TAX_ID).getJSONArray(CANDIDATES).length());
  }

  @Test
  @DisplayName("a tax id shorter than five characters is not searched; the name decides")
  void aShortTaxIdIsNotSearched() throws Exception {
    lookups.byName = List.of(ACME);

    JSONObject vendor = matcher.match("Acme S.L.", "AB-1");

    assertTrue(lookups.taxIdSearches.isEmpty());
    assertEquals("linked", vendor.getString(STATE));
    assertEquals("name", vendor.getString(MATCHED_BY));
  }

  // ---------------------------------------------------------------- by name

  @Test
  @DisplayName("a tax id nobody holds falls back to the name: one exact vendor, no stored tax id, is linked")
  void exactNameWithoutAStoredTaxIdIsLinked() throws Exception {
    lookups.byName = List.of(ACME);

    JSONObject vendor = matcher.match("  ACME s.l.  ", TAX_ID);

    assertEquals(List.of(TAX_ID), lookups.taxIdSearches);
    assertEquals("linked", vendor.getString(STATE));
    assertEquals("name", vendor.getString(MATCHED_BY));
    assertEquals("V1", vendor.getString("id"));
  }

  @Test
  @DisplayName("the document without a tax id links an exact name whatever the vendor stores")
  void exactNameWithNoDocumentTaxIdIsLinked() throws Exception {
    lookups.byName = List.of(ACME);
    lookups.storedTaxIds.put("V1", TAX_ID);

    JSONObject vendor = matcher.match("Acme S.L.", "");

    assertEquals("linked", vendor.getString(STATE));
    assertTrue(lookups.taxIdSearches.isEmpty());
  }

  @Test
  @DisplayName("an exact name whose stored tax id is the document's own, spelled differently, is linked")
  void exactNameWithTheSameTaxIdIsLinked() throws Exception {
    lookups.byName = List.of(ACME);
    lookups.storedTaxIds.put("V1", "ES-" + TAX_ID);

    assertEquals("linked", matcher.match("Acme S.L.", TAX_ID).getString(STATE));
  }

  @Test
  @DisplayName("an exact name with a different tax id on each side is another company: ambiguous")
  void exactNameWithAConflictingTaxIdIsAmbiguous() throws Exception {
    lookups.byName = List.of(ACME);
    lookups.storedTaxIds.put("V1", "A28000727");

    JSONObject vendor = matcher.match("Acme S.L.", TAX_ID);

    assertEquals("ambiguous", vendor.getString(STATE));
    assertEquals("name", vendor.getString(MATCHED_BY));
    assertFalse(vendor.has("id"));
    assertEquals(1, vendor.getJSONArray(CANDIDATES).length());
    assertEquals("A28000727", vendor.getJSONArray(CANDIDATES).getJSONObject(0).getString("taxId"));
    assertTrue(vendor.getJSONArray(CANDIDATES).getJSONObject(0).has("probable"));
    assertFalse(vendor.getJSONArray(CANDIDATES).getJSONObject(0).getBoolean("probable"));
  }

  @Test
  @DisplayName("every candidate carries probable explicitly: true on the best strong one only")
  void probableIsExplicitOnEveryCandidate() throws Exception {
    Party weak = new Party("V4", "Acme S.L. Weak");
    lookups.byName = List.of(weak, new Party("V3", "Acme S.L. Logistics"));
    lookups.storedTaxIds.put("V4", "A28000727");

    JSONArray candidates = matcher.match("Acme", TAX_ID).getJSONArray(CANDIDATES);

    assertEquals("V3", candidates.getJSONObject(0).getString("id"));
    assertTrue(candidates.getJSONObject(0).getBoolean("probable"));
    assertEquals("V4", candidates.getJSONObject(1).getString("id"));
    assertFalse(candidates.getJSONObject(1).getBoolean("probable"));
  }

  @Test
  @DisplayName("all exact names contradict the tax id: strong partial rows still win over new")
  void strongPartialRowsBeatAnAllWeakExactSet() throws Exception {
    lookups.byName = List.of(ACME, ACME_TWIN, new Party("V3", "Acme S.L. Logistics"));
    lookups.storedTaxIds.put("V1", "A28000727");
    lookups.storedTaxIds.put("V2", "A28000728");

    JSONObject vendor = matcher.match("Acme S.L.", TAX_ID);

    assertEquals("ambiguous", vendor.getString(STATE));
    assertEquals("V3", vendor.getJSONArray(CANDIDATES).getJSONObject(0).getString("id"));
    assertTrue(vendor.getJSONArray(CANDIDATES).getJSONObject(0).getBoolean("probable"));
  }

  @Test
  @DisplayName("every partial hit contradicts the tax id: new, no candidates")
  void allWeakPartialRowsAreNew() throws Exception {
    lookups.byName = List.of(new Party("V3", "Acme S.L. Logistics"));
    lookups.storedTaxIds.put("V3", "A28000727");

    JSONObject vendor = matcher.match("Acme", TAX_ID);

    assertEquals("new", vendor.getString(STATE));
    assertEquals(0, vendor.getJSONArray(CANDIDATES).length());
  }

  @Test
  @DisplayName("two vendors with the exact name are ambiguous, and only they are the candidates")
  void twoExactNamesAreAmbiguous() throws Exception {
    lookups.byName = List.of(ACME, ACME_TWIN, new Party("V3", "Acme S.L. Logistics"));

    JSONObject vendor = matcher.match("Acme S.L.", "");

    assertEquals("ambiguous", vendor.getString(STATE));
    assertEquals(2, vendor.getJSONArray(CANDIDATES).length());
  }

  @Test
  @DisplayName("a name that only contains the text is ambiguous even with a single hit: never linked")
  void aContainsHitNeverLinks() throws Exception {
    lookups.byName = List.of(new Party("V3", "Acme S.L. Logistics"));

    JSONObject vendor = matcher.match("Acme", "");

    assertEquals("ambiguous", vendor.getString(STATE));
    assertEquals("name", vendor.getString(MATCHED_BY));
    assertEquals(1, vendor.getJSONArray(CANDIDATES).length());
    assertFalse(vendor.has("id"));
  }

  @Test
  @DisplayName("an exact name among other hits is still the only exact one: linked")
  void oneExactAmongSeveralHitsIsLinked() throws Exception {
    lookups.byName = List.of(new Party("V3", "Acme S.L. Logistics"), ACME);

    JSONObject vendor = matcher.match("Acme S.L.", "");

    assertEquals("linked", vendor.getString(STATE));
    assertEquals("V1", vendor.getString("id"));
  }

  @Test
  @DisplayName("a named vendor nobody knows is new, with no candidates")
  void aNamedUnknownVendorIsNew() throws Exception {
    JSONObject vendor = matcher.match("Brand New Ltd", TAX_ID);

    assertEquals("new", vendor.getString(STATE));
    assertEquals(0, vendor.getJSONArray(CANDIDATES).length());
    assertFalse(vendor.has("id"));
  }

  @Test
  @DisplayName("neither a name nor a tax id read: none, and nothing is searched by name")
  void nothingReadIsNone() throws Exception {
    for (String blank : new String[] { null, "", "   " }) {
      JSONObject vendor = matcher.match(blank, "");

      assertEquals("none", vendor.getString(STATE));
    }
    assertTrue(lookups.nameSearches.isEmpty());
  }

  @Test
  @DisplayName("a tax id with no name that nobody holds is none: there is nothing to create a vendor from")
  void aTaxIdAloneThatMatchesNothingIsNone() throws Exception {
    assertEquals("none", matcher.match("", TAX_ID).getString(STATE));
  }

  // ---------------------------------------------------------------- tiers, order and cap (ETP-6051)

  private static List<String> ids(JSONArray candidates) throws Exception {
    List<String> out = new ArrayList<>();
    for (int i = 0; i < candidates.length(); i++) {
      out.add(candidates.getJSONObject(i).getString("id"));
    }
    return out;
  }

  @Test
  @DisplayName("the stored tax id with a VAT prefix, spaces or lower case is the printed one: exact name linked")
  void storedTaxIdSpellingsAreTheSameCompany() throws Exception {
    for (String stored : new String[] { "es b81639719", "ES B-816 39719", "b81639719", "  eS-B81639719 " }) {
      lookups.byName = List.of(ACME);
      lookups.storedTaxIds.put("V1", stored);

      JSONObject vendor = matcher.match("Acme S.L.", "B 81639719");

      assertEquals("linked", vendor.getString(STATE), stored);
    }
  }

  @Test
  @DisplayName("a printed tax id with prefix, lower case and spaces finds the vendor stored under the cleaned id")
  void printedTaxIdSpellingsAreCleaned() throws Exception {
    lookups.byTaxId.put("ESB81639719", List.of(ACME));

    JSONObject vendor = matcher.match("", "es b 816-39719");

    assertEquals("linked", vendor.getString(STATE));
    assertEquals("taxId", vendor.getString(MATCHED_BY));
  }

  @Test
  @DisplayName("partial names, tax id missing on the printed side: ambiguous, closest first, probable only on it")
  void partialNameWithoutAPrintedTaxIdOrdersByProximity() throws Exception {
    Party far = new Party("V3", "Acme S.L. Logistics International");
    Party close = new Party("V4", "Acme S.L. Log");
    lookups.byName = List.of(far, close, new Party("V5", "Acme S.L."));
    lookups.storedTaxIds.put("V3", "A28000727");
    lookups.storedTaxIds.put("V4", "A28000728");

    JSONObject vendor = matcher.match("Acme S.L. Lo", "");

    assertEquals("ambiguous", vendor.getString(STATE));
    JSONArray candidates = vendor.getJSONArray(CANDIDATES);
    assertEquals(List.of("V4", "V5", "V3"), ids(candidates));
    assertTrue(candidates.getJSONObject(0).getBoolean("probable"));
    for (int i = 1; i < candidates.length(); i++) {
      assertFalse(candidates.getJSONObject(i).getBoolean("probable"));
    }
  }

  @Test
  @DisplayName("several equally exact names are listed alone and none is probable; partial rows are not mixed in")
  void severalExactNamesAreListedWithoutThePartialRows() throws Exception {
    lookups.byName = List.of(new Party("V3", "Acme S.L. Logistics"), ACME, ACME_TWIN);

    JSONArray candidates = matcher.match("Acme S.L.", "").getJSONArray(CANDIDATES);

    assertEquals(List.of("V1", "V2"), ids(candidates));
    assertFalse(candidates.getJSONObject(0).getBoolean("probable"));
    assertFalse(candidates.getJSONObject(1).getBoolean("probable"));
  }

  @Test
  @DisplayName("two equally close partial names tie: no candidate is probable")
  void tiedPartialNamesAreNeverProbable() throws Exception {
    lookups.byName = List.of(new Party("V3", "Acme S.L. Aa"), new Party("V4", "Acme S.L. Bb"));

    JSONArray candidates = matcher.match("Acme S.L.", "").getJSONArray(CANDIDATES);

    assertEquals(2, candidates.length());
    assertFalse(candidates.getJSONObject(0).getBoolean("probable"));
    assertFalse(candidates.getJSONObject(1).getBoolean("probable"));
  }

  @Test
  @DisplayName("two vendors with the same tax id: no candidate is probable")
  void sameTaxIdVendorsAreNeverProbable() throws Exception {
    lookups.byTaxId.put(TAX_ID, List.of(ACME, new Party("V2", "Other")));

    JSONArray candidates = matcher.match("x", TAX_ID).getJSONArray(CANDIDATES);

    assertFalse(candidates.getJSONObject(0).getBoolean("probable"));
    assertFalse(candidates.getJSONObject(1).getBoolean("probable"));
  }

  @Test
  @DisplayName("a strong candidate among weak ones is probable: weak never competes in a tie")
  void strongAmongWeakIsProbable() throws Exception {
    lookups.byName = List.of(new Party("W1", "Acme S.L. Aa"), new Party("S1", "Acme S.L. Zzzzzz"),
        new Party("W2", "Acme S.L. Bb"));
    lookups.storedTaxIds.put("W1", "A28000727");
    lookups.storedTaxIds.put("W2", "A28000728");

    JSONArray candidates = matcher.match("Acme S.L.", TAX_ID).getJSONArray(CANDIDATES);

    assertEquals("S1", candidates.getJSONObject(0).getString("id"));
    assertTrue(candidates.getJSONObject(0).getBoolean("probable"));
    assertFalse(candidates.getJSONObject(1).getBoolean("probable"));
    assertFalse(candidates.getJSONObject(2).getBoolean("probable"));
  }

  @Test
  @DisplayName("partial name, tax id missing on the stored side: not a conflict, ambiguous with probable on the best")
  void partialNameWithoutAStoredTaxIdIsStrong() throws Exception {
    lookups.byName = List.of(new Party("V3", "Acme S.L. Logistics"), new Party("V4", "Acme S.L. Log"));

    JSONObject vendor = matcher.match("Acme S.L.", TAX_ID);

    assertEquals("ambiguous", vendor.getString(STATE));
    JSONArray candidates = vendor.getJSONArray(CANDIDATES);
    assertEquals(List.of("V4", "V3"), ids(candidates));
    assertTrue(candidates.getJSONObject(0).getBoolean("probable"));
    assertFalse(candidates.getJSONObject(1).getBoolean("probable"));
  }

  @Test
  @DisplayName("mixed tiers: strong rows first (best flagged), weak rows after, never flagged, each tier ordered")
  void mixedTiersListStrongFirst() throws Exception {
    lookups.byName = List.of(new Party("W1", "Acme S.L. Weak Long Name"), new Party("S1", "Acme S.L. Strong Long"),
        new Party("W2", "Acme S.L. W"), new Party("S2", "Acme S.L. S"));
    lookups.storedTaxIds.put("W1", "A28000727");
    lookups.storedTaxIds.put("W2", "A28000728");
    lookups.storedTaxIds.put("S1", "ES" + TAX_ID);

    JSONObject vendor = matcher.match("Acme S.L.", TAX_ID);

    JSONArray candidates = vendor.getJSONArray(CANDIDATES);
    assertEquals(List.of("S2", "S1", "W2", "W1"), ids(candidates));
    assertTrue(candidates.getJSONObject(0).getBoolean("probable"));
    for (int i = 1; i < candidates.length(); i++) {
      assertFalse(candidates.getJSONObject(i).getBoolean("probable"), "candidate " + i);
    }
  }

  @Test
  @DisplayName("a missing tax id on either side never ends as new while some row has no conflict")
  void aMissingTaxIdNeverEndsAsNew() throws Exception {
    lookups.byName = List.of(new Party("V3", "Acme S.L. Logistics"));
    lookups.storedTaxIds.put("V3", "A28000727");
    assertEquals("ambiguous", matcher.match("Acme", "").getString(STATE));

    lookups.storedTaxIds.clear();
    assertEquals("ambiguous", matcher.match("Acme", TAX_ID).getString(STATE));
  }

  @Test
  @DisplayName("the cap of ten is applied after ordering: the best row, last in the search, is still kept first")
  void theCapKeepsTheBestAfterOrdering() throws Exception {
    List<Party> rows = new ArrayList<>();
    for (int i = 0; i < 14; i++) {
      rows.add(new Party("V" + i, "Acme S.L. Logistics Extra Long " + i));
    }
    rows.add(new Party("BEST", "Acme S.L. X"));
    lookups.byName = rows;

    JSONArray candidates = matcher.match("Acme S.L.", "").getJSONArray(CANDIDATES);

    assertEquals(10, candidates.length());
    assertEquals("BEST", candidates.getJSONObject(0).getString("id"));
    assertTrue(candidates.getJSONObject(0).getBoolean("probable"));
  }

  @Test
  @DisplayName("more than twenty search rows: the one without a tax id conflict beyond the first twenty is found")
  void aStrongRowBeyondTheTwentiethIsFound() throws Exception {
    List<Party> rows = new ArrayList<>();
    for (int i = 0; i < 30; i++) {
      Party party = new Party("W" + i, "Acme S.L. Weak " + i);
      rows.add(party);
      lookups.storedTaxIds.put(party.id(), "A28000727");
    }
    rows.add(new Party("STRONG", "Acme S.L. Strong"));
    lookups.byName = rows;

    JSONObject vendor = matcher.match("Acme S.L.", TAX_ID);

    assertEquals("ambiguous", vendor.getString(STATE));
    assertEquals("STRONG", vendor.getJSONArray(CANDIDATES).getJSONObject(0).getString("id"));
    assertEquals(List.of(100), lookups.nameSearchLimits, "the search is wide enough to reach it");
  }

  @Test
  @DisplayName("every candidate of every ambiguous result carries a boolean probable")
  void everyCandidateCarriesAnExplicitBooleanProbable() throws Exception {
    lookups.byTaxId.put(TAX_ID, List.of(ACME, new Party("V2", "Other")));
    assertAllBoolean(matcher.match("x", TAX_ID));

    lookups = new InboxFakeLookups();
    matcher = new InboxVendorMatcher(lookups);
    lookups.byName = List.of(ACME, ACME_TWIN);
    assertAllBoolean(matcher.match("Acme S.L.", ""));

    lookups.byName = List.of(ACME);
    lookups.storedTaxIds.put("V1", "A28000727");
    assertAllBoolean(matcher.match("Acme S.L.", TAX_ID));
  }

  private static void assertAllBoolean(JSONObject vendor) throws Exception {
    JSONArray candidates = vendor.getJSONArray(CANDIDATES);
    assertTrue(candidates.length() > 0);
    for (int i = 0; i < candidates.length(); i++) {
      assertTrue(candidates.getJSONObject(i).get("probable") instanceof Boolean);
    }
  }
}
