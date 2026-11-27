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
    org.mockito.Mockito.when(entity.getProperty("orderedQuantity", false)).thenReturn(primitive);
    org.openbravo.model.ad.ui.Tab tab = org.mockito.Mockito.mock(org.openbravo.model.ad.ui.Tab.class);
    org.openbravo.base.structure.BaseOBObject stored = org.mockito.Mockito.mock(
        org.openbravo.base.structure.BaseOBObject.class);
    org.mockito.Mockito.when(stored.get("unitPrice")).thenReturn(new java.math.BigDecimal("0.83"));
    org.mockito.Mockito.when(stored.get("orderedQuantity")).thenReturn(new java.math.BigDecimal("2"));
    // description differs from the stored value, so only its missing callout keeps it out
    org.mockito.Mockito.when(stored.get("description")).thenReturn("old");
    JSONObject body = new JSONObject().put("product", "NEW").put("unitPrice", 40)
        .put("description", "x").put("listPrice", 50).put("orderedQuantity", 2)
        .put("ghost", "g");
    java.util.Set<String> sent = new java.util.HashSet<>(
        java.util.Arrays.asList("product", "unitPrice", "description", "orderedQuantity",
            "ghost"));

    try (org.mockito.MockedStatic<NeoCalloutService> callouts =
        org.mockito.Mockito.mockStatic(NeoCalloutService.class)) {
      NeoCalloutService.CalloutInfo info = org.mockito.Mockito.mock(
          NeoCalloutService.CalloutInfo.class);
      callouts.when(() -> NeoCalloutService.resolveCallout(tab, "unitPrice")).thenReturn(info);
      callouts.when(() -> NeoCalloutService.resolveCallout(tab, "listPrice")).thenReturn(info);
      callouts.when(() -> NeoCalloutService.resolveCallout(tab, "product")).thenReturn(info);
      callouts.when(() -> NeoCalloutService.resolveCallout(tab, "orderedQuantity"))
          .thenReturn(info);

      // listPrice came from the product's callout, not from the caller; orderedQuantity equals the
      // stored one (a pre-hook carried it); description has no callout; ghost has no Property
      // (entity.getProperty returns null): none qualifies.
      assertEquals(java.util.Collections.singletonList("unitPrice"),
          NeoUpdateCalloutRefire.sentValuesWithCallout(entity, body, sent, tab, stored));
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

  // ── apply: which callouts the update re-fires, and with what ──

  private static final String ENTITY_NAME = "OrderLine";
  private static final String LINE_ID = "L1";

  /**
   * Runs {@code apply} over a stored line whose product is OLD and whose unit price is 0.83, with a
   * callout on both product and unitPrice, and returns the trigger lists the cascade was run with,
   * in order.
   */
  private static java.util.List<java.util.Collection<String>> applyAndCaptureTriggers(
      JSONObject body) {
    org.openbravo.base.model.Entity entity = org.mockito.Mockito.mock(
        org.openbravo.base.model.Entity.class);
    org.openbravo.base.model.Property primitive = org.mockito.Mockito.mock(
        org.openbravo.base.model.Property.class);
    org.mockito.Mockito.when(primitive.isPrimitive()).thenReturn(true);
    org.openbravo.base.model.Property reference = org.mockito.Mockito.mock(
        org.openbravo.base.model.Property.class);
    org.mockito.Mockito.when(reference.getTargetEntity()).thenReturn(entity);
    org.mockito.Mockito.when(entity.getProperty("product", false)).thenReturn(reference);
    org.mockito.Mockito.when(entity.getProperty("unitPrice", false)).thenReturn(primitive);
    org.openbravo.base.structure.BaseOBObject oldProduct = org.mockito.Mockito.mock(
        org.openbravo.base.structure.BaseOBObject.class);
    org.mockito.Mockito.when(oldProduct.getId()).thenReturn("OLD");
    org.openbravo.base.structure.BaseOBObject stored = org.mockito.Mockito.mock(
        org.openbravo.base.structure.BaseOBObject.class);
    org.mockito.Mockito.when(stored.get("product")).thenReturn(oldProduct);
    org.mockito.Mockito.when(stored.get("unitPrice")).thenReturn(new java.math.BigDecimal("0.83"));
    org.openbravo.model.ad.ui.Tab tab = org.mockito.Mockito.mock(org.openbravo.model.ad.ui.Tab.class);
    NeoContext ctx = NeoContext.builder().specName("sales-order").entityName("lines")
        .httpMethod("PATCH").endpointType(NeoEndpointType.CRUD).recordId(LINE_ID).adTab(tab)
        .requestBody(body).build();

    org.openbravo.base.model.ModelProvider modelProvider = org.mockito.Mockito.mock(
        org.openbravo.base.model.ModelProvider.class);
    org.mockito.Mockito.when(modelProvider.getEntity(ENTITY_NAME, false)).thenReturn(entity);
    org.openbravo.dal.service.OBDal dal = org.mockito.Mockito.mock(
        org.openbravo.dal.service.OBDal.class);
    org.mockito.Mockito.when(dal.get(ENTITY_NAME, LINE_ID)).thenReturn(stored);
    org.openbravo.service.json.DataToJsonConverter converter = org.mockito.Mockito.mock(
        org.openbravo.service.json.DataToJsonConverter.class);
    org.mockito.Mockito.when(converter.toJsonObject(stored,
        org.openbravo.service.json.DataResolvingMode.FULL)).thenReturn(new JSONObject());
    org.openbravo.base.provider.OBProvider provider = org.mockito.Mockito.mock(
        org.openbravo.base.provider.OBProvider.class);
    org.mockito.Mockito.when(provider.get(org.openbravo.service.json.DataToJsonConverter.class))
        .thenReturn(converter);

    java.util.List<java.util.Collection<String>> triggers = new java.util.ArrayList<>();
    try (org.mockito.MockedStatic<org.openbravo.base.model.ModelProvider> models =
            org.mockito.Mockito.mockStatic(org.openbravo.base.model.ModelProvider.class);
        org.mockito.MockedStatic<org.openbravo.dal.service.OBDal> dals =
            org.mockito.Mockito.mockStatic(org.openbravo.dal.service.OBDal.class);
        org.mockito.MockedStatic<org.openbravo.base.provider.OBProvider> providers =
            org.mockito.Mockito.mockStatic(org.openbravo.base.provider.OBProvider.class);
        org.mockito.MockedStatic<NeoCalloutService> callouts =
            org.mockito.Mockito.mockStatic(NeoCalloutService.class);
        org.mockito.MockedStatic<NeoDefaultsCascadeHelper> cascade =
            org.mockito.Mockito.mockStatic(NeoDefaultsCascadeHelper.class)) {
      models.when(org.openbravo.base.model.ModelProvider::getInstance).thenReturn(modelProvider);
      dals.when(org.openbravo.dal.service.OBDal::getInstance).thenReturn(dal);
      providers.when(org.openbravo.base.provider.OBProvider::getInstance).thenReturn(provider);
      NeoCalloutService.CalloutInfo info = org.mockito.Mockito.mock(
          NeoCalloutService.CalloutInfo.class);
      callouts.when(() -> NeoCalloutService.resolveCallout(tab, "product")).thenReturn(info);
      callouts.when(() -> NeoCalloutService.resolveCallout(tab, "unitPrice")).thenReturn(info);
      cascade.when(() -> NeoDefaultsCascadeHelper.executeCalloutsForTriggerFields(
          org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
          org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
          org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
          .thenAnswer(invocation -> {
            java.util.Collection<String> fired = invocation.getArgument(4);
            triggers.add(new java.util.ArrayList<>(fired));
            return null;
          });

      NeoUpdateCalloutRefire.apply(ctx, body, ENTITY_NAME);
    }
    return triggers;
  }

  /**
   * ETP-6107: a price sent with a new product fires its own callout in a second run, with exactly
   * the changed sent primitives, after the reference's run.
   */
  @Test
  void testTheSentPriceFiresItsCalloutAfterTheReferences() throws Exception {
    JSONObject body = new JSONObject().put("product", "NEW").put("unitPrice", 40);

    java.util.List<java.util.Collection<String>> runs = applyAndCaptureTriggers(body);

    assertEquals(2, runs.size(), runs.toString());
    assertEquals(java.util.Collections.singletonList("product"), runs.get(0));
    assertEquals(java.util.Collections.singletonList("unitPrice"), runs.get(1));
  }

  /** ETP-6107: a price equal to the stored one is no change, so no second run. */
  @Test
  void testAPriceEqualToTheStoredOneFiresNoSecondRun() throws Exception {
    JSONObject body = new JSONObject().put("product", "NEW").put("unitPrice", 0.83);

    java.util.List<java.util.Collection<String>> runs = applyAndCaptureTriggers(body);

    assertEquals(1, runs.size(), runs.toString());
    assertEquals(java.util.Collections.singletonList("product"), runs.get(0));
  }

  /** ETP-6107: only the reference changed, so only its callout runs. */
  @Test
  void testOnlyTheReferenceChangedFiresOneRun() throws Exception {
    JSONObject body = new JSONObject().put("product", "NEW");

    java.util.List<java.util.Collection<String>> runs = applyAndCaptureTriggers(body);

    assertEquals(1, runs.size(), runs.toString());
    assertEquals(java.util.Collections.singletonList("product"), runs.get(0));
  }
}
