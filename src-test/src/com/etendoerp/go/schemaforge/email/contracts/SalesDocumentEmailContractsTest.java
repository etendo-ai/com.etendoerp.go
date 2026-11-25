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

package com.etendoerp.go.schemaforge.email.contracts;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.when;

import com.etendoerp.go.portal.PortalLinkPolicy;
import com.etendoerp.go.schemaforge.email.DefaultDocumentSendEmailContract;
import com.etendoerp.go.schemaforge.email.EmailContract;
import com.etendoerp.go.schemaforge.email.EmailContractCommand;
import com.etendoerp.go.schemaforge.email.EmailContractCommandSupport;
import com.etendoerp.go.schemaforge.email.EmailContractResolution;
import com.etendoerp.go.schemaforge.email.EmailDocumentRecord;
import com.etendoerp.go.schemaforge.email.EmailDocumentRecordResolver;
import com.etendoerp.go.schemaforge.email.EmailProviderRequest;
import com.etendoerp.go.schemaforge.email.EmailRecipientResolution;
import com.etendoerp.go.schemaforge.email.EmailRecipientSet;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import org.codehaus.jettison.json.JSONObject;
import org.junit.Test;
import org.mockito.MockedStatic;
import org.openbravo.base.model.Entity;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.ad.access.User;
import org.openbravo.model.ad.system.Client;
import org.openbravo.model.common.businesspartner.BusinessPartner;
import org.openbravo.model.common.enterprise.DocumentType;
import org.openbravo.model.common.invoice.Invoice;

/**
 * Unit tests for sales document email contract wiring and recipient resolution, and for which
 * document contracts may carry a customer portal link ({@code bp-portal-link}).
 *
 * @covers com.etendoerp.go.schemaforge.email.contracts.SalesDocumentEmailContractProvider
 * @covers com.etendoerp.go.schemaforge.email.contracts.SalesDocumentEmailRecipientResolver
 * @covers com.etendoerp.go.schemaforge.email.contracts.DalOrderEmailDocumentResolver
 * @covers com.etendoerp.go.schemaforge.email.contracts.DalInvoiceEmailDocumentResolver
 * @covers com.etendoerp.go.schemaforge.email.contracts.SalesInvoiceSendEmailContract
 * @covers com.etendoerp.go.schemaforge.email.contracts.SalesOrderSendEmailContract
 * @covers com.etendoerp.go.schemaforge.email.contracts.SalesQuotationSendEmailContract
 * @covers com.etendoerp.go.schemaforge.email.contracts.GoodsShipmentSendEmailContract
 */
public class SalesDocumentEmailContractsTest {

  @Test
  public void providerRegistersSalesDocumentContracts() {
    Collection<EmailContract> contracts = new SalesDocumentEmailContractProvider().getContracts();

    Set<String> contractNames = contracts.stream()
        .map(EmailContract::getName)
        .collect(Collectors.toSet());

    assertEquals(3, contracts.size());
    assertTrue(contractNames.contains("sales-invoice-send"));
    assertTrue(contractNames.contains("sales-order-send"));
    assertTrue(contractNames.contains("sales-quotation-send"));
    assertTrue(contracts.stream().anyMatch(SalesInvoiceSendEmailContract.class::isInstance));
    assertTrue(contracts.stream().anyMatch(SalesOrderSendEmailContract.class::isInstance));
    assertTrue(contracts.stream().anyMatch(SalesQuotationSendEmailContract.class::isInstance));
  }

  @Test
  public void orderResolverRejectsQuotationAndProposalDocumentSubtypes() {
    DalOrderEmailDocumentResolver resolver = new DalOrderEmailDocumentResolver(
        DalOrderEmailDocumentResolver.SalesOrderDocumentFamily.SALES_ORDER);

    assertTrue(resolver.acceptsDocumentSubtype(null));
    assertTrue(resolver.acceptsDocumentSubtype("SO"));
    assertTrue(resolver.acceptsDocumentSubtype("WR"));
    assertFalse(resolver.acceptsDocumentSubtype("OB"));
    assertFalse(resolver.acceptsDocumentSubtype("ON"));
  }

  @Test
  public void quotationResolverOnlyAcceptsQuotationAndProposalDocumentSubtypes() {
    DalOrderEmailDocumentResolver resolver = new DalOrderEmailDocumentResolver(
        DalOrderEmailDocumentResolver.SalesOrderDocumentFamily.SALES_QUOTATION);

    assertTrue(resolver.acceptsDocumentSubtype("OB"));
    assertTrue(resolver.acceptsDocumentSubtype("ON"));
    assertFalse(resolver.acceptsDocumentSubtype(null));
    assertFalse(resolver.acceptsDocumentSubtype("SO"));
    assertFalse(resolver.acceptsDocumentSubtype("WR"));
  }

  @Test
  public void recipientResolverUsesBusinessPartnerEmailFirst() {
    BusinessPartner businessPartner = mock(BusinessPartner.class);
    when(businessPartner.getEtgoEmail()).thenReturn(" customer@example.com ");

    assertEquals("customer@example.com",
        SalesDocumentEmailRecipientResolver.resolveBusinessPartnerEmail(businessPartner));
  }

  @Test
  public void recipientResolverFallsBackToFirstActiveUserEmail() {
    BusinessPartner businessPartner = mock(BusinessPartner.class);
    User inactiveUser = mock(User.class);
    User activeUser = mock(User.class);
    when(businessPartner.getEtgoEmail()).thenReturn(" ");
    when(inactiveUser.isActive()).thenReturn(false);
    when(inactiveUser.getEmail()).thenReturn("inactive@example.com");
    when(activeUser.isActive()).thenReturn(true);
    when(activeUser.getEmail()).thenReturn(" contact@example.com ");
    when(businessPartner.getADUserList()).thenReturn(Arrays.asList(inactiveUser, activeUser));

    assertEquals(" contact@example.com ",
        SalesDocumentEmailRecipientResolver.resolveBusinessPartnerEmail(businessPartner));
  }

  @Test
  public void recipientResolverRejectsMissingRecipientSources() {
    BusinessPartner businessPartner = mock(BusinessPartner.class);
    User userWithoutEmail = mock(User.class);
    when(businessPartner.getEtgoEmail()).thenReturn(null);
    when(userWithoutEmail.isActive()).thenReturn(true);
    when(userWithoutEmail.getEmail()).thenReturn(" ");
    when(businessPartner.getADUserList()).thenReturn(Arrays.asList(userWithoutEmail));

    assertNull(SalesDocumentEmailRecipientResolver.resolveBusinessPartnerEmail(null));
    assertNull(SalesDocumentEmailRecipientResolver.resolveBusinessPartnerEmail(businessPartner));
  }

  // ── ETP-6209: the customer portal link, gated by bp-portal-link ─────────────

  private static final String PORTAL_DAL = "com.etendoerp.go.portal.PortalLinkDal";
  private static final EmailDocumentRecordResolver RESOLVER = recordId -> Optional.of(
      new EmailDocumentRecord("Cliente", "customer@example.test", recordId, "DOC-0001", null,
          "https://app.example.test/doc/" + recordId, "tenant-1"));

  @Test
  public void salesContractsSendTheEmailUnchangedAndMintNothingWhileTheFlagIsClosed()
      throws Exception {
    // Each sales contract next to the same contract without a portal declaration: the email
    // as it was before the portal link existed.
    List<DefaultDocumentSendEmailContract[]> pairs = Arrays.asList(
        new DefaultDocumentSendEmailContract[] { new SalesQuotationSendEmailContract(RESOLVER),
            new DefaultDocumentSendEmailContract(SalesQuotationSendEmailContract.NAME,
                "Sales Quotation", RESOLVER) { } },
        new DefaultDocumentSendEmailContract[] { new SalesOrderSendEmailContract(RESOLVER),
            new DefaultDocumentSendEmailContract(SalesOrderSendEmailContract.NAME, "Sales Order",
                RESOLVER) { } },
        new DefaultDocumentSendEmailContract[] { new GoodsShipmentSendEmailContract(RESOLVER),
            new DefaultDocumentSendEmailContract(GoodsShipmentSendEmailContract.NAME,
                "Goods Shipment", RESOLVER) { } },
        new DefaultDocumentSendEmailContract[] { new SalesInvoiceSendEmailContract(RESOLVER),
            new DefaultDocumentSendEmailContract(SalesInvoiceSendEmailContract.NAME,
                DefaultDocumentSendEmailContract.CONTENT_TEMPLATE, "Sales Invoice",
                "invoice_number", true, RESOLVER) { } });

    try (MockedStatic<PortalLinkPolicy> policy = mockStatic(PortalLinkPolicy.class);
        MockedStatic<?> portalDal = mockStatic(Class.forName(PORTAL_DAL))) {
      policy.when(PortalLinkPolicy::isLinkEnabled).thenReturn(false);

      for (DefaultDocumentSendEmailContract[] pair : pairs) {
        EmailProviderRequest gated = resolve(pair[0]);
        EmailProviderRequest baseline = resolve(pair[1]);

        assertEquals(pair[0].getName(), baseline.getData().toString(),
            gated.getData().toString());
        assertNull(pair[0].getName(), gated.getSendCorrelationId());
        // The download button stays the email's one button.
        String body = gated.getData().getString("body");
        assertTrue(pair[0].getName(), body.contains("Descargar documento"));
        assertTrue(pair[0].getName(), body.contains("https://app.example.test/doc/doc-1"));
      }
      // The gate was asked once per send — the email is unchanged because it said no.
      policy.verify(PortalLinkPolicy::isLinkEnabled, times(4));
      portalDal.verifyNoInteractions();
    }
  }

  @Test
  public void purchaseAndReturnContractsNeverAskForAPortalLinkEvenWithTheFlagOpen()
      throws Exception {
    List<DefaultDocumentSendEmailContract> contracts = Arrays.asList(
        new PurchaseOrderSendEmailContract(RESOLVER),
        new ReturnToVendorSendEmailContract(RESOLVER),
        new ReturnToVendorShipmentSendEmailContract(RESOLVER),
        new ReturnMaterialReceiptSendEmailContract(RESOLVER));

    try (MockedStatic<PortalLinkPolicy> policy = mockStatic(PortalLinkPolicy.class);
        MockedStatic<?> portalDal = mockStatic(Class.forName(PORTAL_DAL))) {
      policy.when(PortalLinkPolicy::isLinkEnabled).thenReturn(true);

      for (DefaultDocumentSendEmailContract contract : contracts) {
        EmailProviderRequest request = resolve(contract);

        assertNull(contract.getName(), request.getSendCorrelationId());
        String body = request.getData().getString("body");
        assertFalse(contract.getName(), body.contains("/portal/"));
        assertTrue(contract.getName(), body.contains("Descargar documento"));
      }
      policy.verifyNoInteractions();
      portalDal.verifyNoInteractions();
    }
  }

  /**
   * The invoice resolver marks a credit note with the {@code creditNote} type variant, so the sales
   * invoice email names it «Factura rectificativa» (ETP-6209 QA). The rule is the portal's: an
   * {@code ARC} or {@code ARI_RM} category, or the localization module's rectificative flag when
   * that module is installed. The amount's sign plays no part.
   */
  @Test
  public void invoiceResolverMarksOnlyARectificativeInvoiceAsACreditNote() {
    // Columns: document category, the flag's property exists, its value, total, expected variant.
    Object[][] cases = {
        { "ARC", false, null, BigDecimal.TEN, DalInvoiceEmailDocumentResolver.CREDIT_NOTE },
        { "ARI_RM", false, null, BigDecimal.TEN, DalInvoiceEmailDocumentResolver.CREDIT_NOTE },
        { "ARI", true, Boolean.TRUE, BigDecimal.TEN, DalInvoiceEmailDocumentResolver.CREDIT_NOTE },
        { "ARI", true, Boolean.FALSE, BigDecimal.TEN, null },
        { "ARI", false, null, BigDecimal.TEN, null },
        // A normal invoice with a negative total is still an invoice.
        { "ARI", false, null, new BigDecimal("-50.00"), null },
    };
    for (Object[] c : cases) {
      DocumentType documentType = invoiceDocumentType((String) c[0], (Boolean) c[1],
          (Boolean) c[2]);
      Invoice invoice = invoice(documentType, (BigDecimal) c[3]);
      OBDal obDal = mock(OBDal.class);
      when(obDal.get(Invoice.class, "inv-1")).thenReturn(invoice);

      try (MockedStatic<OBDal> dal = mockStatic(OBDal.class)) {
        dal.when(OBDal::getInstance).thenReturn(obDal);

        EmailDocumentRecord record = new DalInvoiceEmailDocumentResolver().resolve("inv-1")
            .orElseThrow(() -> new AssertionError(Arrays.toString(c)));

        assertEquals(Arrays.toString(c), c[4], record.getDocumentTypeVariant());
        assertEquals("CO", record.getDocumentStatus());
        assertEquals("INV-001", record.getDocumentNumber());
      }
    }
  }

  private static DocumentType invoiceDocumentType(String category, boolean hasFlag,
      Boolean flag) {
    Entity entity = mock(Entity.class);
    when(entity.hasProperty("etsgIsRectificative")).thenReturn(hasFlag);
    DocumentType documentType = mock(DocumentType.class);
    when(documentType.getDocumentCategory()).thenReturn(category);
    when(documentType.getEntity()).thenReturn(entity);
    when(documentType.get("etsgIsRectificative")).thenReturn(flag);
    return documentType;
  }

  private static Invoice invoice(DocumentType documentType, BigDecimal total) {
    // Client "0" is readable without an OBContext; no business partner skips the email lookup.
    Client client = mock(Client.class);
    when(client.getId()).thenReturn("0");
    Invoice invoice = mock(Invoice.class);
    when(invoice.getId()).thenReturn("inv-1");
    when(invoice.isActive()).thenReturn(Boolean.TRUE);
    when(invoice.getClient()).thenReturn(client);
    when(invoice.getDocumentNo()).thenReturn("INV-001");
    when(invoice.getDocumentStatus()).thenReturn("CO");
    when(invoice.getGrandTotalAmount()).thenReturn(total);
    when(invoice.getTransactionDocument()).thenReturn(documentType);
    return invoice;
  }

  private static EmailProviderRequest resolve(DefaultDocumentSendEmailContract contract)
      throws Exception {
    JSONObject body = new JSONObject();
    body.put(EmailContractCommandSupport.FIELD_RECORD_ID, "doc-1");
    body.put(EmailContractCommandSupport.FIELD_LANGUAGE, "es_ES");
    EmailContractResolution resolution = contract.resolve(
        new EmailContractCommand(contract.getName(), body),
        EmailRecipientResolution.serverResolved(EmailRecipientSet.of(
            Collections.singletonList("customer@example.test"),
            Collections.singletonList("boss@example.test"))));
    assertTrue(contract.getName() + ": " + resolution.getMessage(), resolution.isReady());
    return resolution.getProviderRequest();
  }
}
