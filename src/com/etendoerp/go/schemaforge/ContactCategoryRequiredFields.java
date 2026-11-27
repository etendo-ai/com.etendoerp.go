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

import java.util.ArrayList;
import java.util.List;

import org.apache.commons.lang3.StringUtils;
import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONException;
import org.codehaus.jettison.json.JSONObject;

import com.etendoerp.go.schemaforge.handlers.ContactTypeRules;

/**
 * ETP-6469 — a contact category ({@code C_BP_Group}) always has a search key and a name: both
 * columns are {@code NOT NULL}. The generic create validation only checks the mandatory fields
 * the caller <i>sent</i>, and an update checks none, so a create without {@code name} or an update
 * that blanks it used to reach the database, fail there and be reclassified as a 400 only after
 * Hibernate had logged the SQL error. This rule refuses it in the entity pre-hook, before any SQL.
 *
 * <p>Called only from {@code BusinessPartnerCategoryHandler}, so it applies on every channel that
 * resolves that customization (REST single, REST batch, MCP), and it does not depend on the
 * {@code contact-category-type} feature flag.
 *
 * <ul>
 *   <li><b>Create</b> ({@code POST}): {@code searchKey} and {@code name} are required; absent,
 *       JSON null and blank all count as missing.</li>
 *   <li><b>Update</b> ({@code PATCH}/{@code PUT}, both partial in NEO): a field is judged only when
 *       the body carries its key, so an unrelated edit is never blocked; a key sent null or blank
 *       is refused.</li>
 * </ul>
 *
 * <p><b>Envelope</b>: a body sent as {@code {"data": {...}}} is judged inside the envelope, the
 * object generic CRUD persists (see {@link ContactIdentityRules#persistedSection}).
 */
public final class ContactCategoryRequiredFields {

  /** Catalog key (no {@code AD_MESSAGE} row): the category is missing its search key or name. */
  public static final String MSG_NAME_REQUIRED = "ETGO_BPGroupNameRequired";

  static final String FIELD_SEARCH_KEY = "searchKey";
  static final String FIELD_NAME = "name";

  private static final String METHOD_POST = "POST";

  private ContactCategoryRequiredFields() {
  }

  /**
   * Refuses a category write that leaves its search key or name missing or blank.
   *
   * @param ctx the request context
   * @return a 400 {@code MISSING_REQUIRED_FIELDS} naming the missing fields, or {@code null} to
   *     continue (also for any request that is not a CRUD write)
   */
  public static NeoResponse validate(NeoContext ctx) {
    if (!ContactTypeRules.isCrudWrite(ctx)) {
      return null;
    }
    JSONObject body = ContactIdentityRules.persistedSection(ctx.getRequestBody());
    boolean create = METHOD_POST.equals(ctx.getHttpMethod());
    List<String> missing = new ArrayList<>();
    for (String field : new String[] { FIELD_SEARCH_KEY, FIELD_NAME }) {
      if ((create || body.has(field)) && isBlank(body.opt(field))) {
        missing.add(field);
      }
    }
    if (missing.isEmpty()) {
      return null;
    }
    NeoResponse response = NeoCrudHandler.buildMissingRequiredFieldsResponse(missing,
        NeoMessageText.text(MSG_NAME_REQUIRED));
    JSONObject responseBody = response.getBody();
    JSONObject error = responseBody == null ? null : responseBody.optJSONObject("error");
    if (error != null) {
      try {
        error.put(NeoProcessService.MESSAGE_KEYS, new JSONArray().put(MSG_NAME_REQUIRED));
      } catch (JSONException ignored) {
        // the MISSING_REQUIRED_FIELDS body is still a valid answer
      }
    }
    return response;
  }

  /** Absent, JSON null or blank. Not {@code optString}: it turns a JSON null into {@code "null"}. */
  private static boolean isBlank(Object raw) {
    return raw == null || raw == JSONObject.NULL || StringUtils.isBlank(raw.toString());
  }
}
