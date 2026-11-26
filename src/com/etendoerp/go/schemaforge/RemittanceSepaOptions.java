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
 * All portions are Copyright © 2021-2026 FUTIT SERVICES, S.L
 * All Rights Reserved.
 * Contributor(s): Futit Services S.L.
 * *************************************************************************
 */

package com.etendoerp.go.schemaforge;

import java.util.Locale;

import org.apache.commons.lang3.StringUtils;
import org.openbravo.module.remittance.Remittance;

/**
 * ETP-6247 — the SEPA direct debit scheme a COLLECTION remittance carries on its own,
 * {@code REM_REMITTANCE.DD_SCHEME} ({@value #SCHEME_CORE} | {@value #SCHEME_B2B}, DAL
 * {@value #PROPERTY_SCHEME}).
 *
 * <p>Classic keeps it as a parameter of the remittance TYPE ({@code CORE_B2B}, read by
 * {@code CoreDirectDebit}); here it is per remittance and the generator output is rewritten by
 * structure ({@link RemittanceCreditorIdRewriter#applyOptions}). Known cost: Classic's own «Create
 * File» button ignores the column and writes what the type says. It only applies to a collection:
 * on a payment it is not offered and a value is refused.
 *
 * <p>Financed remittances (Classic «Remit for Discount») are not supported in GO.
 */
final class RemittanceSepaOptions {

  static final String PROPERTY_SCHEME = "directDebitScheme";
  static final String SCHEME_CORE = "CORE";
  static final String SCHEME_B2B = "B2B";

  private RemittanceSepaOptions() {
  }

  /**
   * The scheme as typed, normalized.
   *
   * @param raw the value, may be {@code null}
   * @return {@value #SCHEME_CORE}, {@value #SCHEME_B2B}, or {@code null} when it is neither
   */
  static String normalizeScheme(String raw) {
    String value = StringUtils.trimToEmpty(raw).toUpperCase(Locale.ROOT);
    return SCHEME_CORE.equals(value) || SCHEME_B2B.equals(value) ? value : null;
  }

  /**
   * The remittance's scheme; a collection without a stored one is {@value #SCHEME_CORE}.
   *
   * @param remittance the remittance
   * @return {@value #SCHEME_CORE} or {@value #SCHEME_B2B}
   */
  static String scheme(Remittance remittance) {
    Object value = remittance.get(PROPERTY_SCHEME);
    String scheme = value != null ? normalizeScheme(value.toString()) : null;
    return scheme != null ? scheme : SCHEME_CORE;
  }
}
