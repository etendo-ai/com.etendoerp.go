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

  /**
   * ETP-6107: after a new product, the prices the caller sent with it fire their callouts too, as
   * when the user types the price after picking the product. Only sent, non-reference fields with
   * a callout qualify, in the order sent.
   */
  @Test
  void testSentValuesWithACalloutFollowTheReference() throws Exception {
    org.openbravo.base.model.Entity entity = org.mockito.Mockito.mock(
        org.openbravo.base.model.Entity.class);
    org.openbravo.base.model.Property primitive = org.mockito.Mockito.mock(
        org.openbravo.base.model.Property.class);
    org.mockito.Mockito.when(primitive.isPrimitive()).thenReturn(true);
    org.openbravo.base.model.Property reference = org.mockito.Mockito.mock(
        org.openbravo.base.model.Property.class);
    org.mockito.Mockito.when(entity.getProperty("unitPrice", false)).thenReturn(primitive);
    org.mockito.Mockito.when(entity.getProperty("description", false)).thenReturn(primitive);
    org.mockito.Mockito.when(entity.getProperty("listPrice", false)).thenReturn(primitive);
    org.mockito.Mockito.when(entity.getProperty("product", false)).thenReturn(reference);
    org.openbravo.model.ad.ui.Tab tab = org.mockito.Mockito.mock(org.openbravo.model.ad.ui.Tab.class);
    JSONObject body = new JSONObject().put("product", "NEW").put("unitPrice", 40)
        .put("description", "x").put("listPrice", 50);
    java.util.Set<String> sent = new java.util.HashSet<>(
        java.util.Arrays.asList("product", "unitPrice", "description"));

    try (org.mockito.MockedStatic<NeoCalloutService> callouts =
        org.mockito.Mockito.mockStatic(NeoCalloutService.class)) {
      NeoCalloutService.CalloutInfo info = org.mockito.Mockito.mock(
          NeoCalloutService.CalloutInfo.class);
      callouts.when(() -> NeoCalloutService.resolveCallout(tab, "unitPrice")).thenReturn(info);
      callouts.when(() -> NeoCalloutService.resolveCallout(tab, "listPrice")).thenReturn(info);
      callouts.when(() -> NeoCalloutService.resolveCallout(tab, "product")).thenReturn(info);

      // listPrice came from the product's callout, not from the caller: it does not qualify.
      assertEquals(java.util.Collections.singletonList("unitPrice"),
          NeoUpdateCalloutRefire.sentValuesWithCallout(entity, body, sent, tab));
    }
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
