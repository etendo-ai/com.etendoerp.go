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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

import java.util.Date;

import org.codehaus.jettison.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.common.invoice.Invoice;

/**
 * Unit tests for {@link PurchaseInvoiceImportDua}: the header rule of the "Importación con DUA"
 * check — DUA and DUA date required unless Multiple DUA, independent of the SII invoice type — and
 * the flag gate on turning the check on.
 *
 * @covers com.etendoerp.go.schemaforge.PurchaseInvoiceImportDua
 */
public class PurchaseInvoiceImportDuaTest {

  private static final String INVOICE_ID = "pinv-import";

  private MockedStatic<OBDal> obDal;
  private MockedStatic<SiiImportDuaFlag> flag;
  private Invoice stored;

  @Before
  public void setUp() {
    flag = mockStatic(SiiImportDuaFlag.class, Mockito.CALLS_REAL_METHODS);
    flag.when(SiiImportDuaFlag::isEnabled).thenReturn(true);
    obDal = mockStatic(OBDal.class);
    OBDal dal = mock(OBDal.class);
    obDal.when(OBDal::getInstance).thenReturn(dal);
    stored = mock(Invoice.class);
    when(dal.get(Invoice.class, INVOICE_ID)).thenReturn(stored);
    when(stored.isAeatsiiMultiDua()).thenReturn(false);
  }

  @After
  public void tearDown() {
    obDal.close();
    flag.close();
  }

  private static NeoContext crud(String method, String recordId, JSONObject body) {
    return NeoContext.builder().specName("purchase-invoice").entityName("header")
        .httpMethod(method).endpointType(NeoEndpointType.CRUD).recordId(recordId)
        .requestBody(body).build();
  }

  private static void assertRefused(NeoResponse response, int status) throws Exception {
    assertEquals(status, response.getHttpStatus());
  }

  @Test
  public void turningTheCheckOnWithoutDuaIsRefused() throws Exception {
    JSONObject body = new JSONObject().put(PurchaseInvoiceImportDua.FIELD_IMPORT_DUA, true);

    assertRefused(PurchaseInvoiceImportDua.checkHeaderWrite(crud("PATCH", INVOICE_ID, body)), 400);
  }

  @Test
  public void turningTheCheckOnWithDuaAndDateContinues() throws Exception {
    JSONObject body = new JSONObject().put(PurchaseInvoiceImportDua.FIELD_IMPORT_DUA, "Y")
        .put(PurchaseInvoiceImportDua.FIELD_DUA, "26ES00281234567890")
        .put(PurchaseInvoiceImportDua.FIELD_DUA_DATE, "2026-11-01");

    assertNull(PurchaseInvoiceImportDua.checkHeaderWrite(crud("PATCH", INVOICE_ID, body)));
  }

  @Test
  public void multipleDuaWaivesTheHeaderDua() throws Exception {
    JSONObject body = new JSONObject().put(PurchaseInvoiceImportDua.FIELD_IMPORT_DUA, true)
        .put(PurchaseInvoiceImportDua.FIELD_MULTI_DUA, true);

    assertNull(PurchaseInvoiceImportDua.checkHeaderWrite(crud("POST", null, body)));
  }

  @Test
  public void anUnrelatedWriteOnAStoredImportWithoutDuaIsRefused() throws Exception {
    when(stored.get(PurchaseInvoiceImportDua.FIELD_IMPORT_DUA)).thenReturn(true);

    assertRefused(PurchaseInvoiceImportDua.checkHeaderWrite(
        crud("PATCH", INVOICE_ID, new JSONObject().put("description", "x"))), 400);
  }

  @Test
  public void aStoredImportWithItsDuaContinues() throws Exception {
    when(stored.get(PurchaseInvoiceImportDua.FIELD_IMPORT_DUA)).thenReturn("Y");
    when(stored.getAeatsiiDua()).thenReturn("26ES00281234567890");
    when(stored.getAeatsiiFechaDua()).thenReturn(new Date());

    assertNull(PurchaseInvoiceImportDua.checkHeaderWrite(
        crud("PATCH", INVOICE_ID, new JSONObject().put("description", "x"))));
  }

  @Test
  public void withoutTheCheckNothingIsRequired() throws Exception {
    assertNull(PurchaseInvoiceImportDua.checkHeaderWrite(
        crud("PATCH", INVOICE_ID, new JSONObject().put("description", "x"))));
  }

  @Test
  public void aLegacyF5WithoutTheCheckNeedsItsDua() throws Exception {
    when(stored.getAeatsiiClaveTipoFc()).thenReturn("F5");

    assertRefused(PurchaseInvoiceImportDua.checkHeaderWrite(
        crud("PATCH", INVOICE_ID, new JSONObject().put("description", "x"))), 400);
  }

  @Test
  public void settingTypeLcWithoutDuaIsRefused() throws Exception {
    JSONObject body = new JSONObject().put(PurchaseInvoiceImportDua.FIELD_SII_TYPE, "LC");

    assertRefused(PurchaseInvoiceImportDua.checkHeaderWrite(crud("PATCH", INVOICE_ID, body)), 400);
  }

  @Test
  public void turningTheCheckOnWhileTheFlagIsOffIs403() throws Exception {
    flag.when(SiiImportDuaFlag::isEnabled).thenReturn(false);
    JSONObject body = new JSONObject().put(PurchaseInvoiceImportDua.FIELD_IMPORT_DUA, true)
        .put(PurchaseInvoiceImportDua.FIELD_MULTI_DUA, true);

    assertRefused(PurchaseInvoiceImportDua.checkHeaderWrite(crud("POST", null, body)), 403);
  }

  @Test
  public void aReadIsNotChecked() {
    assertNull(PurchaseInvoiceImportDua.checkHeaderWrite(crud("GET", INVOICE_ID, null)));
  }
}
