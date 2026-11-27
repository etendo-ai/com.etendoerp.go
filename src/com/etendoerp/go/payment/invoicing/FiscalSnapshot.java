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

import java.util.Iterator;

import org.apache.commons.lang3.StringUtils;
import org.codehaus.jettison.json.JSONException;
import org.codehaus.jettison.json.JSONObject;

import com.etendoerp.go.common.SpanishTaxIdValidator;

/**
 * The fiscal data a payment is invoiced to (ETP-6322): what the buyer typed at the Stripe
 * checkout.
 *
 * <p>The checkout is the only source: it is what Stripe computed the VAT from, it has the address
 * (the signup asks for none) and, where Stripe supports it, a tax id the buyer had to give
 * ({@code tax_id_collection[required]=if_supported}). The signup's data are optional and often
 * left for later, so they never complete or replace the checkout's.
 *
 * <p>A Spanish tax id ({@code es_cif}, or {@code es_vat} without its {@code ES} prefix) is checked
 * with {@link SpanishTaxIdValidator}, check digit included, because Stripe only checks its shape.
 * One that fails is left out ({@code taxId} and {@code taxIdType} null) rather than printed on an
 * invoice; {@code stripe.billingDetails} still has it as typed.
 *
 * <p>The tenant's company is returned, unchanged, only for a payment whose checkout told nothing
 * (one recorded before the checkout data were kept).
 */
final class FiscalSnapshot {

  private static final String NAME = "name";
  private static final String LEGAL_NAME = "legalName";
  private static final String TAX_ID = "taxId";
  private static final String TAX_ID_TYPE = "taxIdType";
  private static final String COUNTRY = "country";
  private static final String[] ADDRESS = { "address1", "address2", "postalCode", "city",
      "region", "countryCode" };

  /** Stripe's Spanish tax id types: a bare NIF, and the EU VAT number with its ES prefix. */
  private static final String ES_CIF = "es_cif";
  private static final String ES_VAT = "es_vat";
  private static final String ES_VAT_PREFIX = "ES";

  private FiscalSnapshot() {
  }

  /**
   * The snapshot of a payment.
   *
   * @param checkout    what the buyer typed at the checkout ({@link
   *     StripeInvoiceTaxes#billingDetailsOf}); null for a payment recorded before it was kept
   * @param environment the tenant's company ({@link PaymentLedgerStore#fiscalData}), used only
   *     when the checkout told nothing; null while the environment does not exist
   * @param checkoutCountryName the name of the checkout's country, or null
   * @return {@code {name, legalName, taxIdType, taxId, address1, address2, postalCode, city,
   *     region, countryCode, country}}; or the tenant's company alone when the checkout told
   *     nothing, or null when neither is known
   */
  static JSONObject merge(JSONObject checkout, JSONObject environment, String checkoutCountryName)
      throws JSONException {
    if (isEmpty(checkout)) {
      return environment;
    }
    Object name = value(checkout, NAME);
    boolean validTaxId = isValidTaxId(checkout);
    JSONObject result = new JSONObject()
        .put(NAME, name)
        .put(LEGAL_NAME, name)
        .put(TAX_ID_TYPE, validTaxId ? value(checkout, TAX_ID_TYPE) : JSONObject.NULL)
        .put(TAX_ID, validTaxId ? value(checkout, TAX_ID) : JSONObject.NULL);
    for (String key : ADDRESS) {
      result.put(key, value(checkout, key));
    }
    return result.put(COUNTRY, StringUtils.isBlank(checkoutCountryName) ? JSONObject.NULL
        : checkoutCountryName);
  }

  /** A tax id that is present and, when Spanish, passes the check digit. */
  static boolean isValidTaxId(JSONObject checkout) {
    if (!hasText(checkout, TAX_ID)) {
      return false;
    }
    String type = checkout.optString(TAX_ID_TYPE, "");
    String taxId = SpanishTaxIdValidator.normalize(checkout.optString(TAX_ID));
    if (ES_VAT.equals(type) && taxId.startsWith(ES_VAT_PREFIX)) {
      taxId = taxId.substring(ES_VAT_PREFIX.length());
    } else if (!ES_CIF.equals(type)) {
      return true;
    }
    return !taxId.isEmpty()
        && SpanishTaxIdValidator.validate(taxId) == SpanishTaxIdValidator.Result.VALID;
  }

  private static boolean isEmpty(JSONObject json) {
    if (json == null) {
      return true;
    }
    for (Iterator<?> keys = json.keys(); keys.hasNext();) {
      if (hasText(json, String.valueOf(keys.next()))) {
        return false;
      }
    }
    return true;
  }

  private static boolean hasText(JSONObject json, String key) {
    return json != null && !json.isNull(key) && StringUtils.isNotBlank(json.optString(key, ""));
  }

  private static Object value(JSONObject json, String key) {
    return hasText(json, key) ? json.optString(key).trim() : JSONObject.NULL;
  }
}
