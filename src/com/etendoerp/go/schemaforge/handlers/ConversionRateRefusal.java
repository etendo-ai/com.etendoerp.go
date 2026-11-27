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

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import javax.servlet.http.HttpServletResponse;

import org.codehaus.jettison.json.JSONException;
import org.codehaus.jettison.json.JSONObject;

import com.etendoerp.go.schemaforge.NeoMessageText;
import com.etendoerp.go.schemaforge.NeoResponse;

/**
 * ETP-6365: a refusal of the {@code conversion-rates} customization, carrying the
 * {@code AD_MESSAGE} key and the HTTP status it is answered with. Thrown before anything is
 * written, so the caller only has to turn it into a response. A refusal about one request field
 * carries it ({@link #getField()}), answered as {@code error.field} and, by
 * {@code previewManualRate}, as {@code errorField}. A refusal about instants carries them
 * structured too ({@link #getDetails()}), answered as {@code error.details} and, by
 * {@code previewManualRate}, as {@code errorDetails} — so a client can show them in its own zone.
 *
 * <p>Message keys (Spanish and English text in {@code messages/neo_hints_*.properties}):</p>
 * <ul>
 *   <li>{@value #CURRENCY_REQUIRED}, {@value #CURRENCY_INVALID}, {@value #ORG_CURRENCY},
 *       {@value #AUTO_CURRENCY}, {@value #INVALID_RATE}, {@value #INVALID_VALID_FROM},
 *       {@value #FUTURE_START} — 400, the request itself is wrong;</li>
 *   <li>{@value #USE_ACTIONS} — 405, a create or update through the generic CRUD;</li>
 *   <li>{@value #OVERLAP}, {@value #SYNCED_LOCKED}, {@value #CURRENCY_HAS_RATES},
 *       {@value #CURRENCY_NO_RATES} — 409, the request
 *       collides with the stored ranges;</li>
 *   <li>{@value #NO_ORG_CURRENCY} — 422, the organization has no currency to convert from.</li>
 * </ul>
 */
class ConversionRateRefusal extends RuntimeException {

  private static final long serialVersionUID = 1L;

  static final String CURRENCY_REQUIRED = "ETGO_ConvRateCurrencyRequired";
  static final String CURRENCY_INVALID = "ETGO_ConvRateCurrencyInvalid";
  static final String ORG_CURRENCY = "ETGO_ConvRateOrgCurrency";
  static final String AUTO_CURRENCY = "ETGO_ConvRateAutoCurrency";
  static final String INVALID_RATE = "ETGO_ConvRateInvalidRate";
  static final String INVALID_VALID_FROM = "ETGO_ConvRateInvalidValidFrom";
  static final String OVERLAP = "ETGO_ConvRateOverlap";
  static final String SYNCED_LOCKED = "ETGO_ConvRateSyncedLocked";
  static final String FUTURE_START = "ETGO_ConvRateFutureStart";
  static final String CURRENCY_HAS_RATES = "ETGO_ConvRateCurrencyHasRates";
  static final String CURRENCY_NO_RATES = "ETGO_ConvRateCurrencyNoRates";
  static final String USE_ACTIONS = "ETGO_ConvRateUseActions";
  /** Not a refusal: the warning {@code updateManualRate} answers with its proposal. */
  static final String UPDATE_WARNING = "ETGO_ConvRateUpdateWarning";
  static final String NO_ORG_CURRENCY = "ETGO_ConvRateNoOrgCurrency";
  /** Wording of an open end inside {@value #OVERLAP} ("sin fecha de fin"). */
  static final String NO_END_DATE = "ETGO_ConvRateNoEndDate";

  /** HTTP 422: the request is well formed but the organization cannot serve it. */
  static final int SC_UNPROCESSABLE = 422;

  /** How {@value #OVERLAP} shows an instant, in every language (the design's format). */
  static final DateTimeFormatter INSTANT_FORMAT = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm");

  /** How {@link #getDetails()} carries an instant: the server's wall clock, as NEO answers it. */
  static final DateTimeFormatter WIRE_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");

  /** {@link #getDetails()} of {@value #OVERLAP}: start of the colliding range. */
  static final String D_CONFLICT_FROM = "conflictFrom";
  /** End of the colliding range; {@code null} when it is open (or unknown). */
  static final String D_CONFLICT_TO = "conflictTo";
  /** Whether the colliding range is open (no end date). */
  static final String D_CONFLICT_OPEN = "conflictOpen";
  /** The start a new range must be later than. */
  static final String D_SUGGESTED_FROM = "suggestedFrom";
  /** The server's zone id, the zone of the three instants above. */
  static final String D_TIME_ZONE = "timeZone";

  private static final String NO_VALUE = "—";

  private final int status;
  private final String key;
  private final String field;
  private final transient Object[] args;
  private final transient Map<String, Object> details;

  ConversionRateRefusal(int status, String key, Object... args) {
    this(status, key, null, Collections.emptyMap(), args);
  }

  private ConversionRateRefusal(int status, String key, String field, Map<String, Object> details,
      Object... args) {
    super(key);
    this.status = status;
    this.key = key;
    this.field = field;
    this.details = details;
    this.args = args == null ? new Object[0] : args.clone();
  }

  static ConversionRateRefusal badRequest(String key, Object... args) {
    return new ConversionRateRefusal(HttpServletResponse.SC_BAD_REQUEST, key, args);
  }

  static ConversionRateRefusal conflict(String key, Object... args) {
    return new ConversionRateRefusal(HttpServletResponse.SC_CONFLICT, key, args);
  }

  /**
   * The overlap refusal, with the conflicting range and the start to choose instead: "Ya existe
   * una tasa de conversión para EUR → CHF en las fechas indicadas (01/11/2026 09:00 – 18/11/2026
   * 14:29). Elija una fecha de inicio posterior al 18/11/2026 14:30. Las horas están en la zona
   * horaria del servidor (UTC)." The text names the zone, so an MCP agent reads it right; the same
   * instants travel structured in {@link #getDetails()} ({@code conflictFrom}, {@code conflictTo},
   * {@code conflictOpen}, {@code suggestedFrom}, {@code timeZone}), so the window shows them in the
   * browser's zone instead of the server's.
   *
   * @param fromIso    the origin ISO code
   * @param toIso      the destination ISO code
   * @param conflict   the range the new one collides with; {@code null} renders "—"
   * @param suggestion the start to choose a later one than; {@code null} renders "—"
   * @param zone       the server's zone, the zone of every instant above
   * @return the 409, bound to {@code validFromDate}
   */
  static ConversionRateRefusal overlapOf(String fromIso, String toIso,
      ConversionRateRules.Range conflict, LocalDateTime suggestion, ZoneId zone) {
    String start = conflict != null ? format(conflict.validFrom()) : NO_VALUE;
    String end;
    if (conflict == null) {
      end = NO_VALUE;
    } else if (conflict.isOpen()) {
      end = NeoMessageText.text(NO_END_DATE);
    } else {
      end = format(conflict.validTo());
    }
    String zoneId = zone != null ? zone.getId() : ZoneId.systemDefault().getId();
    Map<String, Object> details = new LinkedHashMap<>();
    details.put(D_CONFLICT_FROM, conflict != null ? wire(conflict.validFrom()) : null);
    boolean open = conflict != null && conflict.isOpen();
    details.put(D_CONFLICT_TO, conflict != null && !open ? wire(conflict.validTo()) : null);
    details.put(D_CONFLICT_OPEN, open);
    details.put(D_SUGGESTED_FROM, wire(suggestion));
    details.put(D_TIME_ZONE, zoneId);
    return new ConversionRateRefusal(HttpServletResponse.SC_CONFLICT, OVERLAP,
        ConversionRateWrites.F_VALID_FROM, Collections.unmodifiableMap(details), fromIso, toIso,
        start, end, format(suggestion), zoneId);
  }

  private static String wire(LocalDateTime instant) {
    return instant == null ? null : instant.format(WIRE_FORMAT);
  }

  static String format(LocalDateTime instant) {
    return instant == null ? NO_VALUE : instant.format(INSTANT_FORMAT);
  }

  /**
   * The same refusal, bound to the request field it is about.
   *
   * @param requestField the body key, e.g. {@code validFromDate}
   * @return a copy carrying the field
   */
  ConversionRateRefusal on(String requestField) {
    return new ConversionRateRefusal(status, key, requestField, details, args);
  }

  int getStatus() {
    return status;
  }

  String getKey() {
    return key;
  }

  /** The request field the refusal is about, or {@code null}. */
  String getField() {
    return field;
  }

  /**
   * The structured values of the refusal (see {@link #overlapOf}), empty when it has none.
   *
   * @return the values by name; a {@code null} value is answered as JSON {@code null}
   */
  Map<String, Object> getDetails() {
    return details;
  }

  /**
   * {@link #getDetails()} as JSON, or {@code null} when there are none.
   *
   * @return the JSON object
   * @throws JSONException if a value cannot be put
   */
  JSONObject detailsJson() throws JSONException {
    if (details.isEmpty()) {
      return null;
    }
    JSONObject json = new JSONObject();
    for (Map.Entry<String, Object> entry : details.entrySet()) {
      json.put(entry.getKey(), entry.getValue() == null ? JSONObject.NULL : entry.getValue());
    }
    return json;
  }

  /** The text in the request language. */
  String text() {
    return NeoMessageText.text(key, args);
  }

  /**
   * The keyed error response ({@code error.message} + {@code error.messageKeys}, and
   * {@code error.field} when the refusal is bound to one, {@code error.details} when it carries
   * structured values).
   */
  NeoResponse toResponse() {
    NeoResponse response = NeoMessageText.error(status, key, args);
    JSONObject error = response.getBody() != null ? response.getBody().optJSONObject("error")
        : null;
    if (error != null) {
      try {
        if (field != null) {
          error.put("field", field);
        }
        JSONObject structured = detailsJson();
        if (structured != null) {
          error.put("details", structured);
        }
      } catch (JSONException e) {
        // the keyed error is still a valid answer without the field and the details
      }
    }
    return NeoResponse.ensureTopLevelMessage(response);
  }
}
