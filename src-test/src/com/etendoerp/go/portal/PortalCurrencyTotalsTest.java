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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.codehaus.jettison.json.JSONObject;
import org.hibernate.Session;
import org.hibernate.query.Query;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.MockedStatic;
import org.openbravo.dal.service.OBDal;

import com.etendoerp.go.schemaforge.NeoCurrencyTotals;

/**
 * Unit tests for {@link PortalCurrencyTotals#convert}: the portal's per-currency sums folded into
 * the company currency (ETP-6209 QA CP-47), never a total that silently leaves money out.
 *
 * <p>The converter is built on a fixed company currency and a rate table; the ISO-to-id lookup is
 * the only database read, answered here as {@code ID-<iso>}.
 *
 * @covers com.etendoerp.go.portal.PortalCurrencyTotals
 */
@SuppressWarnings({ "unchecked", "rawtypes" })
public class PortalCurrencyTotalsTest {

  private static final NeoCurrencyTotals.TargetCurrency EURO =
      new NeoCurrencyTotals.TargetCurrency("ID-EUR", "EUR", 2);

  private MockedStatic<OBDal> obDalStatic;
  /** Rates asked for, as {@code from>to}. */
  private final List<String> asked = new ArrayList<>();

  @Before
  public void setUp() {
    OBDal obDal = mock(OBDal.class);
    obDalStatic = mockStatic(OBDal.class);
    obDalStatic.when(OBDal::getInstance).thenReturn(obDal);
    Session session = mock(Session.class);
    when(obDal.getSession()).thenReturn(session);
    String[] iso = new String[1];
    Query query = mock(Query.class);
    when(query.setParameter(eq("iso"), anyString())).thenAnswer(inv -> {
      iso[0] = inv.getArgument(1);
      return query;
    });
    when(query.uniqueResult()).thenAnswer(inv -> "ID-" + iso[0]);
    when(session.createQuery(anyString(), eq(String.class))).thenReturn(query);
  }

  @After
  public void tearDown() {
    obDalStatic.close();
  }

  @Test
  public void theCompanyCurrencyAloneIsExactAndAsksForNoRate() throws Exception {
    JSONObject total = (JSONObject) converter(EURO, "0.9").convert(sums("EUR", "100"));

    assertEquals(new BigDecimal("100.00"), total.get(PortalCurrencyTotals.FIELD_AMOUNT));
    assertEquals("EUR", total.getString(PortalCurrencyTotals.FIELD_CURRENCY));
    assertEquals(false, total.getBoolean(PortalCurrencyTotals.FIELD_APPROXIMATE));
    assertTrue(asked.isEmpty());
  }

  @Test
  public void aForeignCurrencyIsConvertedAtTodaysRateAndMarksTheTotalApproximate()
      throws Exception {
    JSONObject total = (JSONObject) converter(EURO, "0.9")
        .convert(sums("EUR", "100", "USD", "50"));

    assertEquals(new BigDecimal("145.00"), total.get(PortalCurrencyTotals.FIELD_AMOUNT));
    assertEquals("EUR", total.getString(PortalCurrencyTotals.FIELD_CURRENCY));
    assertEquals(true, total.getBoolean(PortalCurrencyTotals.FIELD_APPROXIMATE));
    assertEquals(Collections.singletonList("ID-USD>ID-EUR"), asked);
  }

  /** Without a total that is the whole of the money, the page shows the per-currency figures. */
  @Test
  public void thereIsNoConvertedTotalWhenItWouldLeaveMoneyOutOrHasNothingToFold()
      throws Exception {
    // Nothing to total.
    assertSame(JSONObject.NULL, converter(EURO, "0.9").convert(new LinkedHashMap<>()));
    // No company currency anywhere: two currencies have no common total ...
    assertSame(JSONObject.NULL, converter(null, "0.9").convert(sums("EUR", "100", "USD", "50")));
    assertTrue("no rate is looked up without a company currency", asked.isEmpty());
    // A currency without a rate.
    assertSame(JSONObject.NULL, converter(EURO, null).convert(sums("EUR", "100", "USD", "50")));
    // ... while one is its own sum, never approximate.
    JSONObject single = (JSONObject) converter(null, "0.9").convert(sums("USD", "50"));
    assertEquals(0, new BigDecimal("50").compareTo(
        (BigDecimal) single.get(PortalCurrencyTotals.FIELD_AMOUNT)));
    assertEquals("USD", single.getString(PortalCurrencyTotals.FIELD_CURRENCY));
    assertEquals(false, single.getBoolean(PortalCurrencyTotals.FIELD_APPROXIMATE));
  }

  private PortalCurrencyTotals converter(NeoCurrencyTotals.TargetCurrency target, String rate) {
    return new PortalCurrencyTotals(target, (from, to) -> {
      asked.add(from + ">" + to);
      return rate == null ? null : new BigDecimal(rate);
    });
  }

  /** Sums keyed by ISO code, in the given order: {@code iso, amount, iso, amount, ...}. */
  private static Map<String, BigDecimal> sums(String... isoAndAmount) {
    Map<String, BigDecimal> sums = new LinkedHashMap<>();
    for (int i = 0; i < isoAndAmount.length; i += 2) {
      sums.put(isoAndAmount[i], new BigDecimal(isoAndAmount[i + 1]));
    }
    return sums;
  }
}
