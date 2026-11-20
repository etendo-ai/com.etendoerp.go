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

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.codehaus.jettison.json.JSONObject;
import org.hibernate.criterion.Criterion;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBCriteria;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.common.currency.Currency;
import org.openbravo.model.common.invoice.Invoice;
import org.openbravo.model.financialmgmt.payment.FIN_Payment;
import org.openbravo.model.financialmgmt.payment.FIN_PaymentDetail;
import org.openbravo.model.financialmgmt.payment.FIN_PaymentSchedule;
import org.openbravo.model.financialmgmt.payment.FIN_PaymentScheduleDetail;
import org.openbravo.module.remittance.Remittance;
import org.openbravo.module.remittance.RemittanceLine;

/**
 * Unit tests for {@link InvoiceRemittance}: what a confirmed remittance means on the invoice side —
 * the payable and remitted amounts of an installment, the remittance a payment came from, and the
 * three refusals (registration over the payable part, linking a taken detail, touching a remitted
 * payment).
 *
 * <p>A class of its own: {@code InvoiceRemittance} is a new shared util and no existing test class
 * is about it. The callers' tests ({@code PaymentRegistrationService*Test},
 * {@code PaymentAgentSupportTest}, {@code AddPaymentServiceTest}, …) guard how each flow uses it.
 *
 * <p>The DAL is a static mock: only {@code REM_RemittanceLine} is queried; the message text comes
 * from the {@code neo_hints} catalog because the mocked connection yields no {@code AD_MESSAGE}.
 *
 * @covers com.etendoerp.go.schemaforge.InvoiceRemittance
 */
class InvoiceRemittanceTest {

  private static final String REM_SENT = InvoiceRemittance.STATUS_REMITTED;

  private MockedStatic<OBDal> dalStatic;
  private MockedStatic<OBContext> contextStatic;
  private OBDal dal;
  private OBCriteria<RemittanceLine> lineCriteria;

  @BeforeEach
  @SuppressWarnings("unchecked")
  void setUp() {
    dal = mock(OBDal.class);
    lineCriteria = mock(OBCriteria.class);
    when(dal.createCriteria(RemittanceLine.class)).thenReturn(lineCriteria);
    when(lineCriteria.add(any(Criterion.class))).thenReturn(lineCriteria);
    dalStatic = mockStatic(OBDal.class);
    dalStatic.when(OBDal::getInstance).thenReturn(dal);
    contextStatic = mockStatic(OBContext.class);
  }

  @AfterEach
  void tearDown() {
    contextStatic.close();
    dalStatic.close();
  }

  // ─── fixtures ──────────────────────────────────────────────────────────────

  private static FIN_Payment payment(String id, String status) {
    FIN_Payment payment = mock(FIN_Payment.class);
    when(payment.getId()).thenReturn(id);
    when(payment.getStatus()).thenReturn(status);
    when(payment.getDocumentNo()).thenReturn("PAY-" + id);
    return payment;
  }

  /** A detail of {@code amount}, free when {@code linkedTo} is {@code null}. */
  private static FIN_PaymentScheduleDetail psd(String amount, FIN_Payment linkedTo) {
    FIN_PaymentScheduleDetail psd = mock(FIN_PaymentScheduleDetail.class);
    when(psd.getId()).thenReturn("PSD-" + amount);
    when(psd.getAmount()).thenReturn(amount == null ? null : new BigDecimal(amount));
    if (linkedTo != null) {
      FIN_PaymentDetail detail = mock(FIN_PaymentDetail.class);
      when(detail.getFinPayment()).thenReturn(linkedTo);
      when(psd.getPaymentDetails()).thenReturn(detail);
    }
    return psd;
  }

  private static FIN_PaymentSchedule schedule(Long precision, FIN_PaymentScheduleDetail... psds) {
    FIN_PaymentSchedule schedule = mock(FIN_PaymentSchedule.class);
    when(schedule.getFINPaymentScheduleDetailInvoicePaymentScheduleList())
        .thenReturn(new ArrayList<>(Arrays.asList(psds)));
    Invoice invoice = mock(Invoice.class);
    Currency currency = mock(Currency.class);
    when(currency.getStandardPrecision()).thenReturn(precision);
    when(currency.getISOCode()).thenReturn("EUR");
    when(invoice.getCurrency()).thenReturn(currency);
    when(schedule.getInvoice()).thenReturn(invoice);
    for (FIN_PaymentScheduleDetail psd : psds) {
      when(psd.getInvoicePaymentSchedule()).thenReturn(schedule);
    }
    return schedule;
  }

  private Remittance remittanceLine(String id, String documentNo) {
    Remittance remittance = mock(Remittance.class);
    when(remittance.getId()).thenReturn(id);
    when(remittance.getDocumentNo()).thenReturn(documentNo);
    RemittanceLine line = mock(RemittanceLine.class);
    when(line.getRemittance()).thenReturn(remittance);
    when(lineCriteria.uniqueResult()).thenReturn(line);
    return remittance;
  }

  private static JSONObject error(NeoResponse response) throws Exception {
    return response.getBody().getJSONObject("error");
  }

  private static void assertAmount(String expected, Object actual) {
    assertEquals(0, new BigDecimal(expected).compareTo(new BigDecimal(String.valueOf(actual))),
        "expected " + expected + " but was " + actual);
  }

  // ─── payableAmount ─────────────────────────────────────────────────────────

  @Test
  void payableAmountSumsOnlyTheFreeDetails() {
    FIN_PaymentSchedule schedule = schedule(2L,
        psd("40.00", null),
        psd("15.50", null),
        psd("60.00", payment("R1", REM_SENT)),
        psd("10.00", payment("P1", "RPR")));

    assertAmount("55.50", InvoiceRemittance.payableAmount(schedule));
  }

  @Test
  void payableAmountIsZeroForANullInstallmentOrANullDetailList() {
    FIN_PaymentSchedule noList = mock(FIN_PaymentSchedule.class);
    when(noList.getFINPaymentScheduleDetailInvoicePaymentScheduleList()).thenReturn(null);

    assertEquals(BigDecimal.ZERO, InvoiceRemittance.payableAmount(null));
    assertEquals(BigDecimal.ZERO, InvoiceRemittance.payableAmount(noList));
  }

  @Test
  void payableAmountSkipsADetailWithoutAmount() {
    FIN_PaymentSchedule schedule = schedule(2L, psd(null, null), psd("12.34", null));

    assertAmount("12.34", InvoiceRemittance.payableAmount(schedule));
  }

  @Test
  void payableAmountCountsTheDraftOwnLinkButNotOtherPayments() {
    FIN_PaymentSchedule schedule = schedule(2L,
        psd("30.00", null),
        psd("20.00", payment("DRAFT-1", "RPAP")),
        psd("50.00", payment("R1", REM_SENT)));

    assertAmount("50.00", InvoiceRemittance.payableAmount(schedule, "DRAFT-1"));
    assertAmount("30.00", InvoiceRemittance.payableAmount(schedule, "OTHER"));
    assertAmount("30.00", InvoiceRemittance.payableAmount(schedule, " "));
  }

  @Test
  void payableAmountKeepsFullPrecisionAndSignOfNegativeAmounts() {
    FIN_PaymentSchedule schedule = schedule(2L, psd("-10.125", null), psd("-0.0005", null));

    BigDecimal payable = InvoiceRemittance.payableAmount(schedule);

    assertEquals(new BigDecimal("-10.1255"), payable);
  }

  // ─── remittedAmount ────────────────────────────────────────────────────────

  @Test
  void remittedAmountOfAnInstallmentCountsOnlyRemSentPayments() {
    FIN_PaymentSchedule schedule = schedule(2L,
        psd("60.00", payment("R1", REM_SENT)),
        psd("5.25", payment("R2", REM_SENT)),
        psd("10.00", payment("P1", "RPR")),
        psd("11.00", payment("P2", "PPM")),
        psd("12.00", payment("P3", "RDNC")),
        psd("13.00", payment("P4", "REM_CANCEL")),
        psd("40.00", null));

    assertAmount("65.25", InvoiceRemittance.remittedAmount(schedule));
  }

  @Test
  void remittedAmountOfAnInstallmentIsZeroWhenNothingIsRemitted() {
    assertEquals(BigDecimal.ZERO, InvoiceRemittance.remittedAmount((FIN_PaymentSchedule) null));
    assertEquals(BigDecimal.ZERO,
        InvoiceRemittance.remittedAmount(schedule(2L, psd("40.00", null))));
  }

  @Test
  void remittedAmountOfAnInvoiceSumsItsInstallments() {
    FIN_PaymentSchedule first = schedule(2L, psd("60.00", payment("R1", REM_SENT)),
        psd("40.00", null));
    FIN_PaymentSchedule second = schedule(2L, psd("-0.333", payment("R2", REM_SENT)),
        psd("100.00", payment("P1", "RPR")));
    Invoice invoice = mock(Invoice.class);
    when(invoice.getFINPaymentScheduleList()).thenReturn(List.of(first, second));

    assertEquals(new BigDecimal("59.667"), InvoiceRemittance.remittedAmount(invoice));
  }

  @Test
  void remittedAmountOfAnInvoiceIsZeroForANullInvoiceOrNoInstallments() {
    Invoice noSchedules = mock(Invoice.class);
    when(noSchedules.getFINPaymentScheduleList()).thenReturn(null);

    assertEquals(BigDecimal.ZERO, InvoiceRemittance.remittedAmount((Invoice) null));
    assertEquals(BigDecimal.ZERO, InvoiceRemittance.remittedAmount(noSchedules));
  }

  // ─── isRemitted / remittanceOf ─────────────────────────────────────────────

  @Test
  void isRemittedOnlyForRemSent() {
    assertTrue(InvoiceRemittance.isRemitted(payment("R1", REM_SENT)));
    assertFalse(InvoiceRemittance.isRemitted(payment("P1", "RPR")));
    assertFalse(InvoiceRemittance.isRemitted(payment("P2", "REM_CANCEL")));
    assertFalse(InvoiceRemittance.isRemitted(null));
  }

  @Test
  void remittanceOfIsNullSafeAndDoesNotQuery() {
    FIN_Payment noId = mock(FIN_Payment.class);

    assertNull(InvoiceRemittance.remittanceOf(null));
    assertNull(InvoiceRemittance.remittanceOf(noId));
    verify(dal, never()).createCriteria(RemittanceLine.class);
  }

  @Test
  void remittanceOfReturnsTheLineRemittanceWhateverThePaymentStatus() {
    Remittance remittance = remittanceLine("REM-1", "REM-0042");

    assertSame(remittance, InvoiceRemittance.remittanceOf(payment("P1", "RPR")));
    verify(lineCriteria).setMaxResults(1);
    contextStatic.verify(() -> OBContext.setAdminMode(true));
    contextStatic.verify(OBContext::restorePreviousMode);
  }

  @Test
  void remittanceOfIsNullWhenNoLinePointsAtThePayment() {
    when(lineCriteria.uniqueResult()).thenReturn(null);

    assertNull(InvoiceRemittance.remittanceOf(payment("P1", "RPR")));
    contextStatic.verify(OBContext::restorePreviousMode);
  }

  // ─── installmentRefusal ────────────────────────────────────────────────────

  @Test
  void installmentRefusalLetsAnythingThroughWhenNothingIsRemitted() {
    FIN_PaymentSchedule schedule = schedule(2L, psd("40.00", null),
        psd("60.00", payment("P1", "RPR")));

    assertNull(InvoiceRemittance.installmentRefusal(null, new BigDecimal("999"), null));
    // A genuine overpayment keeps its credit handling: not refused here.
    assertNull(InvoiceRemittance.installmentRefusal(schedule, new BigDecimal("500.00"), null));
  }

  @Test
  void installmentRefusalAcceptsFundsUpToThePayablePart() {
    FIN_PaymentSchedule schedule = schedule(2L, psd("60.00", payment("R1", REM_SENT)),
        psd("40.00", null));

    assertNull(InvoiceRemittance.installmentRefusal(schedule, new BigDecimal("40.00"), null));
    assertNull(InvoiceRemittance.installmentRefusal(schedule, new BigDecimal("39.99"), null));
    assertNull(InvoiceRemittance.installmentRefusal(schedule, null, null));
  }

  @Test
  void installmentRefusalRefusesFundsOverThePayablePartWithTheRemittanceDetails()
      throws Exception {
    FIN_PaymentSchedule schedule = schedule(2L, psd("60.00", payment("R1", REM_SENT)),
        psd("40.00", null));
    remittanceLine("REM-1", "REM-0042");

    NeoResponse response =
        InvoiceRemittance.installmentRefusal(schedule, new BigDecimal("40.01"), null);

    assertNotNull(response);
    assertEquals(422, response.getHttpStatus());
    JSONObject error = error(response);
    assertEquals(InvoiceRemittance.MSG_INVOICE_IN_REMITTANCE,
        error.getJSONArray("messageKeys").getString(0));
    assertEquals("REM-1", error.getString(InvoiceRemittance.KEY_REMITTANCE_ID));
    assertEquals("REM-0042", error.getString(InvoiceRemittance.KEY_REMITTANCE_DOCUMENT_NO));
    assertAmount("40.00", error.get(InvoiceRemittance.KEY_PAYABLE_AMOUNT));
    assertEquals("EUR", error.getString(InvoiceRemittance.KEY_CURRENCY));
    assertAmount("60.00", error.get(InvoiceRemittance.KEY_REMITTED_AMOUNT));
    String message = error.getString("message");
    assertTrue(message.contains("REM-0042") && message.contains("40.00"), message);
  }

  @Test
  void installmentRefusalRefusesAFullyRemittedInstallmentWhateverTheAmount() throws Exception {
    FIN_PaymentSchedule schedule = schedule(2L, psd("100.00", payment("R1", REM_SENT)));
    remittanceLine("REM-1", "REM-0042");

    NeoResponse zero = InvoiceRemittance.installmentRefusal(schedule, BigDecimal.ZERO, null);
    NeoResponse small = InvoiceRemittance.installmentRefusal(schedule, new BigDecimal("0.01"), null);

    for (NeoResponse response : List.of(zero, small)) {
      assertEquals(422, response.getHttpStatus());
      assertAmount("0", error(response).get(InvoiceRemittance.KEY_PAYABLE_AMOUNT));
      assertAmount("100.00", error(response).get(InvoiceRemittance.KEY_REMITTED_AMOUNT));
    }
  }

  @Test
  void installmentRefusalCountsTheDraftOwnLinkAsPayable() {
    FIN_PaymentSchedule schedule = schedule(2L,
        psd("60.00", payment("R1", REM_SENT)),
        psd("40.00", payment("DRAFT-1", "RPAP")));

    assertNull(InvoiceRemittance.installmentRefusal(schedule, new BigDecimal("40.00"), "DRAFT-1"));
    assertNotNull(InvoiceRemittance.installmentRefusal(schedule, new BigDecimal("40.00"), null));
  }

  @Test
  void installmentRefusalComparesAbsoluteValuesForNegativeInstallments() throws Exception {
    // A credit memo installment: amounts are negative, the funds may come either way.
    FIN_PaymentSchedule schedule = schedule(2L, psd("-60.00", payment("R1", REM_SENT)),
        psd("-40.00", null));
    remittanceLine("REM-1", "REM-0042");

    assertNull(InvoiceRemittance.installmentRefusal(schedule, new BigDecimal("-40.00"), null));
    assertNull(InvoiceRemittance.installmentRefusal(schedule, new BigDecimal("40.00"), null));
    NeoResponse refused =
        InvoiceRemittance.installmentRefusal(schedule, new BigDecimal("-40.01"), null);
    assertAmount("40.00", error(refused).get(InvoiceRemittance.KEY_PAYABLE_AMOUNT));
    assertAmount("60.00", error(refused).get(InvoiceRemittance.KEY_REMITTED_AMOUNT));
  }

  @Test
  void installmentRefusalFormatsThePayableAmountAtTheInvoiceCurrencyPrecision()
      throws Exception {
    FIN_PaymentSchedule jpy = schedule(0L, psd("600", payment("R1", REM_SENT)),
        psd("399.6", null));
    FIN_PaymentSchedule noPrecision = schedule(null, psd("60", payment("R1", REM_SENT)),
        psd("12.345", null));
    remittanceLine("REM-1", "REM-0042");

    String jpyMessage = error(InvoiceRemittance.installmentRefusal(jpy, new BigDecimal("1000"),
        null)).getString("message");
    String defaultMessage = error(InvoiceRemittance.installmentRefusal(noPrecision,
        new BigDecimal("100"), null)).getString("message");

    // 399.6 at precision 0 rounds HALF_UP to 400; no precision falls back to 2 decimals.
    assertTrue(jpyMessage.contains(" 400"), jpyMessage);
    assertFalse(jpyMessage.contains("399"), jpyMessage);
    assertTrue(defaultMessage.contains("12.35"), defaultMessage);
  }

  @Test
  void installmentRefusalNamesThePaymentWhenNoRemittanceLinePointsAtIt() throws Exception {
    FIN_PaymentSchedule schedule = schedule(2L, psd("100.00", payment("R1", REM_SENT)));
    when(lineCriteria.uniqueResult()).thenReturn(null);

    JSONObject error = error(InvoiceRemittance.installmentRefusal(schedule, BigDecimal.ONE, null));

    assertTrue(error.isNull(InvoiceRemittance.KEY_REMITTANCE_ID));
    assertEquals("PAY-R1", error.getString(InvoiceRemittance.KEY_REMITTANCE_DOCUMENT_NO));
  }

  // ─── assertDetailPayable ───────────────────────────────────────────────────

  @Test
  void assertDetailPayableAcceptsAFreeOrNullDetail() {
    FIN_PaymentScheduleDetail free = psd("40.00", null);

    assertDoesNotThrow(() -> InvoiceRemittance.assertDetailPayable(null));
    assertDoesNotThrow(() -> InvoiceRemittance.assertDetailPayable(free));
  }

  @Test
  void assertDetailPayableRefusesADetailTakenByAnOrdinaryPayment() {
    FIN_Payment taker = payment("P1", "RPR");
    when(taker.getDocumentNo()).thenReturn("PAY-0007");
    FIN_PaymentScheduleDetail paid = psd("40.00", taker);
    schedule(2L, paid);

    MessageKeyedException thrown = assertThrows(MessageKeyedException.class,
        () -> InvoiceRemittance.assertDetailPayable(paid));

    assertFalse(thrown instanceof NeoRefusalException);
    assertEquals(InvoiceRemittance.MSG_INSTALLMENT_ALREADY_PAID, thrown.getMessageKey());
    assertTrue(thrown.getMessage().contains("PAY-0007"), thrown.getMessage());
  }

  @Test
  void assertDetailPayableRefusesARemittedDetailWithAKeyedMessage() throws Exception {
    FIN_PaymentScheduleDetail remitted = psd("60.00", payment("R1", REM_SENT));
    schedule(2L, remitted, psd("40.00", null));
    remittanceLine("REM-1", "REM-0042");

    NeoRefusalException thrown = assertThrows(NeoRefusalException.class,
        () -> InvoiceRemittance.assertDetailPayable(remitted));

    assertEquals(InvoiceRemittance.MSG_INVOICE_IN_REMITTANCE, thrown.getMessageKey());
    assertTrue(thrown.getMessage().contains("REM-0042"), thrown.getMessage());
    assertTrue(thrown.getMessage().contains("40.00"), thrown.getMessage());
    // The same 422 answer as registerPayment, extra keys included.
    assertEquals(422, thrown.getResponse().getHttpStatus());
    assertEquals("REM-0042", thrown.getResponse().getBody().getJSONObject("error")
        .getString(InvoiceRemittance.KEY_REMITTANCE_DOCUMENT_NO));
  }

  // ─── paymentRefusal ────────────────────────────────────────────────────────

  @Test
  void paymentRefusalLetsANonRemittedPaymentThrough() {
    assertNull(InvoiceRemittance.paymentRefusal(null));
    for (String status : List.of("RPAP", "RPR", "PPM", "RDNC", "REM_CANCEL")) {
      assertNull(InvoiceRemittance.paymentRefusal(payment("P1", status)), status);
    }
    verify(dal, never()).createCriteria(RemittanceLine.class);
  }

  @Test
  void paymentRefusalRefusesARemittedPaymentNamingTheRemittance() throws Exception {
    remittanceLine("REM-1", "REM-0042");

    NeoResponse response = InvoiceRemittance.paymentRefusal(payment("R1", REM_SENT));

    assertEquals(422, response.getHttpStatus());
    JSONObject error = error(response);
    assertEquals(InvoiceRemittance.MSG_PAYMENT_IN_REMITTANCE,
        error.getJSONArray("messageKeys").getString(0));
    assertEquals("REM-1", error.getString(InvoiceRemittance.KEY_REMITTANCE_ID));
    assertEquals("REM-0042", error.getString(InvoiceRemittance.KEY_REMITTANCE_DOCUMENT_NO));
    assertFalse(error.has(InvoiceRemittance.KEY_PAYABLE_AMOUNT));
    assertFalse(error.has(InvoiceRemittance.KEY_REMITTED_AMOUNT));
    assertTrue(error.getString("message").contains("REM-0042"), error.getString("message"));
  }

  @Test
  void paymentRefusalFallsBackToThePaymentDocumentNo() throws Exception {
    when(lineCriteria.uniqueResult()).thenReturn(null);

    JSONObject error = error(InvoiceRemittance.paymentRefusal(payment("R1", REM_SENT)));

    assertTrue(error.isNull(InvoiceRemittance.KEY_REMITTANCE_ID));
    assertEquals("PAY-R1", error.getString(InvoiceRemittance.KEY_REMITTANCE_DOCUMENT_NO));
  }
}
