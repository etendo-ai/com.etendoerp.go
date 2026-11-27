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

import java.util.List;

import com.etendoerp.go.common.GoAccountResolver;
import com.etendoerp.go.featureflags.FeatureFlagContext;
import com.etendoerp.go.featureflags.GoFeatureFlags;

/**
 * ETP-6365 — the one gate of the {@value GoFeatureFlags#FLAG_CURRENCY_CONVERSION_V2} flag (the
 * redesigned Currency Conversion window and its manual rate writes). Retiring the flag is a grep
 * for this class. It is the <b>only</b> reader of the flag: never evaluate the key anywhere else.
 *
 * <p><b>Evaluate at request entry.</b> {@link #isEnabled()} resolves the account from the
 * {@code OBContext} user, which exists only while the request runs as the account owner.
 *
 * <p><b>Off is the default.</b> {@link GoFeatureFlags#isEnabled} answers {@code false} for an
 * unconfigured flag, an unreachable control plane and any evaluation error; no method throws.
 *
 * <p><b>Per account.</b> The context is {@code FeatureFlagContext.forAccount(<ETGO_ACCOUNT email>)},
 * the same identity {@code SFWindowAccessMap} evaluates for {@code capabilities.currencyConversionV2},
 * so the window the browser shows and the writes the backend accepts always agree.
 *
 * <p><b>What it gates:</b> see {@code ConversionRateFlagGate} — the manual rate writes of the
 * {@code conversion-rates} customization. Off, they answer {@link #featureDisabled()} on every
 * channel.
 */
public final class CurrencyConversionV2Flag {

  /** The {@code error.code} of the refusal a gated request answers while the flag is off. */
  public static final String CODE_FEATURE_DISABLED = "feature_disabled";

  /** The message key of that refusal ({@code neo_hints_*.properties}). */
  public static final String MSG_DISABLED = "ETGO_CurrencyConversionV2Disabled";

  private CurrencyConversionV2Flag() {
  }

  /**
   * The refusal every gated request answers while the flag is off: {@code 403} with
   * {@code error.code = feature_disabled} and
   * {@code messageKeys = [ETGO_CurrencyConversionV2Disabled]}, the same envelope as the other
   * backend flags' refusals.
   *
   * @return the 403 response
   */
  public static NeoResponse featureDisabled() {
    return NeoErrorResponses.toResponse(403, CODE_FEATURE_DISABLED,
        NeoMessageText.text(MSG_DISABLED), List.of(MSG_DISABLED));
  }

  /**
   * Whether the redesigned Currency Conversion window is enabled for the current request user,
   * targeted on the {@code ETGO_ACCOUNT} email resolved by
   * {@link GoAccountResolver#currentAccountEmail()}. Call it at request entry only.
   *
   * @return {@code true} only when the flag resolves to on; {@code false} otherwise
   */
  public static boolean isEnabled() {
    return isEnabledFor(GoAccountResolver.currentAccountEmail());
  }

  /**
   * Whether the redesigned Currency Conversion window is enabled for an account the caller has
   * already resolved. Used by {@code SFWindowAccessMap}, which republishes this answer as
   * {@code capabilities.currencyConversionV2}, so the browser never evaluates the flag itself.
   *
   * @param accountEmail the {@code ETGO_ACCOUNT} email; blank or {@code null} means no account,
   *     which no per-account rule matches, so the default stays off
   * @return {@code true} only when the flag resolves to on; {@code false} otherwise
   */
  public static boolean isEnabledFor(String accountEmail) {
    return GoFeatureFlags.isEnabled(GoFeatureFlags.FLAG_CURRENCY_CONVERSION_V2,
        FeatureFlagContext.forAccount(accountEmail));
  }
}
