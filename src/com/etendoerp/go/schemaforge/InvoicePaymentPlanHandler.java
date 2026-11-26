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
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONObject;
import org.hibernate.query.NativeQuery;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.financialmgmt.payment.FIN_PaymentSchedule;

/**
 * ETP-6247 — the invoice {@code paymentPlan} rows ({@code FIN_Payment_Schedule}) gain two amounts
 * per installment, so the payment plan can show what a confirmed remittance will collect:
 * <ul>
 *   <li>{@value #KEY_REMITTED} — the details linked to a {@code REM_SENT} payment;</li>
 *   <li>{@value #KEY_PAYABLE} — the free details, i.e. what a payment registered from the invoice
 *       may still take (the cap {@link InvoiceRemittance#installmentRefusal} enforces).</li>
 * </ul>
 * The installment's {@code outstandingAmount} is unchanged: the remitted part is still owed until
 * the remittance is collected.
 *
 * <p><b>Why a read decoration and not a column.</b> The payment plan is a per-invoice sub-list of a
 * handful of rows that nobody filters or sorts; both amounts are per-request reads of the detail
 * links. The invoice-level total, which a list may filter on, is the stored computed column
 * {@code C_Invoice.EM_ETGO_Remitted_Amt}. One batched native query per page, the same shape as
 * {@link PaymentScheduleDetailHandler}. A failure is logged at ERROR and leaves the rows without
 * the two keys, which the SPA reads as "nothing remitted": that is the pre-ETP-6247 behaviour, and
 * the service guards still refuse a registration over the payable amount.
 *
 * <p><b>Binding.</b> This class carries the behaviour only; {@link SalesInvoicePaymentPlanHandler}
 * and {@link PurchaseInvoicePaymentPlanHandler} bind it to {@code sales-invoice/paymentPlan} and
 * {@code purchase-invoice/paymentPlan} through {@link NeoExtension} (not repeatable).
 */
public abstract class InvoicePaymentPlanHandler implements NeoHandler {

  private static final Logger log = LogManager.getLogger(InvoicePaymentPlanHandler.class);

  static final String KEY_REMITTED = "remittedAmount";
  static final String KEY_PAYABLE = "payableAmount";

  /**
   * {@code [payable, remitted]} per installment. The SQL form of
   * {@link InvoiceRemittance#payableAmount(FIN_PaymentSchedule)}
   * (details with no payment) and
   * {@link InvoiceRemittance#remittedAmount(FIN_PaymentSchedule)}
   * (details of a {@code REM_SENT} payment), batched for a page; change both together.
   * {@code RemittanceReturnIntegrationTest} compares them on the same data.
   */
  private static final String AMOUNTS_SQL =
      // Every column aliased: Hibernate's native-query alias discovery refuses two unnamed
      // COALESCE columns (NonUniqueDiscoveredSqlAliasException).
      "SELECT ps.fin_payment_schedule_id AS schedule_id,"
      + " COALESCE(SUM(CASE WHEN psd.fin_payment_detail_id IS NULL THEN psd.amount END), 0)"
      + " AS payable,"
      + " COALESCE(SUM(CASE WHEN p.status = :remitted THEN psd.amount END), 0) AS remitted"
      + " FROM fin_payment_schedule ps"
      + " LEFT JOIN fin_payment_scheduledetail psd"
      + "   ON psd.fin_payment_schedule_invoice = ps.fin_payment_schedule_id"
      + " LEFT JOIN fin_payment_detail pd ON pd.fin_payment_detail_id = psd.fin_payment_detail_id"
      + " LEFT JOIN fin_payment p ON p.fin_payment_id = pd.fin_payment_id"
      + " WHERE ps.fin_payment_schedule_id IN (:ids)"
      + " GROUP BY ps.fin_payment_schedule_id";

  @Override
  public NeoResponse handle(NeoContext context) {
    return null;
  }

  @Override
  public NeoResponse afterHandle(NeoContext context) {
    NeoResponse previous = context.getPreviousResult();
    try {
      JSONArray rows = NeoHandlerUtils.extractGetDataArray(context);
      if (rows == null || previous == null) {
        return null;
      }
      List<String> ids = NeoHandlerUtils.collectIds(rows);
      if (ids.isEmpty()) {
        return null;
      }
      Map<String, BigDecimal[]> amounts = fetchAmounts(ids);
      for (int i = 0; i < rows.length(); i++) {
        JSONObject row = rows.getJSONObject(i);
        BigDecimal[] pair = amounts.get(row.optString("id", null));
        if (pair != null) {
          row.put(KEY_PAYABLE, pair[0]);
          row.put(KEY_REMITTED, pair[1]);
        }
      }
      return NeoResponse.ok(previous.getBody());
    } catch (Exception e) {
      log.error("Could not add the remitted/payable amounts to the payment plan of {}",
          context.getRecordId(), e);
      return previous;
    }
  }

  /** Installment id to {@code [payable, remitted]}. */
  Map<String, BigDecimal[]> fetchAmounts(List<String> ids) {
    Map<String, BigDecimal[]> result = new HashMap<>();
    OBContext.setAdminMode(true);
    try {
      @SuppressWarnings("unchecked")
      NativeQuery<Object[]> query = OBDal.getInstance().getSession()
          .createNativeQuery(AMOUNTS_SQL);
      query.setParameter("remitted", InvoiceRemittance.STATUS_REMITTED);
      query.setParameterList("ids", ids);
      for (Object[] row : query.list()) {
        result.put((String) row[0], new BigDecimal[] { toDecimal(row[1]), toDecimal(row[2]) });
      }
    } finally {
      OBContext.restorePreviousMode();
    }
    return result;
  }

  private static BigDecimal toDecimal(Object value) {
    if (value instanceof BigDecimal) {
      return (BigDecimal) value;
    }
    return value == null ? BigDecimal.ZERO : new BigDecimal(value.toString());
  }
}
