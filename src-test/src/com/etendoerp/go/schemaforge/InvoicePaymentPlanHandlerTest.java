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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;

import javax.inject.Named;

import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONObject;
import org.hibernate.Session;
import org.hibernate.query.NativeQuery;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBDal;

/**
 * Unit tests for {@link InvoicePaymentPlanHandler} and its two bindings: every installment row of
 * an invoice's {@code paymentPlan} gains {@code payableAmount} (free details) and
 * {@code remittedAmount} (details linked to a {@code REM_SENT} payment), from one batched native
 * query; a failure leaves the rows as they were.
 *
 * <p>A class of its own: the handler is new and no existing test class covers the payment plan
 * ({@code PaymentScheduleDetailHandlerTest} is about the payment's lines). The SQL itself needs a
 * database; here the query is mocked and the handler's contract is pinned given its rows.
 *
 * @covers com.etendoerp.go.schemaforge.InvoicePaymentPlanHandler
 * @covers com.etendoerp.go.schemaforge.SalesInvoicePaymentPlanHandler
 * @covers com.etendoerp.go.schemaforge.PurchaseInvoicePaymentPlanHandler
 */
class InvoicePaymentPlanHandlerTest {

  private final InvoicePaymentPlanHandler handler = new SalesInvoicePaymentPlanHandler();

  private MockedStatic<OBDal> dalStatic;
  private MockedStatic<OBContext> contextStatic;
  private Session session;
  private NativeQuery<Object[]> query;

  @BeforeEach
  @SuppressWarnings("unchecked")
  void setUp() {
    OBDal dal = mock(OBDal.class);
    session = mock(Session.class);
    query = mock(NativeQuery.class);
    when(dal.getSession()).thenReturn(session);
    when(session.createNativeQuery(anyString())).thenReturn((NativeQuery) query);
    dalStatic = mockStatic(OBDal.class);
    dalStatic.when(OBDal::getInstance).thenReturn(dal);
    contextStatic = mockStatic(OBContext.class);
  }

  @AfterEach
  void tearDown() {
    contextStatic.close();
    dalStatic.close();
  }

  private static NeoContext ctx(String method, NeoResponse previous) {
    NeoContext context = NeoContext.builder()
        .specName("sales-invoice")
        .entityName("paymentPlan")
        .httpMethod(method)
        .endpointType(NeoEndpointType.CRUD)
        .recordId("inv-1")
        .build();
    context.setPreviousResult(previous);
    return context;
  }

  private static NeoResponse page(JSONObject... rows) throws Exception {
    JSONArray data = new JSONArray();
    for (JSONObject row : rows) {
      data.put(row);
    }
    return NeoResponse.ok(new JSONObject().put("response", new JSONObject().put("data", data)));
  }

  private static JSONObject row(String id) throws Exception {
    return new JSONObject().put("id", id).put("outstandingAmount", 100);
  }

  private static JSONArray rows(NeoResponse response) throws Exception {
    return response.getBody().getJSONObject("response").getJSONArray("data");
  }

  private void queryReturns(Object[]... sqlRows) {
    List<Object[]> list = new ArrayList<>(List.of(sqlRows));
    when(query.list()).thenReturn(list);
  }

  private static void assertAmount(String expected, JSONObject row, String key) throws Exception {
    assertEquals(0, new BigDecimal(expected).compareTo(new BigDecimal(row.get(key).toString())),
        key + " of " + row);
  }

  @Test
  void handleIsAPassThrough() {
    assertNull(handler.handle(ctx("GET", null)));
  }

  @Test
  void afterHandleAddsPayableAndRemittedAmountsPerInstallment() throws Exception {
    queryReturns(
        new Object[] { "ps-1", new BigDecimal("40.00"), new BigDecimal("60.00") },
        new Object[] { "ps-2", new BigDecimal("50.00"), BigDecimal.ZERO });
    NeoResponse previous = page(row("ps-1"), row("ps-2"));

    NeoResponse result = handler.afterHandle(ctx("GET", previous));

    assertNotNull(result);
    JSONArray data = rows(result);
    assertAmount("40.00", data.getJSONObject(0), InvoicePaymentPlanHandler.KEY_PAYABLE);
    assertAmount("60.00", data.getJSONObject(0), InvoicePaymentPlanHandler.KEY_REMITTED);
    assertAmount("50.00", data.getJSONObject(1), InvoicePaymentPlanHandler.KEY_PAYABLE);
    assertAmount("0", data.getJSONObject(1), InvoicePaymentPlanHandler.KEY_REMITTED);
    // The outstanding is left alone: the remitted part is still owed.
    assertEquals(100, data.getJSONObject(0).getInt("outstandingAmount"));
    verify(query).setParameter("remitted", InvoiceRemittance.STATUS_REMITTED);
    verify(query).setParameterList("ids", List.of("ps-1", "ps-2"));
    contextStatic.verify(() -> OBContext.setAdminMode(true));
    contextStatic.verify(OBContext::restorePreviousMode);
  }

  @Test
  void afterHandleConvertsNonDecimalAndNullSqlValues() throws Exception {
    queryReturns(new Object[] { "ps-1", BigInteger.valueOf(7), null });
    NeoResponse previous = page(row("ps-1"));

    JSONObject enriched = rows(handler.afterHandle(ctx("GET", previous))).getJSONObject(0);

    assertAmount("7", enriched, InvoicePaymentPlanHandler.KEY_PAYABLE);
    assertAmount("0", enriched, InvoicePaymentPlanHandler.KEY_REMITTED);
  }

  @Test
  void afterHandleLeavesARowWithoutSqlResultUntouched() throws Exception {
    queryReturns(new Object[] { "ps-1", new BigDecimal("10"), new BigDecimal("0") });
    NeoResponse previous = page(row("ps-1"), row("ps-unknown"));

    JSONObject untouched = rows(handler.afterHandle(ctx("GET", previous))).getJSONObject(1);

    assertFalse(untouched.has(InvoicePaymentPlanHandler.KEY_PAYABLE));
    assertFalse(untouched.has(InvoicePaymentPlanHandler.KEY_REMITTED));
  }

  @Test
  void afterHandleKeepsTheDefaultWithoutRowsIdsOrOnAWrite() throws Exception {
    assertNull(handler.afterHandle(ctx("GET", null)));
    assertNull(handler.afterHandle(ctx("GET", page())));
    assertNull(handler.afterHandle(ctx("GET", page(new JSONObject().put("amount", 1)))));
    assertNull(handler.afterHandle(ctx("POST", page(row("ps-1")))));
    verify(session, never()).createNativeQuery(anyString());
  }

  /** The failure path: logged, the rows go back without the two keys (read as nothing remitted). */
  @Test
  void afterHandleReturnsThePreviousResultWhenTheQueryFails() throws Exception {
    when(query.list()).thenThrow(new IllegalStateException("db down"));
    NeoResponse previous = page(row("ps-1"));

    NeoResponse result = handler.afterHandle(ctx("GET", previous));

    assertSame(previous, result);
    JSONObject row = rows(result).getJSONObject(0);
    assertFalse(row.has(InvoicePaymentPlanHandler.KEY_PAYABLE));
    assertFalse(row.has(InvoicePaymentPlanHandler.KEY_REMITTED));
    contextStatic.verify(OBContext::restorePreviousMode);
  }

  @Test
  void bindingsTargetTheSalesAndPurchaseInvoicePaymentPlansWithoutNamed() {
    NeoExtension sales = SalesInvoicePaymentPlanHandler.class.getAnnotation(NeoExtension.class);
    NeoExtension purchase =
        PurchaseInvoicePaymentPlanHandler.class.getAnnotation(NeoExtension.class);

    assertEquals("sales-invoice", sales.spec());
    assertEquals("paymentPlan", sales.entity());
    assertEquals("purchase-invoice", purchase.spec());
    assertEquals("paymentPlan", purchase.entity());
    assertNull(SalesInvoicePaymentPlanHandler.class.getAnnotation(Named.class));
    assertNull(PurchaseInvoicePaymentPlanHandler.class.getAnnotation(Named.class));
    assertTrue(new PurchaseInvoicePaymentPlanHandler() instanceof InvoicePaymentPlanHandler);
  }
}
