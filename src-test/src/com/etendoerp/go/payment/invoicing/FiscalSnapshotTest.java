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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.codehaus.jettison.json.JSONObject;
import org.junit.jupiter.api.Test;

/**
 * The fiscal data a payment is invoiced to (ETP-6322): what the buyer typed at the checkout, with
 * a Spanish tax id checked down to its check digit; the tenant's company only when the checkout
 * told nothing.
 *
 * @covers com.etendoerp.go.payment.invoicing.FiscalSnapshot
 */
class FiscalSnapshotTest {

  private static JSONObject checkout(String name, String taxIdType, String taxId, String street,
      String country) throws Exception {
    return new JSONObject()
        .put("name", name == null ? JSONObject.NULL : name)
        .put("taxIdType", taxIdType == null ? JSONObject.NULL : taxIdType)
        .put("taxId", taxId == null ? JSONObject.NULL : taxId)
        .put("address1", street == null ? JSONObject.NULL : street)
        .put("address2", JSONObject.NULL)
        .put("postalCode", street == null ? JSONObject.NULL : "28013")
        .put("city", street == null ? JSONObject.NULL : "Madrid")
        .put("region", JSONObject.NULL)
        .put("countryCode", country == null ? JSONObject.NULL : country);
  }

  private static JSONObject environment() throws Exception {
    return new JSONObject()
        .put("name", "Fiscal Uno Prod").put("legalName", "Fiscal Uno SL")
        .put("taxId", "B81639718").put("address1", "Gran Via 2").put("address2", JSONObject.NULL)
        .put("postalCode", "28001").put("city", "Madrid").put("region", "Madrid")
        .put("countryCode", "ES").put("country", "Spain");
  }

  @Test
  void aSpanishBusinessIsInvoicedWithWhatItTypedAtTheCheckout() throws Exception {
    JSONObject snapshot = FiscalSnapshot.merge(
        checkout("Fiscal Tres SL", "es_cif", "B81639734", "Calle Mayor 1", "ES"), environment(),
        "Spain");

    assertEquals("Fiscal Tres SL", snapshot.getString("name"));
    assertEquals("Fiscal Tres SL", snapshot.getString("legalName"));
    assertEquals("es_cif", snapshot.getString("taxIdType"));
    assertEquals("B81639734", snapshot.getString("taxId"));
    assertEquals("Calle Mayor 1", snapshot.getString("address1"));
    assertEquals("28013", snapshot.getString("postalCode"));
    assertEquals("ES", snapshot.getString("countryCode"));
    assertEquals("Spain", snapshot.getString("country"));
  }

  /** Stripe checks the shape only: a typo in the check digit must not reach an invoice. */
  @Test
  void aSpanishTaxIdWithAWrongCheckDigitIsLeftOut() throws Exception {
    JSONObject snapshot = FiscalSnapshot.merge(
        checkout("Fiscal Tres SL", "es_cif", "B81639735", "Calle Mayor 1", "ES"), environment(),
        "Spain");

    assertTrue(snapshot.isNull("taxId"));
    assertTrue(snapshot.isNull("taxIdType"));
    assertEquals("Fiscal Tres SL", snapshot.getString("name"), "never the tenant's company");
  }

  @Test
  void aSpanishVatNumberIsCheckedWithoutItsPrefix() throws Exception {
    assertTrue(FiscalSnapshot.isValidTaxId(checkout("X", "es_vat", "ESB81639734", null, "ES")));
    assertFalse(FiscalSnapshot.isValidTaxId(checkout("X", "es_vat", "ESB81639735", null, "ES")));
  }

  /** A self-employed buyer gives a DNI or a NIE under the same "ES NIF" type. */
  @Test
  void aPersonsDniOrNieIsAValidSpanishTaxId() throws Exception {
    assertTrue(FiscalSnapshot.isValidTaxId(checkout("X", "es_cif", "12345678Z", null, "ES")));
    assertTrue(FiscalSnapshot.isValidTaxId(checkout("X", "es_cif", "X1234567L", null, "ES")));
    assertFalse(FiscalSnapshot.isValidTaxId(checkout("X", "es_cif", "12345678A", null, "ES")));
  }

  /** Only Spanish tax ids have rules here; another country's is taken as Stripe accepted it. */
  @Test
  void aForeignTaxIdIsTakenAsStripeAcceptedIt() throws Exception {
    JSONObject snapshot = FiscalSnapshot.merge(
        checkout("Acme SARL", "eu_vat", "FR40303265045", "1 Rue de Rivoli", "FR"), environment(),
        "France");

    assertEquals("FR40303265045", snapshot.getString("taxId"));
    assertEquals("eu_vat", snapshot.getString("taxIdType"));
    assertEquals("France", snapshot.getString("country"));
  }

  /** Where Stripe asks for no tax id, the invoice goes to the cardholder, never the tenant. */
  @Test
  void withoutATaxIdThePartyIsStillTheCheckouts() throws Exception {
    JSONObject snapshot = FiscalSnapshot.merge(
        checkout("Juan Perez", null, null, "Av. Colon 1", "AR"), environment(), "Argentina");

    assertEquals("Juan Perez", snapshot.getString("name"));
    assertTrue(snapshot.isNull("taxId"));
    assertEquals("Av. Colon 1", snapshot.getString("address1"));
    assertEquals("AR", snapshot.getString("countryCode"));
  }

  @Test
  void anUnknownCountryNameStaysNull() throws Exception {
    JSONObject snapshot = FiscalSnapshot.merge(checkout("Juan Perez", null, null, null, "ES"),
        null, null);

    assertEquals("ES", snapshot.getString("countryCode"));
    assertTrue(snapshot.isNull("country"));
  }

  /** A payment recorded before the checkout data were kept reads as it always did. */
  @Test
  void withoutCheckoutDataTheTenantsCompanyIsReturnedUnchanged() throws Exception {
    JSONObject environment = environment();

    assertSame(environment, FiscalSnapshot.merge(null, environment, null));
    assertSame(environment, FiscalSnapshot.merge(checkout(null, null, null, null, null),
        environment, null));
    assertNull(FiscalSnapshot.merge(null, null, null));
  }
}
