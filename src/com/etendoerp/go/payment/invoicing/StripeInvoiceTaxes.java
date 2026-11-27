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

package com.etendoerp.go.payment.invoicing;

import java.math.BigDecimal;
import java.math.RoundingMode;

import org.apache.commons.lang3.StringUtils;
import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONException;
import org.codehaus.jettison.json.JSONObject;

import com.etendoerp.go.payment.StripeJson;

/**
 * The fiscal side of a Stripe invoice (ETP-6322): the tax Stripe Tax charged, its breakdown, and
 * the billing details the buyer typed at the checkout. Read by {@link StripeInvoicePayment}, in the
 * current and the older Stripe API shapes.
 */
final class StripeInvoiceTaxes {

  private static final String AMOUNT = "amount";
  private static final String TAXABLE_AMOUNT = "taxable_amount";
  private static final String TAX_ID = "taxId";
  private static final int RATE_SCALE = 2;

  private StripeInvoiceTaxes() {
  }

  /** {@code total_taxes} on current API versions, {@code tax} on older ones; null if neither. */
  static BigDecimal taxOf(JSONObject invoice, int exponent) {
    JSONArray taxes = invoice.optJSONArray("total_taxes");
    if (taxes != null) {
      long sum = 0L;
      for (int i = 0; i < taxes.length(); i++) {
        JSONObject tax = taxes.optJSONObject(i);
        sum += tax == null ? 0L : tax.optLong(AMOUNT, 0L);
      }
      return BigDecimal.valueOf(sum).movePointLeft(exponent);
    }
    return StripeInvoicePayment.major(invoice, "tax", exponent);
  }

  /**
   * The tax breakdown: {@code [{taxableAmount, amount, rate, reason}]}, one entry per tax Stripe
   * applied, from {@code total_taxes} or, on older API versions, {@code total_tax_amounts}.
   * {@code rate} is the percentage, worked out from the two amounts because the event only names
   * the tax rate by id; {@code reason} is Stripe's {@code taxability_reason}
   * ({@code standard_rated}, {@code reverse_charge}, {@code not_collecting}...). Empty when Stripe
   * did not compute tax.
   */
  static JSONArray taxesOf(JSONObject invoice, int exponent) throws JSONException {
    JSONArray source = invoice.optJSONArray("total_taxes");
    if (source == null) {
      source = invoice.optJSONArray("total_tax_amounts");
    }
    JSONArray result = new JSONArray();
    for (int i = 0; source != null && i < source.length(); i++) {
      JSONObject tax = source.optJSONObject(i);
      if (tax == null) {
        continue;
      }
      BigDecimal taxable = StripeInvoicePayment.major(tax, TAXABLE_AMOUNT, exponent);
      BigDecimal amount = StripeInvoicePayment.major(tax, AMOUNT, exponent);
      result.put(new JSONObject()
          .put("taxableAmount", plainOrNull(taxable))
          .put(AMOUNT, plainOrNull(amount))
          .put("rate", plainOrNull(rateOf(amount, taxable)))
          .put("reason", textOrNull(StripeJson.text(tax, "taxability_reason"))));
    }
    return result;
  }

  /** The percentage {@code amount} is of {@code taxable}, or null when it cannot be told. */
  static BigDecimal rateOf(BigDecimal amount, BigDecimal taxable) {
    if (amount == null || taxable == null || taxable.signum() == 0) {
      return null;
    }
    return amount.multiply(BigDecimal.valueOf(100))
        .divide(taxable, RATE_SCALE, RoundingMode.HALF_UP);
  }

  /**
   * What the buyer typed at the checkout, as Stripe copied it onto the invoice:
   * {@code {name, taxIdType, taxId, address1, address2, postalCode, city, region, countryCode}},
   * blank fields as {@code null}. The tax id is the first one the buyer gave; its type is
   * Stripe's ({@code es_cif}, {@code eu_vat}...).
   */
  static JSONObject billingDetailsOf(JSONObject invoice) throws JSONException {
    JSONObject address = invoice.optJSONObject("customer_address");
    JSONArray taxIds = invoice.optJSONArray("customer_tax_ids");
    JSONObject taxId = taxIds == null || taxIds.length() == 0 ? null : taxIds.optJSONObject(0);
    return new JSONObject()
        .put("name", textOrNull(StripeJson.text(invoice, "customer_name")))
        .put("taxIdType", textOrNull(taxId == null ? null : StripeJson.text(taxId, "type")))
        .put(TAX_ID, textOrNull(taxId == null ? null : StripeJson.text(taxId, "value")))
        .put("address1", textOrNull(address == null ? null : StripeJson.text(address, "line1")))
        .put("address2", textOrNull(address == null ? null : StripeJson.text(address, "line2")))
        .put("postalCode", textOrNull(address == null ? null
            : StripeJson.text(address, "postal_code")))
        .put("city", textOrNull(address == null ? null : StripeJson.text(address, "city")))
        .put("region", textOrNull(address == null ? null : StripeJson.text(address, "state")))
        .put("countryCode", textOrNull(address == null ? null
            : StripeJson.text(address, "country")));
  }

  private static Object plainOrNull(BigDecimal value) {
    return value == null ? JSONObject.NULL : value.toPlainString();
  }

  private static Object textOrNull(String value) {
    return StringUtils.isBlank(value) ? JSONObject.NULL : value.trim();
  }
}
