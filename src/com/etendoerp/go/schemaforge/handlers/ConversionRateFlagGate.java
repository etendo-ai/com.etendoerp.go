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

package com.etendoerp.go.schemaforge.handlers;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import com.etendoerp.go.schemaforge.CurrencyConversionV2Flag;
import com.etendoerp.go.schemaforge.NeoContext;
import com.etendoerp.go.schemaforge.NeoEndpointType;
import com.etendoerp.go.schemaforge.util.NeoActionContract;

/**
 * ETP-6365 — what the {@code currency-conversion-v2} flag ({@link CurrencyConversionV2Flag}) gates
 * on {@code conversion-rates/conversionRate}. Owned by the flag: retiring it deletes this class
 * and its calls in {@link ConversionRateHandler}.
 *
 * <p><b>Gated</b> (refused with 403 {@code feature_disabled} while the flag is off, on every
 * channel — REST single, batch and MCP all reach {@link ConversionRateHandler#handle}):
 * <ul>
 *   <li>the write actions {@code createManualCurrency}, {@code createManualRate} and
 *       {@code updateManualRate} — each writes the rate and its inverse;</li>
 *   <li>{@code previewManualRate}, the dry run of a create: it only exists to prepare one;</li>
 *   <li>a {@code DELETE} of a rate, and any generic {@code POST}/{@code PUT}/{@code PATCH}.</li>
 * </ul>
 * The same actions are left out of {@link #declaredContracts}, so the MCP does not advertise what
 * the window (read-only with the flag off) does not offer.
 *
 * <p><b>Not gated:</b> the reads, {@code serverClock}, {@code currentRates},
 * {@code manualCurrencyOptions} and every non-CRUD surface (selectors, defaults, callouts).
 */
final class ConversionRateFlagGate {

  private static final Set<String> GATED_ACTIONS = Set.of(
      ConversionRateWriteActions.CREATE_CURRENCY,
      ConversionRateWriteActions.CREATE_RATE,
      ConversionRateWriteActions.UPDATE_RATE,
      ConversionRateActions.PREVIEW_MANUAL_RATE);

  private static final Set<String> WRITE_METHODS = Set.of("POST", "PUT", "PATCH", "DELETE");

  /** The contracts the MCP sees while the flag is off: the ungated (read) actions only. */
  static final Map<String, NeoActionContract> UNGATED_CONTRACTS = ungated();

  private ConversionRateFlagGate() {
  }

  private static Map<String, NeoActionContract> ungated() {
    Map<String, NeoActionContract> contracts = new LinkedHashMap<>(ConversionRateActions.CONTRACTS);
    contracts.keySet().removeAll(GATED_ACTIONS);
    return Collections.unmodifiableMap(contracts);
  }

  /**
   * Whether the request is one the flag gates. Structure of this entity's own surface only.
   *
   * @param context the request context
   * @return {@code true} for a gated action, a delete or a generic write
   */
  static boolean isGated(NeoContext context) {
    if (context == null) {
      return false;
    }
    NeoEndpointType type = context.getEndpointType();
    if (type == NeoEndpointType.ACTION) {
      return GATED_ACTIONS.contains(context.getFieldName());
    }
    if (type != null && type != NeoEndpointType.CRUD) {
      return false;
    }
    String method = context.getHttpMethod();
    return method != null && WRITE_METHODS.contains(method);
  }

  /**
   * The contracts to declare: all of them while the flag is on, the ungated ones while it is off.
   *
   * @param enabled the flag, evaluated for the request
   * @return the contracts
   */
  static Map<String, NeoActionContract> declaredContracts(boolean enabled) {
    return enabled ? ConversionRateActions.CONTRACTS : UNGATED_CONTRACTS;
  }
}
