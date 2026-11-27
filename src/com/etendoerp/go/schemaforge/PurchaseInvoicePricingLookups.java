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
import java.time.LocalDate;
import java.util.Optional;

import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.openbravo.dal.service.OBDal;
import org.openbravo.erpCommon.utility.OBCurrencyUtils;
import org.openbravo.model.common.currency.Currency;

import com.etendoerp.go.schemaforge.ocr.OcrLookups;
import com.etendoerp.go.schemaforge.selector.policy.ProductPriceSelectorPolicy;

/**
 * The price-list, catalog-price and conversion-rate lookups of the purchase-invoice review
 * (ETP-6051), split from {@link PurchaseInvoiceOcrLookups} to keep it cohesive and within the
 * method budget. Package-private: it only exists as the parent of that class, which is the bean the
 * OCR registry binds ({@link PurchaseInvoiceOcrLookups#RESOLVER}). Every method is total (a failed
 * lookup is logged by class name and answers "nothing found").
 */
abstract class PurchaseInvoicePricingLookups implements OcrLookups {

  private static final Logger log = LogManager.getLogger(PurchaseInvoicePricingLookups.class);

  @Override
  public Optional<PurchasePriceList> documentPriceList(String vendorId) {
    try {
      InvoiceInboxPriceList.Resolved list = InvoiceInboxPriceList.forVendor(vendorId);
      return list == null ? Optional.empty()
          : Optional.of(new PurchasePriceList(list.id(), list.includesTax()));
    } catch (RuntimeException e) {
      log.warn("OCR document price list lookup failed: {}", e.getClass().getSimpleName());
      return Optional.empty();
    }
  }

  /** Same version rule as the product selector: see {@link ProductPriceSelectorPolicy}. */
  @Override
  public Optional<BigDecimal> productStandardPrice(String priceListId, String productId) {
    try {
      return ProductPriceSelectorPolicy.standardPrice(priceListId, productId);
    } catch (RuntimeException e) {
      log.warn("OCR product price lookup failed: {}", e.getClass().getSimpleName());
      return Optional.empty();
    }
  }

  @Override
  public Optional<String> organizationCurrencyId(String organizationId) {
    try {
      return Optional.ofNullable(StringUtils.isBlank(organizationId) ? null
          : OBCurrencyUtils.getOrgCurrency(organizationId));
    } catch (RuntimeException e) {
      log.warn("OCR organization currency lookup failed: {}", e.getClass().getSimpleName());
      return Optional.empty();
    }
  }

  @Override
  public Optional<Integer> priceScale(String currencyId) {
    try {
      Currency currency = StringUtils.isBlank(currencyId) ? null
          : OBDal.getInstance().get(Currency.class, currencyId);
      Long precision = currency == null ? null : currency.getPricePrecision();
      return precision == null || precision < 0 ? Optional.empty()
          : Optional.of(precision.intValue());
    } catch (RuntimeException e) {
      log.warn("OCR currency price scale lookup failed: {}", e.getClass().getSimpleName());
      return Optional.empty();
    }
  }

  @Override
  public Optional<BigDecimal> conversionRate(String organizationId, String clientId,
      String toCurrencyId, LocalDate date) {
    try {
      Optional<String> from = organizationCurrencyId(organizationId);
      if (from.isEmpty() || date == null || StringUtils.isBlank(toCurrencyId)) {
        return Optional.empty();
      }
      return Optional.ofNullable(DocumentCurrencyRate.lookupRate(from.get(), toCurrencyId, date,
          organizationId, clientId));
    } catch (RuntimeException e) {
      log.warn("OCR conversion rate lookup failed: {}", e.getClass().getSimpleName());
      return Optional.empty();
    }
  }
}
