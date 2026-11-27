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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.codehaus.jettison.json.JSONObject;
import org.junit.jupiter.api.Test;

/**
 * The form state an update re-fires its callouts with.
 *
 * @covers com.etendoerp.go.schemaforge.NeoUpdateCalloutRefire
 */
class NeoUpdateCalloutRefireTest {

  /**
   * ETP-6107: an order line stores no business partner nor address of its own. Carried as empty
   * keys they hid the header's from the product callout, which then picked a tax without the
   * customer's address. Dropping them lets the callout request take the header's, as on a create.
   */
  @Test
  void testEmptyStoredColumnsAreDroppedSoTheParentFillsThem() throws Exception {
    JSONObject stored = new JSONObject()
        .put("businessPartner", JSONObject.NULL)
        .put("partnerAddress", JSONObject.NULL)
        .put("description", "");

    NeoUpdateCalloutRefire.withoutBlankValues(stored);

    assertFalse(stored.has("businessPartner"));
    assertFalse(stored.has("partnerAddress"));
    assertFalse(stored.has("description"));
  }

  @Test
  void testStoredValuesAreKept() throws Exception {
    JSONObject stored = new JSONObject()
        .put("product", "P1")
        .put("orderedQuantity", 2)
        .put("discount", 0)
        .put("directShip", false);

    JSONObject result = NeoUpdateCalloutRefire.withoutBlankValues(stored);

    assertSame(stored, result);
    assertEquals(4, result.length());
    assertEquals("P1", result.get("product"));
    assertEquals(0, result.get("discount"));
    assertTrue(result.has("directShip"));
  }
}
