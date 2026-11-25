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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.common.businesspartner.Location;
import org.openbravo.model.common.enterprise.Organization;
import org.openbravo.model.common.enterprise.OrganizationInformation;
import org.openbravo.model.common.geography.Country;
import org.openbravo.model.common.geography.Region;
import org.openbravo.model.common.invoice.Invoice;
import org.openbravo.module.sii.utils.SIIUtils;

/**
 * Unit tests for {@link PurchaseInvoiceSiiDeferredVat}: the {@code siiDeferredVat} action of the
 * purchase-invoice header, which tells the SPA whether to offer the compensatory import-tax line.
 * {@code deferredVat} is true only for an import with DUA (the check, or type F5/LC) of an IVA
 * organization whose supplier address the SII module classifies as deferred VAT — never depending on the SII
 * configuration (adoption, tax type, invoice type).
 *
 * @covers com.etendoerp.go.schemaforge.PurchaseInvoiceSiiDeferredVat
 */
public class PurchaseInvoiceSiiDeferredVatTest {

  private static final String INVOICE_ID = "pinv-dua";

  private MockedStatic<OBDal> obDal;
  private MockedStatic<OBContext> obContext;
  private MockedStatic<SiiImportDuaFlag> siiImportDua;
  private MockedStatic<SIIUtils> siiUtils;

  private OBDal dal;
  private Invoice invoice;
  private Location partnerAddress;

  @Before
  public void setUp() {
    // ETP-6284: the feature flag on, so the existing cases exercise the feature itself.
    siiImportDua = mockStatic(SiiImportDuaFlag.class, Mockito.CALLS_REAL_METHODS);
    siiImportDua.when(SiiImportDuaFlag::isEnabled).thenReturn(true);
    obDal = mockStatic(OBDal.class);
    obContext = mockStatic(OBContext.class);
    siiUtils = mockStatic(SIIUtils.class);
    obContext.when(() -> OBContext.setAdminMode(anyBoolean())).thenAnswer(i -> null);
    obContext.when(OBContext::restorePreviousMode).thenAnswer(i -> null);

    dal = mock(OBDal.class);
    obDal.when(OBDal::getInstance).thenReturn(dal);

    // An import with DUA whose supplier is of deferred VAT: every guard passes.
    invoice = mock(Invoice.class);
    when(dal.get(Invoice.class, INVOICE_ID)).thenReturn(invoice);
    when(invoice.getId()).thenReturn(INVOICE_ID);
    when(invoice.get(PurchaseInvoiceImportDua.FIELD_IMPORT_DUA)).thenReturn(true);
    partnerAddress = mock(Location.class);
    when(invoice.getPartnerAddress()).thenReturn(partnerAddress);
    when(partnerAddress.getLocationAddress())
        .thenReturn(mock(org.openbravo.model.common.geography.Location.class));
    siiUtils.when(() -> SIIUtils.isDeferredVATInvoice(invoice)).thenReturn(true);
  }

  @After
  public void tearDown() {
    siiImportDua.close();
    siiUtils.close();
    obContext.close();
    obDal.close();
  }

  private static NeoContext actionCtx(String method, String recordId) {
    return NeoContext.builder()
        .specName("purchase-invoice").entityName("header")
        .httpMethod(method).endpointType(NeoEndpointType.ACTION)
        .fieldName(PurchaseInvoiceSiiDeferredVat.ACTION_NAME).recordId(recordId).build();
  }

  private static boolean deferredVat(NeoResponse response) throws Exception {
    assertEquals(200, response.getHttpStatus());
    return response.getBody().getBoolean(PurchaseInvoiceSiiDeferredVat.KEY_DEFERRED_VAT);
  }

  private boolean answer() throws Exception {
    return deferredVat(PurchaseInvoiceSiiDeferredVat.handleIfDeferredVat(actionCtx("GET",
        INVOICE_ID)));
  }

  @Test
  public void anotherActionIsNotServed() {
    NeoContext other = NeoContext.builder()
        .httpMethod("GET").endpointType(NeoEndpointType.ACTION).fieldName("siiSend")
        .recordId(INVOICE_ID).build();

    assertNull(PurchaseInvoiceSiiDeferredVat.handleIfDeferredVat(other));
  }

  @Test
  public void anUnknownInvoiceIs404() {
    NeoResponse response = PurchaseInvoiceSiiDeferredVat.handleIfDeferredVat(
        actionCtx("GET", "missing"));

    assertEquals(404, response.getHttpStatus());
  }

  @Test
  public void aPostIs405() {
    NeoResponse response = PurchaseInvoiceSiiDeferredVat.handleIfDeferredVat(
        actionCtx("POST", INVOICE_ID));

    assertEquals(405, response.getHttpStatus());
  }

  @Test
  public void aDeferredVatImportIsTrue() throws Exception {
    assertTrue(answer());
  }

  @Test
  public void theCheckStoredAsYIsTrue() throws Exception {
    when(invoice.get(PurchaseInvoiceImportDua.FIELD_IMPORT_DUA)).thenReturn("Y");

    assertTrue(answer());
  }

  @Test
  public void anInvoiceWithoutTheImportCheckIsFalse() throws Exception {
    when(invoice.get(PurchaseInvoiceImportDua.FIELD_IMPORT_DUA)).thenReturn(false);

    assertFalse(answer());
  }

  @Test
  public void theAnswerNeverReadsTheSiiConfiguration() throws Exception {
    when(invoice.getAeatsiiClaveTipoFc()).thenReturn(null);

    assertTrue(answer());
    siiUtils.verify(() -> SIIUtils.getSiiConfigFromOrg(any()), org.mockito.Mockito.never());
  }

  @Test
  public void aLegacyF5ImportWithoutTheCheckIsTrue() throws Exception {
    when(invoice.get(PurchaseInvoiceImportDua.FIELD_IMPORT_DUA)).thenReturn(false);
    when(invoice.getAeatsiiClaveTipoFc()).thenReturn("F5");

    assertTrue(answer());
  }

  @Test
  public void anIgicOrganizationIsFalse() throws Exception {
    Organization org = orgIn("ES", "35");
    when(invoice.getOrganization()).thenReturn(org);

    assertFalse(answer());
  }

  @Test
  public void aPeninsulaOrganizationIsTrue() throws Exception {
    Organization org = orgIn("ES", "28");
    when(invoice.getOrganization()).thenReturn(org);

    assertTrue(answer());
  }

  private static Organization orgIn(String countryCode, String regionCode) {
    Organization org = mock(Organization.class);
    OrganizationInformation info = mock(OrganizationInformation.class);
    org.openbravo.model.common.geography.Location address =
        mock(org.openbravo.model.common.geography.Location.class);
    Country country = mock(Country.class);
    Region region = mock(Region.class);
    when(org.getOrganizationInformationList()).thenReturn(List.of(info));
    when(info.getLocationAddress()).thenReturn(address);
    when(address.getCountry()).thenReturn(country);
    when(address.getRegion()).thenReturn(region);
    when(country.getISOCountryCode()).thenReturn(countryCode);
    when(region.getEsregRegionCode()).thenReturn(regionCode);
    return org;
  }

  @Test
  public void aPartnerAddressWithoutLocationIsFalseWithoutAskingTheSiiModule() throws Exception {
    when(partnerAddress.getLocationAddress()).thenReturn(null);

    assertFalse(answer());
    siiUtils.verify(() -> SIIUtils.isDeferredVATInvoice(any()), org.mockito.Mockito.never());
  }

  @Test
  public void anInvoiceTheSiiModuleDoesNotClassifyAsDeferredIsFalse() throws Exception {
    siiUtils.when(() -> SIIUtils.isDeferredVATInvoice(invoice)).thenReturn(false);

    assertFalse(answer());
  }
}
