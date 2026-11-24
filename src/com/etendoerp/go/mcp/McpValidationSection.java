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
package com.etendoerp.go.mcp;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONObject;

/**
 * The {@code validation} section of a field's {@code MCP_CONFIG} (ETP-4558): the declarative
 * constraints NEO enforces on REST/batch writes before any customization or persistence runs.
 *
 * <pre>
 * { "validation": { "required": true, "minLength": 1, "maxLength": 60, "minimum": 0,
 *                   "maximum": 100, "format": "email", "enum": ["A","B"],
 *                   "allowedSchemes": ["https"] } }
 * </pre>
 *
 * <p>It is the same object {@code schema_forge_core} publishes per field in the contract, so the
 * push step copies it verbatim. Only the {@code field} level is read. Each key is optional and
 * independent. Keys this server does not know (a newer contract) are ignored and logged once; a known
 * key of the wrong type makes the section unusable. Enforcement lives in
 * {@code NeoDeclarativeValidation}; the key names below are shared with it.</p>
 */
public final class McpValidationSection {

  static final String NAME = "validation";

  public static final String REQUIRED = "required";
  public static final String MIN_LENGTH = "minLength";
  public static final String MAX_LENGTH = "maxLength";
  public static final String MINIMUM = "minimum";
  public static final String MAXIMUM = "maximum";
  public static final String FORMAT = "format";
  public static final String ENUM = "enum";
  public static final String ALLOWED_SCHEMES = "allowedSchemes";

  private static final Logger log = LogManager.getLogger(McpValidationSection.class);
  /** Unknown keys already reported, so a newer contract logs once per key, not once per read. */
  private static final Set<String> REPORTED_UNKNOWN = ConcurrentHashMap.newKeySet();

  private static final Set<String> NUMBER_KEYS = Set.of(MIN_LENGTH, MAX_LENGTH, MINIMUM, MAXIMUM);
  private static final Set<String> ARRAY_KEYS = Set.of(ENUM, ALLOWED_SCHEMES);
  private static final Set<String> ALLOWED_KEYS = Set.of(REQUIRED, MIN_LENGTH, MAX_LENGTH,
      MINIMUM, MAXIMUM, FORMAT, ENUM, ALLOWED_SCHEMES);

  private static final McpConfigSection DECLARATION = McpConfigSection.ofOpenKeys(NAME, ALLOWED_KEYS,
      McpConfigSection.Merge.REPLACE, McpValidationSection::validate);

  private McpValidationSection() {
  }

  static McpConfigSection declaration() {
    return DECLARATION;
  }

  /**
   * Validate the section body on its own terms.
   *
   * @param body the section body
   * @return the problems found, empty when usable
   */
  static List<String> validate(JSONObject body) {
    List<String> problems = new ArrayList<>();
    if (body.length() == 0) {
      problems.add("the 'validation' section is empty - remove it or declare a constraint");
      return problems;
    }
    reportUnknownKeys(body);
    checkRequired(body, problems);
    checkNumbers(body, problems);
    checkFormat(body, problems);
    checkArrays(body, problems);
    return problems;
  }

  private static void checkRequired(JSONObject body, List<String> problems) {
    if (body.has(REQUIRED) && !(body.opt(REQUIRED) instanceof Boolean)) {
      problems.add("section 'validation': 'required' must be a boolean");
    }
  }

  private static void checkNumbers(JSONObject body, List<String> problems) {
    for (String key : NUMBER_KEYS) {
      if (body.has(key) && !(body.opt(key) instanceof Number)) {
        problems.add("section 'validation': '" + key + "' must be a number");
      }
    }
  }

  private static void checkFormat(JSONObject body, List<String> problems) {
    if (!body.has(FORMAT)) {
      return;
    }
    Object format = body.opt(FORMAT);
    if (!(format instanceof String) || ((String) format).trim().isEmpty()) {
      problems.add("section 'validation': 'format' must be a non-empty string");
    }
  }

  private static void checkArrays(JSONObject body, List<String> problems) {
    for (String key : ARRAY_KEYS) {
      if (body.has(key) && !isNonEmptyArray(body.opt(key))) {
        problems.add("section 'validation': '" + key + "' must be a non-empty array");
      }
    }
  }

  private static boolean isNonEmptyArray(Object value) {
    return value instanceof JSONArray && ((JSONArray) value).length() > 0;
  }

  /** Keys this server does not know are ignored (a newer contract), logged once per key. */
  private static void reportUnknownKeys(JSONObject body) {
    for (Iterator<?> it = body.keys(); it.hasNext();) {
      String key = String.valueOf(it.next());
      if (!ALLOWED_KEYS.contains(key) && REPORTED_UNKNOWN.add(key)) {
        log.warn("MCP_CONFIG 'validation' has an unknown constraint '{}': ignored", key);
      }
    }
  }
}
