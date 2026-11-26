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

package com.etendoerp.go.schemaforge.selector.policy;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONObject;
import org.hibernate.Session;
import org.hibernate.query.NativeQuery;
import org.junit.Test;
import org.mockito.MockedStatic;
import org.openbravo.dal.service.OBDal;

import com.etendoerp.go.schemaforge.NeoResponse;

/**
 * Unit tests for {@link ProductPriceSelectorPolicy}.
 *
 * @covers com.etendoerp.go.schemaforge.selector.policy.ProductPriceSelectorPolicy
 */
public class ProductPriceSelectorPolicyTest {

  /**
   * ETP-5703 QA: with a price list in context, the product selector showed the context list's
   * standard/list price but `_aux._PLIM` of whichever price-list version row the selector kept (a
   * purchase list's 0.90 instead of the sales list's 1.50). All three aux prices must come from
   * the context price list, and so must the item-level {@code priceLimit} grid value.
   */
  @Test
  @SuppressWarnings({ "unchecked", "rawtypes" })
  public void enrich_withContextPriceList_setsAllAuxPricesFromIt() throws Exception {
    JSONObject aux = new JSONObject().put("_PSTD", "0.90").put("_PLIST", "0.90")
        .put("_PLIM", "0.90");
    JSONObject item = new JSONObject().put("id", "agua").put("priceLimit", new BigDecimal("0.90"))
        .put("_aux", aux);
    NeoResponse response = NeoResponse.ok(new JSONObject().put("items", new JSONArray().put(item)));

    Object[] salesRow = { "agua", new BigDecimal("1.50"), new BigDecimal("1.60"), "N",
        new BigDecimal("1.45") };
    List<Object> rows = Collections.singletonList(salesRow);
    NativeQuery query = mock(NativeQuery.class);
    when(query.list()).thenReturn(rows);
    Session session = mock(Session.class);
    when(session.createNativeQuery(anyString())).thenReturn(query);
    OBDal dal = mock(OBDal.class);
    when(dal.getSession()).thenReturn(session);

    try (MockedStatic<OBDal> dalMock = mockStatic(OBDal.class)) {
      dalMock.when(OBDal::getInstance).thenReturn(dal);
      ProductPriceSelectorPolicy.enrichProductSelectorWithPrices(response, "sales-list");
    }

    JSONObject enriched = response.getBody().getJSONArray("items").getJSONObject(0);
    JSONObject enrichedAux = enriched.getJSONObject("_aux");
    assertEquals(0, new BigDecimal("1.45").compareTo(new BigDecimal(
        String.valueOf(enriched.get("priceLimit")))));
    assertEquals("1.50", enrichedAux.getString("_PSTD"));
    assertEquals("1.60", enrichedAux.getString("_PLIST"));
    assertEquals("1.45", enrichedAux.getString("_PLIM"));
  }

  @SuppressWarnings({ "unchecked", "rawtypes" })
  private static Optional<BigDecimal> standardPriceOfRows(List<Object> rows, String... ids) {
    NativeQuery query = mock(NativeQuery.class);
    when(query.list()).thenReturn(rows);
    Session session = mock(Session.class);
    when(session.createNativeQuery(anyString())).thenReturn(query);
    OBDal dal = mock(OBDal.class);
    when(dal.getSession()).thenReturn(session);
    try (MockedStatic<OBDal> dalMock = mockStatic(OBDal.class)) {
      dalMock.when(OBDal::getInstance).thenReturn(dal);
      return ProductPriceSelectorPolicy.standardPrice(ids[0], ids[1]);
    }
  }

  /** ETP-6051: the standard price is the column the selector shows, 0 when the row carries none. */
  @Test
  public void standardPrice_isThePstdOfTheRowOfTheProduct() {
    Object[] row = { "P1", new BigDecimal("4.25"), new BigDecimal("5"), "N", new BigDecimal("3") };

    assertEquals(0, new BigDecimal("4.25").compareTo(
        standardPriceOfRows(Collections.singletonList(row), "PL1", "P1").orElseThrow()));
  }

  @Test
  public void standardPrice_ofARowWithoutPriceIsZeroAndOfAnAbsentProductIsEmpty() {
    Object[] nullPrice = { "P1", null, null, "N", null };

    assertEquals(0, BigDecimal.ZERO.compareTo(
        standardPriceOfRows(Collections.singletonList(nullPrice), "PL1", "P1").orElseThrow()));
    assertTrue(standardPriceOfRows(Collections.emptyList(), "PL1", "P1").isEmpty());
    assertTrue(standardPriceOfRows(Collections.singletonList(nullPrice), "PL1", "OTHER").isEmpty());
  }

  @Test
  public void standardPrice_withABlankArgumentIsEmptyAndNeverQueries() {
    try (MockedStatic<OBDal> dalMock = mockStatic(OBDal.class)) {
      assertTrue(ProductPriceSelectorPolicy.standardPrice(null, "P1").isEmpty());
      assertTrue(ProductPriceSelectorPolicy.standardPrice(" ", "P1").isEmpty());
      assertTrue(ProductPriceSelectorPolicy.standardPrice("PL1", "").isEmpty());
      dalMock.verifyNoInteractions();
    }
  }
}
