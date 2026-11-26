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

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.Optional;

import org.apache.commons.lang3.StringUtils;

import com.etendoerp.go.schemaforge.ocr.OcrLookups;

/**
 * The unit price a MANUAL inbox line takes from the price list when its product is picked
 * (ETP-6051), the way the purchase invoice window prices a picked product.
 *
 * <p><b>Rule (parity with the purchase invoice).</b> The catalog price is the {@code pricestd} the
 * product selector displays ({@link OcrLookups#productStandardPrice}: latest active version valid NOW,
 * the same query as the selector, not the invoice date's). The list is the document's
 * ({@link InvoiceInboxPriceList}). The purchase invoice reads that price as an ORGANIZATION-currency
 * price ALWAYS, whatever the list's own currency (the drawer shows it with {@code priceCurrency =
 * org}); so does this class, and the list's currency is deliberately ignored. Documented limitation
 * shared with the purchase invoice: a list in another currency is read as if it were in the
 * organization's. The price is applied only when it is positive and:
 * <ul>
 *   <li>the document currency is the organization currency: as is; or</li>
 *   <li>the document is in another currency: multiplied by the org-to-document rate valid on the
 *       invoice date and rounded HALF_UP to the document currency's price precision
 *       ({@code C_Currency.PricePrecision}, 2 when none), the rule of
 *       {@code OrderLineCurrencyConversion} (ETP-6177) and of the SPA's
 *       {@code applyProductCurrencyConversion}. No positive rate: nothing is applied (the
 *       {@code exchangeRate} blocker already warns).</li>
 * </ul>
 * Otherwise (no list, a tax-included list, a product absent from the list, price 0, unknown
 * currency) nothing is applied and the line keeps its price. Caveat: the converted price the drawer
 * shows is at a fixed 2 decimals, while the server rounds to {@code C_Currency.PricePrecision}.
 *
 * <p>Total and stateless: empty means "leave the price alone".</p>
 */
final class InvoiceInboxLinePricing {

  /** The SPA's fixed rounding, used when the currency has no price precision. */
  static final int DEFAULT_PRICE_SCALE = 2;

  private InvoiceInboxLinePricing() {
  }

  /**
   * The price to put on the line, or empty to leave it untouched.
   *
   * @param lookups       the document type's lookups
   * @param vendorId      the linked vendor, {@code null} for none or a new one
   * @param productId     the picked product
   * @param docCurrencyId the document currency
   * @param organizationId the organization of the row
   * @param clientId      the client of the row
   * @param invoiceDate   the invoice date, {@code null} when not read yet
   * @return the unit price in the document currency
   */
  static Optional<BigDecimal> priceFor(OcrLookups lookups, String vendorId, String productId,
      String docCurrencyId, String organizationId, String clientId, LocalDate invoiceDate) {
    if (lookups == null || StringUtils.isBlank(docCurrencyId)) {
      return Optional.empty();
    }
    Optional<OcrLookups.PurchasePriceList> list = lookups.documentPriceList(vendorId);
    if (list.isEmpty() || list.get().includesTax()) {
      return Optional.empty();
    }
    BigDecimal price = lookups.productStandardPrice(list.get().id(), productId).orElse(null);
    if (price == null || price.signum() <= 0) {
      return Optional.empty();
    }
    String orgCurrencyId = lookups.organizationCurrencyId(organizationId).orElse(null);
    if (orgCurrencyId == null) {
      return Optional.empty();
    }
    if (docCurrencyId.equals(orgCurrencyId)) {
      return Optional.of(price);
    }
    BigDecimal rate = lookups.conversionRate(organizationId, clientId, docCurrencyId, invoiceDate)
        .filter(r -> r.signum() > 0).orElse(null);
    if (rate == null) {
      return Optional.empty();
    }
    int scale = lookups.priceScale(docCurrencyId).orElse(DEFAULT_PRICE_SCALE);
    return Optional.of(price.multiply(rate).setScale(scale, RoundingMode.HALF_UP));
  }
}
