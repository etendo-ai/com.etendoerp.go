/*
 *************************************************************************
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
 *************************************************************************
 */
package com.etendoerp.go.schemaforge;

import org.codehaus.jettison.json.JSONException;
import org.codehaus.jettison.json.JSONObject;

/**
 * ETP-6225 — the whitespace policy of a contact phone ({@code etgoPhone}). Called only from
 * {@link BusinessPartnerHandler}, so it applies on every channel that resolves that customization
 * (REST single, REST batch, MCP).
 */
final class ContactPhoneText {

  static final String FIELD_PHONE = "etgoPhone";

  private ContactPhoneText() {
  }

  /**
   * Drops every whitespace character from an incoming {@code etgoPhone} before it is validated
   * and stored: spreadsheets, the export and people type "+34 910 565 400", and the 15-character
   * cap is meant for the number, not its spacing, so "+34 (910) 565-400" fits once stripped. Done
   * server-side, so every channel (form, import, batch, MCP) stores the same value. A leading
   * apostrophe is deliberately left in place: it stays invalid.
   */
  static void stripWhitespace(JSONObject body) throws JSONException {
    if (!body.has(FIELD_PHONE) || body.isNull(FIELD_PHONE)) {
      return;
    }
    Object raw = body.get(FIELD_PHONE);
    if (raw instanceof String) {
      body.put(FIELD_PHONE, withoutWhitespace((String) raw));
    }
  }

  /** {@code value} without any whitespace (NBSP included); {@code null} stays {@code null}. */
  static String withoutWhitespace(String value) {
    return value == null ? null : value.replaceAll("[\\s\\u00A0\\uFEFF]+", "");
  }
}
