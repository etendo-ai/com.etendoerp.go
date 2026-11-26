/*
 *************************************************************************
 * The contents of this file are subject to the Etendo License
 * (the "License"), you may not use this file except in compliance
 * with the License.
 * You may obtain a copy of the License at
 * https://github.com/etendosoftware/etendo_core/blob/main/legal/Etendo_license.txt
 * Software distributed under the License is distributed on an
 * "AS IS" basis, WITHOUT WARRANTY OF ANY KIND, either express or
 * implied. See the License for the specific language governing rights
 * and limitations under the License.
 * All portions are Copyright (C) 2021-2026 FUTIT SERVICES, S.L
 * All Rights Reserved.
 * Contributor(s): Futit Services S.L.
 *************************************************************************
 */

package com.etendoerp.go.schemaforge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.etendoerp.go.schemaforge.ocr.OcrLookups;
import com.etendoerp.go.schemaforge.ocr.OcrLookups.PurchasePriceList;

/**
 * The price a manual inbox line takes from the price list (ETP-6051): the pure rule over the
 * lookups, with no DAL. The list price is always an organization-currency price (the list's own
 * currency is not read). Covered: as is on an org-currency document, converted (HALF_UP to the
 * price precision) on any other, and every case that leaves the price alone.
 *
 * <p>A new class: no test covers {@code InvoiceInboxLinePricing} (it is new) and it is a pure unit
 * over {@code OcrLookups}, unlike {@code InvoiceInboxApplyTest}, which exercises it only end to end
 * through the stored document.</p>
 *
 * @covers com.etendoerp.go.schemaforge.InvoiceInboxLinePricing
 */
class InvoiceInboxLinePricingTest {

  private static final LocalDate DATE = LocalDate.parse("2026-11-02");

  private OcrLookups lookups;

  @BeforeEach
  void setUp() {
    lookups = mock(OcrLookups.class);
  }

  private void list(boolean includesTax) {
    when(lookups.documentPriceList("V1")).thenReturn(
        Optional.of(new PurchasePriceList("PL1", includesTax)));
  }

  private void price(String value) {
    when(lookups.productStandardPrice("PL1", "P1")).thenReturn(
        Optional.of(new BigDecimal(value)));
  }

  /** A list in the organization currency EUR, a document in USD, the rate and precision given. */
  private void orgListToUsd(String listPrice, String rate, Integer precision) {
    list(false);
    price(listPrice);
    when(lookups.organizationCurrencyId("ORG")).thenReturn(Optional.of("EUR"));
    when(lookups.conversionRate("ORG", "CLI", "USD", DATE)).thenReturn(
        rate == null ? Optional.empty() : Optional.of(new BigDecimal(rate)));
    when(lookups.priceScale("USD")).thenReturn(Optional.ofNullable(precision));
  }

  private Optional<BigDecimal> priceFor(String docCurrency) {
    return InvoiceInboxLinePricing.priceFor(lookups, "V1", "P1", docCurrency, "ORG", "CLI", DATE);
  }

  @Test
  @DisplayName("a document in the organization currency gives the catalog price as is")
  void sameCurrencyIsTakenAsIs() {
    list(false);
    price("12.3456");
    when(lookups.organizationCurrencyId("ORG")).thenReturn(Optional.of("EUR"));

    assertEquals(new BigDecimal("12.3456"), priceFor("EUR").orElseThrow());
    verify(lookups, never()).conversionRate(any(), any(), any(), any());
  }

  @Test
  @DisplayName("an org-currency list on a document in another currency is price x rate, HALF_UP, at precision 2")
  void convertedAtPrecisionTwo() {
    orgListToUsd("10", "1.0855", 2);

    assertEquals(new BigDecimal("10.86"), priceFor("USD").orElseThrow());
  }

  @Test
  @DisplayName("the precision of the document currency rules the rounding: 0, 3, and 2 when it has none")
  void convertedAtThePrecisionOfTheCurrency() {
    orgListToUsd("10", "1.0855", 0);
    assertEquals(new BigDecimal("11"), priceFor("USD").orElseThrow());

    orgListToUsd("10", "1.0855", 3);
    assertEquals(new BigDecimal("10.855"), priceFor("USD").orElseThrow());

    orgListToUsd("10", "1.0855", null);
    assertEquals(new BigDecimal("10.86"), priceFor("USD").orElseThrow());
  }

  @Test
  @DisplayName("rounding is HALF_UP, not banker's: 0.125 at precision 2 is 0.13, and half a unit goes up")
  void roundingIsHalfUp() {
    orgListToUsd("0.125", "1", 2);
    assertEquals(new BigDecimal("0.13"), priceFor("USD").orElseThrow());

    orgListToUsd("2.5", "1", 0);
    assertEquals(new BigDecimal("3"), priceFor("USD").orElseThrow());
  }

  @Test
  @DisplayName("no rate, or a rate that is not positive, leaves the price alone")
  void noValidRate() {
    orgListToUsd("10", null, 2);
    assertTrue(priceFor("USD").isEmpty());

    orgListToUsd("10", "0", 2);
    assertTrue(priceFor("USD").isEmpty());

    orgListToUsd("10", "-1.2", 2);
    assertTrue(priceFor("USD").isEmpty());
  }

  @Test
  @DisplayName("on another document currency the list price is x rate: 90 x 1.47 = 132.30")
  void aListIsMultipliedByTheRate() {
    orgListToUsd("90", "1.47", 2);

    assertEquals(new BigDecimal("132.30"), priceFor("USD").orElseThrow());
  }

  @Test
  @DisplayName("an unknown organization currency gives nothing, in any document currency")
  void unknownOrganizationCurrency() {
    list(false);
    price("10");
    when(lookups.organizationCurrencyId("ORG")).thenReturn(Optional.empty());

    assertTrue(priceFor("USD").isEmpty());
    assertTrue(priceFor("EUR").isEmpty());
    verify(lookups, never()).conversionRate(any(), any(), any(), any());
  }

  @Test
  @DisplayName("a tax-included list gives nothing, even in the document currency")
  void aTaxIncludedList() {
    list(true);
    price("10");

    assertTrue(priceFor("EUR").isEmpty());
  }

  @Test
  @DisplayName("a product absent from the list, or priced 0 or negative, gives nothing")
  void noUsablePrice() {
    list(false);

    assertTrue(priceFor("EUR").isEmpty());

    price("0");
    assertTrue(priceFor("EUR").isEmpty());

    price("-3");
    assertTrue(priceFor("EUR").isEmpty());
  }

  @Test
  @DisplayName("no price list at all, no lookups, or an unknown document currency gives nothing")
  void noListNoLookupsNoDocumentCurrency() {
    assertTrue(priceFor("EUR").isEmpty());
    assertTrue(InvoiceInboxLinePricing.priceFor(null, "V1", "P1", "EUR", "ORG", "CLI", DATE)
        .isEmpty());

    list(false);
    price("10");
    assertTrue(priceFor(null).isEmpty());
    assertTrue(priceFor("  ").isEmpty());
  }

  @Test
  @DisplayName("a new vendor (no id) asks the default list; with no invoice date the rate is asked with none")
  void aNewVendorAndNoDate() {
    when(lookups.documentPriceList(null)).thenReturn(
        Optional.of(new PurchasePriceList("PLD", false)));
    when(lookups.productStandardPrice("PLD", "P1")).thenReturn(Optional.of(new BigDecimal("4")));

    when(lookups.organizationCurrencyId("ORG")).thenReturn(Optional.of("EUR"));
    assertEquals(new BigDecimal("4"), InvoiceInboxLinePricing
        .priceFor(lookups, null, "P1", "EUR", "ORG", "CLI", null).orElseThrow());

    assertTrue(InvoiceInboxLinePricing.priceFor(lookups, null, "P1", "USD", "ORG", "CLI", null)
        .isEmpty());
    verify(lookups).conversionRate("ORG", "CLI", "USD", null);
  }
}
