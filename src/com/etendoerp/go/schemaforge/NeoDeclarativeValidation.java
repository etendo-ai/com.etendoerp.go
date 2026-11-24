/*
 * *************************************************************************
 * The contents of this file are subject to the Etendo License
 * (the "License"), you may not use this file except in compliance with
 * the License.
 * You may obtain a copy of the License at
 * https://github.com/etendosoftware/etendo_core/blob/main/legal/Etendo_license.txt
 * Software distributed under the License  is  distributed  on  an  "AS IS"
 * basis, WITHOUT WARRANTY OF ANY KIND, either express or implied. See the
 * License for the specific language governing rights and limitations under
 * the License.
 * All portions are Copyright © 2021-2026 FUTIT SERVICES, S.L
 * All Rights Reserved.
 * Contributor(s): Futit Services S.L.
 * *************************************************************************
 */
package com.etendoerp.go.schemaforge;

import java.math.BigDecimal;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.UnaryOperator;
import java.util.regex.Pattern;

import javax.servlet.http.HttpServletResponse;

import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONException;
import org.codehaus.jettison.json.JSONObject;
import org.openbravo.model.ad.ui.Tab;

import com.etendoerp.go.mcp.McpFieldValidationRules;

/**
 * Authoritative server-side enforcement of the per-field {@code validation} constraints
 * (ETP-4558), run by {@link NeoWritePreflight} before any customization or persistence — so REST
 * POST/PUT/PATCH and every batch create share it.
 *
 * <h2>Source</h2>
 * The constraints are the {@code validation} section of the field's {@code ETGO_SF_FIELD.MCP_CONFIG}
 * (same object {@code schema_forge_core} publishes in the contract); see
 * {@link McpFieldValidationRules}. No window-specific code lives here.
 *
 * <h2>Semantics</h2>
 * <ul>
 *   <li>{@code required} is demanded only on POST; a PUT/PATCH is partial, so an omitted field is
 *       never required, but an explicit {@code null}/blank for a required field is refused.</li>
 *   <li>Every other constraint applies only to a value that is sent and not {@code null}; zero and
 *       {@code false} are real values.</li>
 *   <li>The field is found under its API key or under its DAL property name, the two names the
 *       write path accepts.</li>
 *   <li>A constraint of unknown shape or an unknown {@code format} is not enforced and is logged.</li>
 * </ul>
 *
 * <h2>Error contract</h2>
 * HTTP 400, {@code {"error":{"code":"VALIDATION_FAILED","fields":{"<apiKey>":[{"code":...}]}}}}.
 * Codes: {@code REQUIRED}, {@code INVALID_TYPE}, {@code TOO_SHORT} ({@code minLength}),
 * {@code TOO_LONG} ({@code maxLength}), {@code BELOW_MINIMUM} ({@code minimum}),
 * {@code ABOVE_MAXIMUM} ({@code maximum}), {@code INVALID_FORMAT} ({@code format}),
 * {@code INVALID_ENUM} ({@code allowed}), {@code DISALLOWED_SCHEME} ({@code allowedSchemes}).
 * The rejected value is never echoed or logged.
 */
final class NeoDeclarativeValidation {

  private static final String K_MIN_LENGTH = "minLength";
  private static final String K_MAX_LENGTH = "maxLength";
  private static final String K_MINIMUM = "minimum";
  private static final String K_MAXIMUM = "maximum";
  private static final String K_REQUIRED_KEY = "required";
  private static final String K_FORMAT = "format";
  private static final String K_ENUM = "enum";
  private static final String K_ALLOWED_SCHEMES = "allowedSchemes";
  private static final String K_EXPECTED = "expected";
  private static final String C_INVALID_TYPE = "INVALID_TYPE";
  private static final String C_REQUIRED = "REQUIRED";
  /** Unknown formats already reported: logged once per format, not once per write. */
  private static final Set<String> REPORTED_FORMATS = ConcurrentHashMap.newKeySet();

  static final String CODE_VALIDATION_FAILED = "VALIDATION_FAILED";

  private static final Logger log = LogManager.getLogger(NeoDeclarativeValidation.class);
  private static final String DATA_WRAPPER = "data";
  private static final String CODE = "code";
  private static final Pattern EMAIL = Pattern.compile("^[^\\s@]+@[^\\s@.]+(\\.[^\\s@.]+)+$");
  private static final Pattern UUID_HEX = Pattern.compile("^[0-9a-fA-F]{32}$|^[0-9a-fA-F]{8}-"
      + "[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$");
  private static final Pattern ISO_DATE = Pattern.compile("^\\d{4}-\\d{2}-\\d{2}$");

  private NeoDeclarativeValidation() {
  }

  /**
   * The refusal for a create/update whose body breaks a declared constraint, or {@code null}.
   *
   * @param context the CRUD request context
   * @param adTab the AD tab the write targets
   * @return the 400 response, or {@code null} to proceed
   */
  static NeoResponse refusalFor(NeoContext context, Tab adTab) {
    Map<String, JSONObject> rules = McpFieldValidationRules.forEntity(context.getSfEntity());
    if (rules.isEmpty()) {
      return null;
    }
    NeoFieldFilter filter = NeoFieldFilter.forEntity(context.getSfEntity(),
        adTab.getTable().getName());
    Map<String, List<JSONObject>> errors = evaluate(rules, filter::resolveWritablePropName,
        context.getRequestBody(), "POST".equals(context.getHttpMethod()));
    if (errors.isEmpty()) {
      return null;
    }
    // Field names and codes only: values may be personal data or secrets.
    log.warn("Declarative validation refused a {} on {}/{}: {}", context.getHttpMethod(),
        context.getSpecName(), context.getEntityName(), summary(errors));
    return toResponse(errors);
  }

  /**
   * Evaluates the constraints against a request body.
   *
   * @param rules API key to constraints
   * @param propResolver API key to DAL property name
   * @param body the request body, possibly wrapped under {@code data}; {@code null} is empty
   * @param create whether {@code required} demands presence (POST)
   * @return API key to its violations, in declaration order; empty when the body is valid
   */
  static Map<String, List<JSONObject>> evaluate(Map<String, JSONObject> rules,
      UnaryOperator<String> propResolver, JSONObject body, boolean create) {
    Map<String, List<JSONObject>> errors = new LinkedHashMap<>();
    JSONObject source = body == null ? new JSONObject() : body;
    JSONObject data = source.optJSONObject(DATA_WRAPPER);
    if (data != null) {
      source = data;
    }
    for (Map.Entry<String, JSONObject> rule : rules.entrySet()) {
      String apiKey = rule.getKey();
      List<JSONObject> found = new ArrayList<>();
      boolean present = false;
      String propName = propResolver.apply(apiKey);
      String[] candidates = propName == null || propName.equals(apiKey)
          ? new String[] { apiKey } : new String[] { apiKey, propName };
      for (String key : candidates) {
        if (source.has(key)) {
          present = true;
          checkValue(rule.getValue(), source.opt(key), found);
        }
      }
      if (!present && create && rule.getValue().optBoolean(K_REQUIRED_KEY, false)) {
        found.add(error(C_REQUIRED));
      }
      if (!found.isEmpty()) {
        errors.put(apiKey, found);
      }
    }
    return errors;
  }

  private static void checkValue(JSONObject rule, Object value, List<JSONObject> out) {
    boolean blank = value == null || JSONObject.NULL.equals(value)
        || (value instanceof String && ((String) value).trim().isEmpty());
    if (blank) {
      if (rule.optBoolean(K_REQUIRED_KEY, false)) {
        out.add(error(C_REQUIRED));
      }
      return;
    }
    checkLength(rule, value, out);
    checkRange(rule, value, out);
    checkEnum(rule, value, out);
    checkFormatAndScheme(rule, value, out);
  }

  private static void checkLength(JSONObject rule, Object value, List<JSONObject> out) {
    if (!rule.has(K_MIN_LENGTH) && !rule.has(K_MAX_LENGTH)) {
      return;
    }
    if (!(value instanceof String)) {
      out.add(error(C_INVALID_TYPE, K_EXPECTED, "string"));
      return;
    }
    // UTF-16 code units, the semantics the contract documents (JS String.length / AD fieldlength).
    int length = ((String) value).length();
    if (rule.has(K_MIN_LENGTH) && length < rule.optLong(K_MIN_LENGTH)) {
      out.add(error("TOO_SHORT", K_MIN_LENGTH, rule.opt(K_MIN_LENGTH)));
    }
    if (rule.has(K_MAX_LENGTH) && length > rule.optLong(K_MAX_LENGTH)) {
      out.add(error("TOO_LONG", K_MAX_LENGTH, rule.opt(K_MAX_LENGTH)));
    }
  }

  private static void checkRange(JSONObject rule, Object value, List<JSONObject> out) {
    if (!rule.has(K_MINIMUM) && !rule.has(K_MAXIMUM)) {
      return;
    }
    BigDecimal number = asNumber(value);
    if (number == null) {
      out.add(error(C_INVALID_TYPE, K_EXPECTED, "number"));
      return;
    }
    if (rule.has(K_MINIMUM) && number.compareTo(decimal(rule.opt(K_MINIMUM))) < 0) {
      out.add(error("BELOW_MINIMUM", K_MINIMUM, rule.opt(K_MINIMUM)));
    }
    if (rule.has(K_MAXIMUM) && number.compareTo(decimal(rule.opt(K_MAXIMUM))) > 0) {
      out.add(error("ABOVE_MAXIMUM", K_MAXIMUM, rule.opt(K_MAXIMUM)));
    }
  }

  private static void checkEnum(JSONObject rule, Object value, List<JSONObject> out) {
    JSONArray allowed = rule.optJSONArray(K_ENUM);
    if (allowed == null) {
      return;
    }
    for (int i = 0; i < allowed.length(); i++) {
      if (sameValue(allowed.opt(i), value)) {
        return;
      }
    }
    out.add(error("INVALID_ENUM", "allowed", allowed));
  }

  /** Numbers compare numerically (1 equals 1.0); anything else by its text. */
  private static boolean sameValue(Object allowed, Object value) {
    if (allowed instanceof Number && value instanceof Number) {
      return decimal(allowed).compareTo(decimal(value)) == 0;
    }
    return String.valueOf(allowed).equals(String.valueOf(value));
  }

  private static void checkFormatAndScheme(JSONObject rule, Object value, List<JSONObject> out) {
    String format = rule.optString(K_FORMAT, null);
    JSONArray schemes = rule.optJSONArray(K_ALLOWED_SCHEMES);
    if (format == null && schemes == null) {
      return;
    }
    if (!(value instanceof String)) {
      out.add(error(C_INVALID_TYPE, K_EXPECTED, "string"));
      return;
    }
    String text = ((String) value).trim();
    if (format != null && !matchesFormat(format, text)) {
      out.add(error("INVALID_FORMAT", K_FORMAT, format));
    }
    if (schemes != null && !schemeAllowed(text, schemes)) {
      out.add(error("DISALLOWED_SCHEME", K_ALLOWED_SCHEMES, schemes));
    }
  }

  /** Unknown formats are not enforced (logged once per call): a newer contract must not break writes. */
  private static boolean matchesFormat(String format, String text) {
    switch (format.toLowerCase(Locale.ROOT)) {
      case "email":
        return EMAIL.matcher(text).matches();
      case "uuid":
        return UUID_HEX.matcher(text).matches();
      case "date":
        return ISO_DATE.matcher(text).matches();
      case "uri":
      case "url":
        return parseUri(text) != null;
      default:
        if (REPORTED_FORMATS.add(format)) {
          log.warn("Declarative validation: unknown format '{}' is not enforced",
              StringUtils.abbreviate(format, 40));
        }
        return true;
    }
  }

  private static boolean schemeAllowed(String text, JSONArray schemes) {
    URI uri = parseUri(text);
    String scheme = uri == null ? null : uri.getScheme();
    if (scheme == null) {
      return false;
    }
    for (int i = 0; i < schemes.length(); i++) {
      if (scheme.equalsIgnoreCase(String.valueOf(schemes.opt(i)))) {
        return true;
      }
    }
    return false;
  }

  private static URI parseUri(String text) {
    try {
      return new URI(text);
    } catch (URISyntaxException e) {
      return null;
    }
  }

  private static BigDecimal asNumber(Object value) {
    if (value instanceof Number || value instanceof String) {
      try {
        return decimal(value);
      } catch (NumberFormatException e) {
        return null;
      }
    }
    return null;
  }

  private static BigDecimal decimal(Object value) {
    return new BigDecimal(String.valueOf(value).trim());
  }

  private static JSONObject error(String code, Object... keyValues) {
    try {
      JSONObject error = new JSONObject();
      error.put(CODE, code);
      for (int i = 0; i + 1 < keyValues.length; i += 2) {
        error.put(String.valueOf(keyValues[i]), keyValues[i + 1]);
      }
      return error;
    } catch (JSONException e) {
      throw new IllegalStateException("Could not build a validation error", e);
    }
  }

  private static String summary(Map<String, List<JSONObject>> errors) {
    List<String> parts = new ArrayList<>();
    for (Map.Entry<String, List<JSONObject>> entry : errors.entrySet()) {
      List<String> codes = new ArrayList<>();
      for (JSONObject error : entry.getValue()) {
        codes.add(error.optString(CODE));
      }
      parts.add(entry.getKey() + "=" + codes);
    }
    return String.join(", ", parts);
  }

  static NeoResponse toResponse(Map<String, List<JSONObject>> errors) {
    try {
      JSONObject fields = new JSONObject();
      for (Map.Entry<String, List<JSONObject>> entry : errors.entrySet()) {
        fields.put(entry.getKey(), new JSONArray(entry.getValue()));
      }
      JSONObject error = new JSONObject();
      error.put(CODE, CODE_VALIDATION_FAILED);
      error.put("message", "Validation failed");
      error.put("status", HttpServletResponse.SC_BAD_REQUEST);
      error.put("fields", fields);
      return NeoResponse.error(HttpServletResponse.SC_BAD_REQUEST,
          new JSONObject().put("error", error));
    } catch (JSONException e) {
      throw new IllegalStateException("Could not build the validation response", e);
    }
  }
}
