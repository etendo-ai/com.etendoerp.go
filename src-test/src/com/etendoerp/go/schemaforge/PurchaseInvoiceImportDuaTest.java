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
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
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
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.common.enterprise.Organization;
import org.openbravo.model.common.invoice.Invoice;
import org.openbravo.module.sii.data.AEATSIIConfig;
import org.openbravo.module.sii.utils.SIIUtils;

/**
 * Unit tests for {@link PurchaseInvoiceImportDua}: the header rule of the "Importación con DUA"
 * check — DUA and DUA date required unless Multiple DUA, only for an organization that files SII
 * for the invoice (independent of the SII invoice type) — and the flag gate on turning the check
 * on. By default the organization files SII (adopted before the invoice's accounting date).
 *
 * @covers com.etendoerp.go.schemaforge.PurchaseInvoiceImportDua
 */
public class PurchaseInvoiceImportDuaTest {

  private static final String INVOICE_ID = "pinv-import";
  private static final String ORG_ID = "org-sii";

  private MockedStatic<OBDal> obDal;
  private MockedStatic<SiiImportDuaFlag> flag;
  private MockedStatic<OBContext> obContext;
  private MockedStatic<SalesRectificativeFiscalDefaults> fiscalDefaults;
  private MockedStatic<SIIUtils> siiUtils;
  private OBDal dal;
  private Invoice stored;
  private AEATSIIConfig siiConfig;

  @Before
  public void setUp() {
    flag = mockStatic(SiiImportDuaFlag.class, Mockito.CALLS_REAL_METHODS);
    flag.when(SiiImportDuaFlag::isEnabled).thenReturn(true);
    obDal = mockStatic(OBDal.class);
    dal = mock(OBDal.class);
    obDal.when(OBDal::getInstance).thenReturn(dal);
    stored = mock(Invoice.class);
    when(dal.get(Invoice.class, INVOICE_ID)).thenReturn(stored);
    when(stored.isAeatsiiMultiDua()).thenReturn(false);

    Organization org = mock(Organization.class);
    when(org.getId()).thenReturn(ORG_ID);
    when(stored.getOrganization()).thenReturn(org);
    when(stored.getAccountingDate()).thenReturn(new Date());
    when(dal.get(Organization.class, ORG_ID)).thenReturn(org);
    obContext = mockStatic(OBContext.class);
    obContext.when(() -> OBContext.setAdminMode(anyBoolean())).thenAnswer(i -> null);
    obContext.when(OBContext::restorePreviousMode).thenAnswer(i -> null);
    OBContext ctx = mock(OBContext.class);
    when(ctx.getCurrentOrganization()).thenReturn(org);
    obContext.when(OBContext::getOBContext).thenReturn(ctx);
    siiConfig = mock(AEATSIIConfig.class);
    when(siiConfig.getFechaAcogidaSII()).thenReturn(new Date(0L));
    fiscalDefaults = mockStatic(SalesRectificativeFiscalDefaults.class);
    fiscalDefaults.when(() -> SalesRectificativeFiscalDefaults.siiConfigActive(anyString()))
        .thenReturn(true);
    siiUtils = mockStatic(SIIUtils.class);
    siiUtils.when(() -> SIIUtils.getSiiConfigFromOrg(any(Organization.class)))
        .thenReturn(siiConfig);
  }

  @After
  public void tearDown() {
    siiUtils.close();
    fiscalDefaults.close();
    obContext.close();
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

    NeoResponse response = PurchaseInvoiceImportDua.checkHeaderWrite(
        crud("PATCH", INVOICE_ID, new JSONObject().put("description", "x")));

    // check off (stored N), no DUA, SII active: refused with the import-DUA key
    assertRefused(response, 400);
    assertEquals(PurchaseInvoiceImportDua.MSG_DUA_REQUIRED, response.getBody()
        .getJSONObject("error").getJSONArray(NeoProcessService.MESSAGE_KEYS).getString(0));
  }

  @Test
  public void settingTypeLcWithoutDuaIsRefused() throws Exception {
    JSONObject body = new JSONObject().put(PurchaseInvoiceImportDua.FIELD_SII_TYPE, "LC");

    assertRefused(PurchaseInvoiceImportDua.checkHeaderWrite(crud("PATCH", INVOICE_ID, body)), 400);
  }

  @Test
  public void uncheckingAndMovingTheTypeAwayFromF5InOneSaveNeedsNoDua() throws Exception {
    when(stored.get(PurchaseInvoiceImportDua.FIELD_IMPORT_DUA)).thenReturn("Y");
    when(stored.getAeatsiiClaveTipoFc()).thenReturn("F5");
    JSONObject body = new JSONObject().put(PurchaseInvoiceImportDua.FIELD_IMPORT_DUA, "N")
        .put(PurchaseInvoiceImportDua.FIELD_SII_TYPE, "F1");

    assertNull(PurchaseInvoiceImportDua.checkHeaderWrite(crud("PATCH", INVOICE_ID, body)));
  }

  @Test
  public void uncheckingAStoredImportWithNoTypeNeedsNoDua() throws Exception {
    when(stored.get(PurchaseInvoiceImportDua.FIELD_IMPORT_DUA)).thenReturn(true);
    JSONObject body = new JSONObject().put(PurchaseInvoiceImportDua.FIELD_IMPORT_DUA, false);

    assertNull(PurchaseInvoiceImportDua.checkHeaderWrite(crud("PATCH", INVOICE_ID, body)));
  }

  @Test
  public void uncheckingWhileTheStoredTypeStaysF5StillNeedsTheDua() throws Exception {
    when(stored.get(PurchaseInvoiceImportDua.FIELD_IMPORT_DUA)).thenReturn("Y");
    when(stored.getAeatsiiClaveTipoFc()).thenReturn("F5");
    JSONObject body = new JSONObject().put(PurchaseInvoiceImportDua.FIELD_IMPORT_DUA, "N");

    assertRefused(PurchaseInvoiceImportDua.checkHeaderWrite(crud("PATCH", INVOICE_ID, body)), 400);
  }

  @Test
  public void turningTheCheckOnWhileTheFlagIsOffIs403() throws Exception {
    flag.when(SiiImportDuaFlag::isEnabled).thenReturn(false);
    JSONObject body = new JSONObject().put(PurchaseInvoiceImportDua.FIELD_IMPORT_DUA, true)
        .put(PurchaseInvoiceImportDua.FIELD_MULTI_DUA, true);

    assertRefused(PurchaseInvoiceImportDua.checkHeaderWrite(crud("POST", null, body)), 403);
  }

  // ── the requirement holds only where the organization files SII ─────────────────────────

  @Test
  public void aNonSiiOrgWithTheCheckOnAndNoDuaIsAccepted() throws Exception {
    fiscalDefaults.when(() -> SalesRectificativeFiscalDefaults.siiConfigActive(anyString()))
        .thenReturn(false);
    JSONObject body = new JSONObject().put(PurchaseInvoiceImportDua.FIELD_IMPORT_DUA, true);

    assertNull(PurchaseInvoiceImportDua.checkHeaderWrite(crud("PATCH", INVOICE_ID, body)));
  }

  @Test
  public void anSiiOrgWithTheCheckOnAndNoDuaIs400() throws Exception {
    JSONObject body = new JSONObject().put(PurchaseInvoiceImportDua.FIELD_IMPORT_DUA, true)
        .put(PurchaseInvoiceImportDua.FIELD_ORG, ORG_ID)
        .put(PurchaseInvoiceImportDua.FIELD_DUA, "");

    NeoResponse response = PurchaseInvoiceImportDua.checkHeaderWrite(
        crud("PATCH", INVOICE_ID, body));

    assertRefused(response, 400);
    assertEquals(PurchaseInvoiceImportDua.MSG_DUA_REQUIRED, response.getBody()
        .getJSONObject("error").getJSONArray(NeoProcessService.MESSAGE_KEYS).getString(0));
  }

  @Test
  public void anInvoiceDatedBeforeTheSiiAdoptionNeedsNoDua() throws Exception {
    when(siiConfig.getFechaAcogidaSII()).thenReturn(new Date());
    JSONObject body = new JSONObject().put(PurchaseInvoiceImportDua.FIELD_IMPORT_DUA, true)
        .put(PurchaseInvoiceImportDua.FIELD_ACCOUNTING_DATE, "2020-01-15");

    assertNull(PurchaseInvoiceImportDua.checkHeaderWrite(crud("PATCH", INVOICE_ID, body)));
  }

  @Test
  public void aNonSiiOrgCreatingAnImportWithoutDuaIsAccepted() throws Exception {
    fiscalDefaults.when(() -> SalesRectificativeFiscalDefaults.siiConfigActive(anyString()))
        .thenReturn(false);
    JSONObject body = new JSONObject().put(PurchaseInvoiceImportDua.FIELD_IMPORT_DUA, true);

    assertNull(PurchaseInvoiceImportDua.checkHeaderWrite(crud("POST", null, body)));
  }

  @Test
  public void aReadIsNotChecked() {
    assertNull(PurchaseInvoiceImportDua.checkHeaderWrite(crud("GET", INVOICE_ID, null)));
  }
  // ── orgFilesSii(String, Date): Classic's "files SII for this date" criterion ─────────────

  private static final String PLAIN_ORG_ID = "org-plain";
  private static final long DAY = 86_400_000L;

  @Test
  public void aBlankOrgDoesNotFileSii() {
    assertFalse(PurchaseInvoiceImportDua.orgFilesSii((String) null, new Date()));
    assertFalse(PurchaseInvoiceImportDua.orgFilesSii("  ", new Date()));
    fiscalDefaults.verifyNoInteractions();
  }

  @Test
  public void anOrgWithoutAnActiveSiiConfigDoesNotFileSii() {
    fiscalDefaults.when(() -> SalesRectificativeFiscalDefaults.siiConfigActive(ORG_ID))
        .thenReturn(false);

    assertFalse(PurchaseInvoiceImportDua.orgFilesSii(ORG_ID, new Date()));
    siiUtils.verifyNoInteractions();
  }

  @Test
  public void anOrgWhoseSiiConfigIsMissingDoesNotFileSii() {
    siiUtils.when(() -> SIIUtils.getSiiConfigFromOrg(any(Organization.class))).thenReturn(null);

    assertFalse(PurchaseInvoiceImportDua.orgFilesSii(ORG_ID, new Date()));
  }

  @Test
  public void anUnknownOrgDoesNotFileSii() {
    when(dal.get(Organization.class, ORG_ID)).thenReturn(null);

    assertFalse(PurchaseInvoiceImportDua.orgFilesSii(ORG_ID, new Date()));
    siiUtils.verifyNoInteractions();
  }

  @Test
  public void aConfigWithoutAdoptionDateDoesNotFileSii() {
    when(siiConfig.getFechaAcogidaSII()).thenReturn(null);

    assertFalse(PurchaseInvoiceImportDua.orgFilesSii(ORG_ID, new Date()));
  }

  @Test
  public void anAccountingDateOnTheAdoptionDateFilesSii() {
    assertTrue(PurchaseInvoiceImportDua.orgFilesSii(ORG_ID, new Date(0L)));
  }

  @Test
  public void anAccountingDateTheDayBeforeTheAdoptionDoesNotFileSii() {
    assertFalse(PurchaseInvoiceImportDua.orgFilesSii(ORG_ID, new Date(-DAY)));
  }

  @Test
  public void anUnknownAccountingDateReadsAsAdopted() {
    assertTrue(PurchaseInvoiceImportDua.orgFilesSii(ORG_ID, null));
  }

  @Test
  public void aFailingLookupReadsAsNotFilingSiiAndRestoresTheMode() {
    fiscalDefaults.when(() -> SalesRectificativeFiscalDefaults.siiConfigActive(anyString()))
        .thenThrow(new IllegalStateException("boom"));

    assertFalse(PurchaseInvoiceImportDua.orgFilesSii(ORG_ID, new Date()));
    obContext.verify(() -> OBContext.setAdminMode(true));
    obContext.verify(OBContext::restorePreviousMode);
  }

  // ── orgFilesSii(Invoice): accounting date, else invoice date ─────────────────────────────

  @Test
  public void aStoredInvoiceIsJudgedByItsAccountingDate() {
    when(stored.getAccountingDate()).thenReturn(new Date(-DAY));
    when(stored.getInvoiceDate()).thenReturn(new Date());

    assertFalse(PurchaseInvoiceImportDua.orgFilesSii(stored));
  }

  @Test
  public void aStoredInvoiceWithoutAccountingDateFallsBackToItsInvoiceDate() {
    when(stored.getAccountingDate()).thenReturn(null);
    when(stored.getInvoiceDate()).thenReturn(new Date(-DAY));

    assertFalse(PurchaseInvoiceImportDua.orgFilesSii(stored));
    when(stored.getInvoiceDate()).thenReturn(new Date(DAY));
    assertTrue(PurchaseInvoiceImportDua.orgFilesSii(stored));
  }

  @Test
  public void aStoredInvoiceWithoutOrgDoesNotFileSii() {
    when(stored.getOrganization()).thenReturn(null);

    assertFalse(PurchaseInvoiceImportDua.orgFilesSii(stored));
    assertFalse(PurchaseInvoiceImportDua.orgFilesSii((Invoice) null));
    fiscalDefaults.verifyNoInteractions();
  }

  // ── the request merged over the stored invoice: organization and dates ───────────────────

  /** Only {@link #ORG_ID} files SII; {@link #PLAIN_ORG_ID} does not. */
  private Organization onlyTheSiiOrgFilesSii() {
    fiscalDefaults.when(() -> SalesRectificativeFiscalDefaults.siiConfigActive(anyString()))
        .thenReturn(false);
    fiscalDefaults.when(() -> SalesRectificativeFiscalDefaults.siiConfigActive(ORG_ID))
        .thenReturn(true);
    Organization plain = mock(Organization.class);
    when(plain.getId()).thenReturn(PLAIN_ORG_ID);
    when(dal.get(Organization.class, PLAIN_ORG_ID)).thenReturn(plain);
    return plain;
  }

  @Test
  public void aBodyOrgSentAsAnObjectOverridesTheStoredOne() throws Exception {
    onlyTheSiiOrgFilesSii();
    JSONObject body = new JSONObject().put(PurchaseInvoiceImportDua.FIELD_IMPORT_DUA, true)
        .put(PurchaseInvoiceImportDua.FIELD_ORG, new JSONObject().put("id", PLAIN_ORG_ID));

    assertNull(PurchaseInvoiceImportDua.checkHeaderWrite(crud("PATCH", INVOICE_ID, body)));
    fiscalDefaults.verify(() -> SalesRectificativeFiscalDefaults.siiConfigActive(PLAIN_ORG_ID));
  }

  @Test
  public void aBodyOrgSentAsAStringOverridesTheStoredOne() throws Exception {
    Organization plain = onlyTheSiiOrgFilesSii();
    when(stored.getOrganization()).thenReturn(plain);
    JSONObject body = new JSONObject().put(PurchaseInvoiceImportDua.FIELD_IMPORT_DUA, true)
        .put(PurchaseInvoiceImportDua.FIELD_ORG, ORG_ID);

    assertRefused(PurchaseInvoiceImportDua.checkHeaderWrite(crud("PATCH", INVOICE_ID, body)), 400);
  }

  @Test
  public void aBodyWithoutOrgUsesTheStoredOne() throws Exception {
    Organization plain = onlyTheSiiOrgFilesSii();
    when(stored.getOrganization()).thenReturn(plain);
    JSONObject body = new JSONObject().put(PurchaseInvoiceImportDua.FIELD_IMPORT_DUA, true);

    assertNull(PurchaseInvoiceImportDua.checkHeaderWrite(crud("PATCH", INVOICE_ID, body)));
    fiscalDefaults.verify(() -> SalesRectificativeFiscalDefaults.siiConfigActive(PLAIN_ORG_ID));
    fiscalDefaults.verify(() -> SalesRectificativeFiscalDefaults.siiConfigActive(ORG_ID),
        Mockito.never());
  }

  @Test
  public void aBodyWithoutDatesUsesTheStoredAccountingDate() throws Exception {
    when(stored.getAccountingDate()).thenReturn(new Date(-DAY));
    JSONObject body = new JSONObject().put(PurchaseInvoiceImportDua.FIELD_IMPORT_DUA, true);

    assertNull(PurchaseInvoiceImportDua.checkHeaderWrite(crud("PATCH", INVOICE_ID, body)));
  }

  @Test
  public void withoutAnyAccountingDateTheBodyInvoiceDateDecides() throws Exception {
    when(stored.getAccountingDate()).thenReturn(null);
    when(stored.getInvoiceDate()).thenReturn(new Date());
    JSONObject body = new JSONObject().put(PurchaseInvoiceImportDua.FIELD_IMPORT_DUA, true)
        .put(PurchaseInvoiceImportDua.FIELD_INVOICE_DATE, "1969-12-01");

    assertNull(PurchaseInvoiceImportDua.checkHeaderWrite(crud("PATCH", INVOICE_ID, body)));
  }

  @Test
  public void turningTheCheckOffWhileTheFlagIsOffIsNotGated() throws Exception {
    flag.when(SiiImportDuaFlag::isEnabled).thenReturn(false);
    JSONObject body = new JSONObject().put(PurchaseInvoiceImportDua.FIELD_IMPORT_DUA, false);

    assertNull(PurchaseInvoiceImportDua.checkHeaderWrite(crud("PATCH", INVOICE_ID, body)));
  }
}
