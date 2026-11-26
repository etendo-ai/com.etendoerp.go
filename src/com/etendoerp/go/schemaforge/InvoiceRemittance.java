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

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.List;
import java.util.Locale;

import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONException;
import org.codehaus.jettison.json.JSONObject;
import org.hibernate.criterion.Restrictions;
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
import org.openbravo.module.remittance.utility.RemittanceStatusUtil;

import com.etendoerp.go.common.NeoCurrencyFormatConfig;

/**
 * ETP-6247 — what a confirmed remittance means on the invoice side, in one place.
 *
 * <p><b>The Classic model.</b> Confirming a remittance makes Classic REM create one
 * {@link FIN_Payment} per due, in status {@value #STATUS_REMITTED} ("Remitted"), and link the
 * invoice installment's {@link FIN_PaymentScheduleDetail} to it. A partial remittance splits the
 * detail first: the remitted part is linked, the remainder stays free. The invoice amounts do not
 * move — its outstanding stays full until the remittance is collected (the payment then moves to
 * RPR/PPM and on to RDNC/PWNC). A draft remittance creates no payment, so it never counts here;
 * reactivating the remittance deletes the payments and a return voids them, both of which take the
 * detail out of {@value #STATUS_REMITTED}.
 *
 * <p><b>What follows for GO.</b> The remitted part is still owed, so totals, aging and "pending"
 * filters keep counting it. What it can no longer be is registered as a payment from the invoice:
 * that would collect it twice, once through the remittance and once through the payment. So:
 * <ul>
 *   <li>{@link #payableAmount} is what a payment registered from the invoice may take — the
 *       installment details not linked to any payment (the free ones);</li>
 *   <li>{@link #remittedAmount} is the part linked to a {@value #STATUS_REMITTED} payment;</li>
 *   <li>{@link #installmentRefusal} refuses a registration that would exceed the payable amount of
 *       a partly or fully remitted installment ({@value #MSG_INVOICE_IN_REMITTANCE}), instead of
 *       turning the excess into credit;</li>
 *   <li>{@link #paymentRefusal} refuses to reactivate or remove a {@value #STATUS_REMITTED}
 *       payment ({@value #MSG_PAYMENT_IN_REMITTANCE}): the remittance owns its lifecycle.</li>
 * </ul>
 * The invoice-level total is also stored on {@code C_Invoice.EM_ETGO_Remitted_Amt} (stored computed
 * column, function {@code ETGO_GET_REMITTED_AMT}).
 *
 * <p><b>No feature-flag gate.</b> None of this reads the {@code sepa-remittances} flag
 * ({@link SepaRemittancesFlag}): a {@value #STATUS_REMITTED} payment only exists where a remittance
 * was confirmed, i.e. where the flag was on (or in Classic, which has the same double-collection
 * problem). Gating these guards would only re-open the double collection for a tenant whose flag
 * was turned off after remitting.
 *
 * <p><b>Structure, not identity.</b> Every check reads the payment status and the detail links —
 * generic payment metadata, shared by sales and purchase invoices alike; no spec or entity is
 * named.
 */
public final class InvoiceRemittance {

  private static final Logger log = LogManager.getLogger(InvoiceRemittance.class);

  /** The status Classic REM gives the payment of a confirmed, not yet collected, remittance due. */
  public static final String STATUS_REMITTED = "REM_SENT";

  /** Refusal of a registration over the payable amount of a remitted installment. */
  public static final String MSG_INVOICE_IN_REMITTANCE = "ETGO_InvoiceInRemittance";

  /**
   * The wording of that refusal when nothing is left to register (the whole installment is
   * remitted). Published in {@code messageKeys} after {@value #MSG_INVOICE_IN_REMITTANCE}, which
   * stays first so a client keyed on it keeps working.
   */
  public static final String MSG_INVOICE_FULLY_IN_REMITTANCE = "ETGO_InvoiceFullyInRemittance";

  /** Refusal of a reactivation or removal of a remitted payment. */
  public static final String MSG_PAYMENT_IN_REMITTANCE = "ETGO_PaymentInRemittance";

  /** Refusal of linking an installment detail another (not remitted) payment already takes. */
  public static final String MSG_INSTALLMENT_ALREADY_PAID = "ETGO_InstallmentAlreadyPaid";

  /** Extra keys of the refusal body, so a client can act without parsing the prose. */
  public static final String KEY_REMITTANCE_ID = "remittanceId";
  public static final String KEY_REMITTANCE_DOCUMENT_NO = "remittanceDocumentNo";
  public static final String KEY_PAYABLE_AMOUNT = "payableAmount";
  public static final String KEY_REMITTED_AMOUNT = "remittedAmount";
  /** ISO code of the invoice currency, so a client formats the two amounts itself. */
  public static final String KEY_CURRENCY = "currency";
  /** {@code true} when nothing is left to register: the whole installment is remitted. */
  public static final String KEY_FULLY_REMITTED = "fullyRemitted";

  private static final int SC_UNPROCESSABLE = 422;
  private static final int DEFAULT_PRECISION = 2;
  private static final String STATUS_LABEL_PREFIX = "ETGO_RemittanceStatus_";
  private static final String NBSP = "\u00A0";
  private static final String DEFAULT_THOUSANDS = ".";
  private static final String DEFAULT_DECIMAL = ",";

  private InvoiceRemittance() {
  }

  // ─── amounts ───────────────────────────────────────────────────────────────

  /**
   * What a payment registered from the invoice may still take on this installment: the sum of its
   * details not linked to any payment. Neither a remitted detail nor one an existing payment
   * already takes is payable.
   *
   * <p>Same definition, in SQL, as {@code InvoicePaymentPlanHandler.AMOUNTS_SQL} (the paymentPlan
   * {@code payableAmount}); change both together. {@code RemittanceReturnIntegrationTest} compares
   * them on the same data.
   *
   * @param schedule the invoice installment
   * @return the payable amount, {@code 0} for a {@code null} installment
   */
  public static BigDecimal payableAmount(FIN_PaymentSchedule schedule) {
    return payableAmount(schedule, null);
  }

  /**
   * {@link #payableAmount(FIN_PaymentSchedule)} plus the details already linked to
   * {@code ownPaymentId} — the draft being edited, whose own link is re-applied, not added.
   *
   * @param schedule     the invoice installment
   * @param ownPaymentId the draft being edited, or {@code null}/blank on a create
   * @return the payable amount, {@code 0} for a {@code null} installment
   */
  public static BigDecimal payableAmount(FIN_PaymentSchedule schedule, String ownPaymentId) {
    BigDecimal total = BigDecimal.ZERO;
    for (FIN_PaymentScheduleDetail psd : detailsOf(schedule)) {
      if (psd.getAmount() != null && isPayableBy(psd, ownPaymentId)) {
        total = total.add(psd.getAmount());
      }
    }
    return total;
  }

  /**
   * The part of this installment that sits in a confirmed remittance: the sum of its details linked
   * to a {@value #STATUS_REMITTED} payment.
   *
   * <p>Same definition as {@code InvoicePaymentPlanHandler.AMOUNTS_SQL} (the paymentPlan
   * {@code remittedAmount}) and the {@code ETGO_GET_REMITTED_AMT} function behind the stored
   * column; change them together.
   *
   * @param schedule the invoice installment
   * @return the remitted amount, {@code 0} when nothing is remitted
   */
  public static BigDecimal remittedAmount(FIN_PaymentSchedule schedule) {
    BigDecimal total = BigDecimal.ZERO;
    for (FIN_PaymentScheduleDetail psd : detailsOf(schedule)) {
      if (psd.getAmount() != null && remittedPaymentOf(psd) != null) {
        total = total.add(psd.getAmount());
      }
    }
    return total;
  }

  /**
   * The part of the whole invoice that sits in a confirmed remittance: {@link #remittedAmount} over
   * its installments. The same value {@code C_Invoice.EM_ETGO_Remitted_Amt} stores, but exact
   * inside the current transaction (the stored column refreshes at commit).
   *
   * @param invoice the invoice, may be {@code null}
   * @return the remitted amount, {@code 0} when nothing is remitted
   */
  public static BigDecimal remittedAmount(Invoice invoice) {
    BigDecimal total = BigDecimal.ZERO;
    List<FIN_PaymentSchedule> schedules =
        invoice == null ? null : invoice.getFINPaymentScheduleList();
    if (schedules != null) {
      for (FIN_PaymentSchedule schedule : schedules) {
        total = total.add(remittedAmount(schedule));
      }
    }
    return total;
  }

  /**
   * Whether the payment belongs to a confirmed remittance that was not collected yet.
   *
   * @param payment the payment, may be {@code null}
   * @return {@code true} when its status is {@value #STATUS_REMITTED}
   */
  public static boolean isRemitted(FIN_Payment payment) {
    return payment != null && STATUS_REMITTED.equals(payment.getStatus());
  }

  /**
   * The remittance a payment was created by, whatever its current status (remitted, collected or
   * returned), through {@code REM_RemittanceLine.FIN_Payment_ID}.
   *
   * @param payment the payment, may be {@code null}
   * @return the remittance, or {@code null} when the payment did not come from one
   */
  public static Remittance remittanceOf(FIN_Payment payment) {
    if (payment == null || payment.getId() == null) {
      return null;
    }
    OBContext.setAdminMode(true);
    try {
      OBCriteria<RemittanceLine> criteria =
          OBDal.getInstance().createCriteria(RemittanceLine.class);
      criteria.add(Restrictions.eq(RemittanceLine.PROPERTY_PAYMENT + ".id", payment.getId()));
      criteria.setMaxResults(1);
      RemittanceLine line = (RemittanceLine) criteria.uniqueResult();
      return line == null ? null : line.getRemittance();
    } finally {
      OBContext.restorePreviousMode();
    }
  }

  /**
   * The remittance's status code ({@code REM_REMITTANCE.REMITTANCE_STATUS}: {@code PE}, {@code PR},
   * {@code SB}, {@code CT}, {@code PT}, {@code CP}, {@code PP}, {@code AN}).
   *
   * @param remittance the remittance, may be {@code null}
   * @return the code, or {@code null}
   */
  public static String remittanceStatus(Remittance remittance) {
    Object status = remittance == null ? null
        : remittance.get(RemittanceStatusUtil.PROPERTY_REMITTANCE_STATUS);
    return status == null ? null : status.toString();
  }

  /**
   * The name of a remittance status in the request language, worded as the SPA's remittance window
   * words it ({@code remittanceStatus<Code>}: «Pendiente de envío» for {@code PR}), from the
   * {@code ETGO_RemittanceStatus_<code>} catalog keys of {@link NeoMessageText}. Not the
   * {@code REM_RemittanceStatus} list names: REM ships them in English only («Processed») and they
   * would contradict the window. An unknown code is answered as is.
   *
   * @param status the status code, may be {@code null}
   * @return the label, the code itself when unknown, or {@code null} for {@code null}
   */
  public static String remittanceStatusLabel(String status) {
    if (StringUtils.isBlank(status)) {
      return status;
    }
    String key = STATUS_LABEL_PREFIX + status;
    String label = NeoMessageText.text(key);
    return label == null || key.equals(label) ? status : label;
  }

  /**
   * ETP-6247: the remittance a payment came from — remitted ({@code REM_SENT}), collected or
   * returned alike — with its status code and its name in the request language
   * ({@link #remittanceStatusLabel}), so the list can say «En la remesa N ·
   * Pendiente de envío» and route edit/delete to the remittance
   * (a remitted payment refuses both, see {@link #paymentRefusal}). Absent for a
   * payment registered by hand. Written into an {@code invoicePayments} list item by
   * {@code PaymentRegistrationService.paymentListItem}.
   *
   * @param item    the list item to complete
   * @param payment the listed payment
   * @throws JSONException if the item cannot be written
   */
  static void putRemittance(JSONObject item, FIN_Payment payment) throws JSONException {
    Remittance remittance = remittanceOf(payment);
    if (remittance != null) {
      item.put(KEY_REMITTANCE_ID, remittance.getId());
      item.put(KEY_REMITTANCE_DOCUMENT_NO, remittance.getDocumentNo());
      String status = remittanceStatus(remittance);
      item.put("remittanceStatus", status == null ? JSONObject.NULL : status);
      item.put("remittanceStatusLabel", status == null ? JSONObject.NULL
          : remittanceStatusLabel(status));
    }
  }

  // ─── refusals ──────────────────────────────────────────────────────────────

  /**
   * Refuses a registration of {@code funds} on an installment that is partly or fully remitted when
   * the funds exceed what is payable — the excess would otherwise become credit of the business
   * partner, and the remittance would collect the same amount a second time. A fully remitted
   * installment is refused whatever the amount. An installment with nothing remitted is never
   * refused here: a genuine overpayment there keeps its usual credit/refund handling.
   *
   * @param schedule     the invoice installment, may be {@code null} (nothing to check)
   * @param funds        what the registration applies: cash plus consumed credit
   * @param ownPaymentId the draft being edited, or {@code null}/blank on a create
   * @return a 422 {@value #MSG_INVOICE_IN_REMITTANCE} refusal, or {@code null} to proceed
   */
  public static NeoResponse installmentRefusal(FIN_PaymentSchedule schedule, BigDecimal funds,
      String ownPaymentId) {
    if (schedule == null) {
      return null;
    }
    BigDecimal remitted = remittedAmount(schedule);
    if (remitted.signum() == 0) {
      return null;
    }
    BigDecimal payable = payableAmount(schedule, ownPaymentId).abs();
    BigDecimal requested = funds == null ? BigDecimal.ZERO : funds.abs();
    if (payable.signum() > 0 && requested.compareTo(payable) <= 0) {
      return null;
    }
    return remittedInstallmentResponse(schedule, firstRemittedPayment(schedule), payable,
        remitted.abs());
  }

  /**
   * The same refusal as {@link #installmentRefusal}, as an exception, for a flow that links an
   * installment detail by id: a detail already linked to a payment is not payable.
   *
   * @param psd the detail about to be linked
   * @throws NeoRefusalException the 422 {@value #MSG_INVOICE_IN_REMITTANCE} refusal of
   *     {@link #installmentRefusal} when that payment is a {@value #STATUS_REMITTED} one
   * @throws MessageKeyedException {@value #MSG_INSTALLMENT_ALREADY_PAID} when it is any other
   */
  public static void assertDetailPayable(FIN_PaymentScheduleDetail psd) {
    if (psd == null || psd.getPaymentDetails() == null) {
      return;
    }
    FIN_Payment remitted = remittedPaymentOf(psd);
    if (remitted == null) {
      FIN_Payment taken = psd.getPaymentDetails().getFinPayment();
      throw MessageKeyedException.withArgs(MSG_INSTALLMENT_ALREADY_PAID,
          taken == null ? psd.getId() : StringUtils.defaultString(taken.getDocumentNo()));
    }
    FIN_PaymentSchedule schedule = psd.getInvoicePaymentSchedule();
    throw new NeoRefusalException(remittedInstallmentResponse(schedule, remitted,
        payableAmount(schedule).abs(), remittedAmount(schedule).abs()), MSG_INVOICE_IN_REMITTANCE);
  }

  /**
   * Refuses to reactivate or remove a {@value #STATUS_REMITTED} payment: Classic REM owns it until
   * the remittance is collected, reactivated or returned, and touching it from the payment would
   * leave the remittance pointing at a payment that no longer matches it.
   *
   * @param payment the payment the action is posted to, may be {@code null}
   * @return a 422 {@value #MSG_PAYMENT_IN_REMITTANCE} refusal, or {@code null} to proceed
   */
  public static NeoResponse paymentRefusal(FIN_Payment payment) {
    if (!isRemitted(payment)) {
      return null;
    }
    Remittance remittance = remittanceOf(payment);
    String documentNo = remittanceDocumentNo(remittance, payment);
    NeoResponse response = NeoMessageText.error(SC_UNPROCESSABLE, MSG_PAYMENT_IN_REMITTANCE,
        documentNo);
    putDetails(response, remittance, documentNo, null, null);
    return response;
  }

  /**
   * The 422 {@value #MSG_INVOICE_IN_REMITTANCE} answer. The text names the remittance and, when
   * part of the installment is still free, the amount that can be registered, formatted as money
   * ({@link #formatMoney}); when nothing is free it is the {@value #MSG_INVOICE_FULLY_IN_REMITTANCE}
   * wording, which names no amount. {@code messageKeys} always starts with
   * {@value #MSG_INVOICE_IN_REMITTANCE}. The body adds {@value #KEY_REMITTANCE_ID},
   * {@value #KEY_REMITTANCE_DOCUMENT_NO}, {@value #KEY_PAYABLE_AMOUNT},
   * {@value #KEY_REMITTED_AMOUNT}, {@value #KEY_CURRENCY} and {@value #KEY_FULLY_REMITTED}.
   */
  private static NeoResponse remittedInstallmentResponse(FIN_PaymentSchedule schedule,
      FIN_Payment remittedPayment, BigDecimal payable, BigDecimal remitted) {
    Remittance remittance = remittanceOf(remittedPayment);
    String documentNo = remittanceDocumentNo(remittance, remittedPayment);
    boolean fully = payable.signum() == 0;
    NeoResponse response = fully
        ? NeoMessageText.error(SC_UNPROCESSABLE, MSG_INVOICE_FULLY_IN_REMITTANCE, documentNo)
        : NeoMessageText.error(SC_UNPROCESSABLE, MSG_INVOICE_IN_REMITTANCE, documentNo,
            formatMoney(payable, currencyOf(schedule)));
    putDetails(response, remittance, documentNo, payable, remitted);
    putCurrency(response, currencyOf(schedule));
    putFullyRemitted(response, fully);
    return response;
  }

  // ─── helpers ───────────────────────────────────────────────────────────────

  private static List<FIN_PaymentScheduleDetail> detailsOf(FIN_PaymentSchedule schedule) {
    if (schedule == null) {
      return List.of();
    }
    List<FIN_PaymentScheduleDetail> details =
        schedule.getFINPaymentScheduleDetailInvoicePaymentScheduleList();
    return details == null ? List.of() : details;
  }

  private static boolean isPayableBy(FIN_PaymentScheduleDetail psd, String ownPaymentId) {
    FIN_PaymentDetail detail = psd.getPaymentDetails();
    if (detail == null) {
      return true;
    }
    FIN_Payment payment = detail.getFinPayment();
    return StringUtils.isNotBlank(ownPaymentId) && payment != null
        && ownPaymentId.equals(payment.getId());
  }

  /** The {@value #STATUS_REMITTED} payment a detail is linked to, or {@code null}. */
  private static FIN_Payment remittedPaymentOf(FIN_PaymentScheduleDetail psd) {
    FIN_PaymentDetail detail = psd.getPaymentDetails();
    FIN_Payment payment = detail == null ? null : detail.getFinPayment();
    return isRemitted(payment) ? payment : null;
  }

  private static FIN_Payment firstRemittedPayment(FIN_PaymentSchedule schedule) {
    for (FIN_PaymentScheduleDetail psd : detailsOf(schedule)) {
      FIN_Payment payment = remittedPaymentOf(psd);
      if (payment != null) {
        return payment;
      }
    }
    return null;
  }

  /** The remittance's document number; the payment's when no remittance line points at it. */
  private static String remittanceDocumentNo(Remittance remittance, FIN_Payment payment) {
    if (remittance != null && remittance.getDocumentNo() != null) {
      return remittance.getDocumentNo();
    }
    return payment == null ? "" : StringUtils.defaultString(payment.getDocumentNo());
  }

  /**
   * The amount as money for a message an API or MCP caller reads as is: the currency's precision
   * (half up), the instance-wide thousands and decimal separators the SPA's {@code formatCurrency}
   * also reads ({@link NeoCurrencyFormatConfig}, served at {@code /sws/neo/currency-format}), and
   * the currency symbol on the side {@code C_Currency.IsSymbolRightSide} says — after a no-break
   * space on the right, glued on the left, as the SPA renders it. Falls back to the ISO code when
   * the currency has no symbol (after the amount), and to the bare number without a currency.
   * Total.
   */
  static String formatMoney(BigDecimal amount, Currency currency) {
    int precision = currency == null || currency.getStandardPrecision() == null
        ? DEFAULT_PRECISION
        : currency.getStandardPrecision().intValue();
    String number = groupedNumber(amount == null ? BigDecimal.ZERO : amount, precision);
    String symbol = currency == null ? null : StringUtils.trimToNull(currency.getSymbol());
    if (symbol == null) {
      String isoCode = currency == null ? null : StringUtils.trimToNull(currency.getISOCode());
      return isoCode == null ? number : number + NBSP + isoCode;
    }
    return Boolean.TRUE.equals(currency.isCurrencySymbolAtTheRight())
        ? number + NBSP + symbol
        : symbol + number;
  }

  private static String groupedNumber(BigDecimal amount, int precision) {
    String grouping = DEFAULT_THOUSANDS;
    String decimal = DEFAULT_DECIMAL;
    try {
      NeoCurrencyFormatConfig config = NeoCurrencyFormatConfig.fromRuntime();
      grouping = config.getThousandsSeparator();
      decimal = config.getDecimalSeparator();
    } catch (RuntimeException e) {
      log.debug("No currency format config, using the defaults: {}", e.getMessage());
    }
    DecimalFormatSymbols symbols = new DecimalFormatSymbols(Locale.ROOT);
    symbols.setGroupingSeparator(grouping.charAt(0));
    symbols.setDecimalSeparator(decimal.charAt(0));
    DecimalFormat format = new DecimalFormat(
        precision > 0 ? "#,##0." + "0".repeat(precision) : "#,##0", symbols);
    format.setRoundingMode(RoundingMode.HALF_UP);
    return format.format(amount.setScale(precision, RoundingMode.HALF_UP));
  }

  private static Currency currencyOf(FIN_PaymentSchedule schedule) {
    return schedule == null || schedule.getInvoice() == null
        ? null
        : schedule.getInvoice().getCurrency();
  }

  private static void putCurrency(NeoResponse response, Currency currency) {
    try {
      response.getBody().getJSONObject(NeoProcessService.ERROR).put(KEY_CURRENCY,
          currency == null || currency.getISOCode() == null ? JSONObject.NULL
              : currency.getISOCode());
    } catch (Exception e) {
      log.debug("Could not attach the currency to the refusal: {}", e.getMessage());
    }
  }

  private static void putFullyRemitted(NeoResponse response, boolean fully) {
    try {
      JSONObject error = response.getBody().getJSONObject(NeoProcessService.ERROR);
      error.put(KEY_FULLY_REMITTED, fully);
      if (fully) {
        error.put(NeoProcessService.MESSAGE_KEYS, new JSONArray()
            .put(MSG_INVOICE_IN_REMITTANCE).put(MSG_INVOICE_FULLY_IN_REMITTANCE));
      }
    } catch (Exception e) {
      log.debug("Could not mark the refusal as fully remitted: {}", e.getMessage());
    }
  }

  private static void putDetails(NeoResponse response, Remittance remittance, String documentNo,
      BigDecimal payable, BigDecimal remitted) {
    try {
      JSONObject error = response.getBody().getJSONObject(NeoProcessService.ERROR);
      error.put(KEY_REMITTANCE_ID, remittance == null ? JSONObject.NULL : remittance.getId());
      error.put(KEY_REMITTANCE_DOCUMENT_NO, documentNo);
      if (payable != null) {
        error.put(KEY_PAYABLE_AMOUNT, payable);
        error.put(KEY_REMITTED_AMOUNT, remitted);
      }
    } catch (Exception e) {
      // The message and its key already carry the refusal; the extra keys are a convenience.
      log.debug("Could not attach the remittance details to the refusal: {}", e.getMessage());
    }
  }
}
