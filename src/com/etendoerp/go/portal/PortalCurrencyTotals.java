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

package com.etendoerp.go.portal;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.codehaus.jettison.json.JSONException;
import org.codehaus.jettison.json.JSONObject;
import org.hibernate.query.Query;
import org.openbravo.dal.service.OBDal;
import org.openbravo.erpCommon.utility.OBCurrencyUtils;
import org.openbravo.financial.FinancialUtils;
import org.openbravo.model.ad.system.Client;
import org.openbravo.model.common.currency.ConversionRate;
import org.openbravo.model.common.currency.Currency;
import org.openbravo.model.common.enterprise.Organization;

import com.etendoerp.go.schemaforge.NeoCurrencyTotals;

/**
 * One total in the issuing company's currency for amounts spread over several currencies
 * (ETP-6209 QA CP-47): the portal list's totals and the home's "Tu saldo".
 *
 * <p><b>The app's rule, not a new one.</b> The folding is {@link NeoCurrencyTotals}, the same class
 * behind the app's Financial Accounts sidebar and payment list summary: today's general
 * {@code C_Conversion_Rate} through core {@link FinancialUtils#getConversionRate}, each foreign
 * subtotal converted and rounded to the target's precision, {@code approximate} as soon as one
 * foreign amount was converted. Where the app leaves a currency without a rate out of its total and
 * names it, the portal answers no converted total at all ({@code null}): the page then shows the
 * per-currency figures it already receives, so the customer never reads a total that silently
 * omits money.
 *
 * <p><b>Only the scope differs.</b> The app resolves the organization and client from the user's
 * session; a portal request has none (it runs as SYSTEM), so they come from the validated link:
 * the target is the currency of the link's organization ({@link OBCurrencyUtils#getOrgCurrency},
 * organization → legal entity → client), falling back to the tenant's base currency, and rates are
 * searched from that organization up to {@code 0}, for the link's client plus the system rates.
 *
 * <p>Answers {@code {amount, currency, approximate}}; {@code null} when there is nothing to total
 * or a rate is missing.
 */
final class PortalCurrencyTotals {

  private static final Logger log = LogManager.getLogger(PortalCurrencyTotals.class);

  static final String FIELD_AMOUNT = "amount";
  static final String FIELD_CURRENCY = "currency";
  static final String FIELD_APPROXIMATE = "approximate";

  private final NeoCurrencyTotals.TargetCurrency target;
  private final NeoCurrencyTotals totals;
  private final Map<String, String> currencyIds = new HashMap<>();

  PortalCurrencyTotals(NeoCurrencyTotals.TargetCurrency target,
      NeoCurrencyTotals.RateLookup rates) {
    this.target = target;
    this.totals = new NeoCurrencyTotals(rates);
  }

  /**
   * The converter of a session: its company currency and its organization's rates. Admin mode by
   * the caller (every portal request runs in it).
   *
   * @param session the validated session
   * @return the converter
   */
  static PortalCurrencyTotals of(PortalSession session) {
    Organization organization = StringUtils.isBlank(session.getOrganizationId()) ? null
        : OBDal.getInstance().get(Organization.class, session.getOrganizationId());
    Client client = OBDal.getInstance().get(Client.class, session.getClientId());
    return new PortalCurrencyTotals(targetOf(organization, client),
        (from, to) -> rate(from, to, organization, client));
  }

  /**
   * Folds per-currency sums into the company currency.
   *
   * @param byIso the sums, keyed by ISO code (the portal's aggregate queries group by it)
   * @return the converted total as JSON, or {@link JSONObject#NULL} when {@code byIso} is empty
   * @throws JSONException never in practice
   */
  Object convert(Map<String, BigDecimal> byIso) throws JSONException {
    if (byIso.isEmpty() || target == null && byIso.size() > 1) {
      // Without a company currency, two currencies have no total: never a mixed-currency sum.
      return JSONObject.NULL;
    }
    List<NeoCurrencyTotals.Subtotal> subtotals = new ArrayList<>();
    for (Map.Entry<String, BigDecimal> entry : byIso.entrySet()) {
      subtotals.add(new NeoCurrencyTotals.Subtotal(currencyId(entry.getKey()), entry.getKey(),
          entry.getValue()));
    }
    NeoCurrencyTotals.Converted converted = totals.convert(subtotals, target);
    if (!converted.missingRateCurrencies.isEmpty()) {
      // A total that leaves a currency out is not the total: the page falls back to the
      // per-currency figures it already has (balances[] / totals[] / paidThisYear[]).
      log.info("Portal: no conversion rate for {} into {}; no converted total",
          converted.missingRateCurrencies, converted.iso);
      return JSONObject.NULL;
    }
    JSONObject json = new JSONObject();
    json.put(FIELD_AMOUNT, converted.total);
    json.put(FIELD_CURRENCY, StringUtils.defaultString(converted.iso));
    json.put(FIELD_APPROXIMATE, converted.approximate);
    return json;
  }

  private String currencyId(String iso) {
    return currencyIds.computeIfAbsent(StringUtils.defaultString(iso), code -> {
      Query<String> query = OBDal.getInstance().getSession().createQuery(
          "select c.id from Currency c where c.iSOCode = :iso", String.class);
      query.setParameter("iso", code);
      query.setMaxResults(1);
      return query.uniqueResult();
    });
  }

  private static NeoCurrencyTotals.TargetCurrency targetOf(Organization organization,
      Client client) {
    String currencyId = organization == null ? null
        : OBCurrencyUtils.getOrgCurrency(organization.getId());
    Currency currency = currencyId == null ? null
        : OBDal.getInstance().get(Currency.class, currencyId);
    if (currency == null && client != null) {
      currency = client.getCurrency();
    }
    if (currency == null) {
      // No company currency anywhere: one currency is its own sum; several have none (convert).
      return null;
    }
    Long precision = currency.getStandardPrecision();
    return new NeoCurrencyTotals.TargetCurrency(currency.getId(),
        StringUtils.trimToEmpty(currency.getISOCode()),
        precision != null ? precision.intValue() : 2);
  }

  /** Today's multiply rate from the link's organization up to {@code 0}; {@code null} if none. */
  private static BigDecimal rate(String fromCurrencyId, String toCurrencyId,
      Organization organization, Client client) {
    if (fromCurrencyId == null || toCurrencyId == null || client == null) {
      return null;
    }
    try {
      OBDal dal = OBDal.getInstance();
      ConversionRate rate = FinancialUtils.getConversionRate(new Date(),
          dal.getProxy(Currency.class, fromCurrencyId), dal.getProxy(Currency.class, toCurrencyId),
          organization != null ? organization : dal.getProxy(Organization.class, "0"), client);
      BigDecimal multiply = rate == null ? null : rate.getMultipleRateBy();
      return multiply != null && multiply.signum() != 0 ? multiply : null;
    } catch (RuntimeException e) {
      // A missing or unreadable rate leaves that currency out (missingRateCurrencies), as the app.
      log.warn("Portal: no usable conversion rate {} -> {}: {}", fromCurrencyId, toCurrencyId,
          e.getMessage());
      return null;
    }
  }
}
