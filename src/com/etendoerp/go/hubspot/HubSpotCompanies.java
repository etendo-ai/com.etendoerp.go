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

package com.etendoerp.go.hubspot;

import static com.etendoerp.go.hubspot.HubSpotValues.association;
import static com.etendoerp.go.hubspot.HubSpotValues.describe;
import static com.etendoerp.go.hubspot.HubSpotValues.encode;

import java.util.Locale;
import java.util.Set;

import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONException;
import org.codehaus.jettison.json.JSONObject;

import com.etendoerp.go.hubspot.HubSpotClient.HubSpotUnreachableException;
import com.etendoerp.go.hubspot.HubSpotClient.Response;
import com.etendoerp.go.hubspot.HubSpotEventSender.CallFailed;
import com.etendoerp.go.hubspot.HubSpotEventSender.Outcome;

/**
 * The company of a self-serve account's deal. ETP-6363 (marketing's "Pendientes de medición en la
 * app", 4.1): a company whose domain HubSpot already has is reused, so the people of one company
 * do not each create their own; an address of a personal mail provider has no company domain, and
 * gets a company of its own named as the owner entered it.
 *
 * <p>A company is a courtesy: the deal is what links HubSpot with Etendo, so a refusal (a token
 * without the companies scope, for one) leaves the deal without a company instead of holding the
 * deal back. Only a busy or failing HubSpot is retried.
 */
class HubSpotCompanies {

  private static final Logger log = LogManager.getLogger(HubSpotCompanies.class);

  // A path of the HubSpot API, appended to the configurable base URL (java:S1075 does not apply).
  private static final String COMPANIES_PATH = "/crm/v3/objects/companies"; // NOSONAR
  private static final String SEARCH_PATH = COMPANIES_PATH + "/search";
  private static final String PROPERTIES = "properties";
  private static final String DOMAIN = "domain";

  /**
   * Mail providers whose domain names no company: an owner writing from one of them gets a company
   * of their own. Wider than marketing's four, with the Spanish and Argentine variants.
   */
  static final Set<String> PERSONAL_DOMAINS = Set.of("gmail.com", "googlemail.com",
      "hotmail.com", "hotmail.es", "hotmail.com.ar", "outlook.com", "outlook.es",
      "outlook.com.ar", "live.com", "live.es", "live.com.ar", "msn.com", "yahoo.com", "yahoo.es",
      "yahoo.com.ar", "ymail.com", "icloud.com", "me.com", "mac.com", "aol.com", "protonmail.com",
      "proton.me", "gmx.com", "gmx.es", "mail.com", "zoho.com", "yandex.com", "terra.es",
      "telefonica.net", "fibertel.com.ar", "arnet.com.ar", "speedy.com.ar");

  private final HubSpotClient client;

  HubSpotCompanies(HubSpotClient client) {
    this.client = client;
  }

  /**
   * The company of the contact: the one HubSpot has with the domain of the address, associated
   * with the contact; else a new one.
   *
   * @param name      the company name the owner entered, which names a new company only
   * @param country   the company's country, or {@code null}
   * @param email     the owner's address, whose domain identifies the company
   * @param contactId the contact to associate it with
   * @return the company's id, or {@code null} when there is none
   */
  String forContact(String name, String country, String email, String contactId)
      throws CallFailed, JSONException {
    String domain = companyDomain(email);
    if (domain != null) {
      String existing = findByDomain(domain);
      if (existing != null) {
        associate(existing, contactId);
        return existing;
      }
    }
    return StringUtils.isBlank(name) ? null : create(name, country, domain, contactId);
  }

  /**
   * The domain of a company address, lower-cased; {@code null} for a personal mail provider or an
   * address without one.
   */
  static String companyDomain(String email) {
    String domain = StringUtils.lowerCase(
        StringUtils.trimToNull(StringUtils.substringAfterLast(email, "@")), Locale.ROOT);
    return domain == null || !domain.contains(".") || PERSONAL_DOMAINS.contains(domain) ? null
        : domain;
  }

  private String findByDomain(String domain) throws CallFailed, JSONException {
    JSONObject filter = new JSONObject().put("propertyName", DOMAIN)
        .put("operator", "EQ")
        .put("value", domain);
    JSONObject body = new JSONObject()
        .put("filterGroups", new JSONArray().put(new JSONObject()
            .put("filters", new JSONArray().put(filter))))
        .put(PROPERTIES, new JSONArray().put(DOMAIN))
        .put("limit", 1);
    Response response = request("POST", SEARCH_PATH, body);
    if (!response.isSuccess()) {
      courtesyRefused("look up the company of " + domain, "POST", SEARCH_PATH, response);
      return null;
    }
    JSONArray results = new JSONObject(response.body()).optJSONArray("results");
    return results == null || results.length() == 0 ? null
        : StringUtils.trimToNull(results.getJSONObject(0).optString("id", null));
  }

  private void associate(String companyId, String contactId) throws CallFailed {
    String path = "/crm/v4/objects/companies/" + encode(companyId) // NOSONAR - see COMPANIES_PATH
        + "/associations/default/contacts/" + encode(contactId);
    Response response = request("PUT", path, null);
    if (!response.isSuccess()) {
      courtesyRefused("associate company " + companyId + " with its contact", "PUT", path,
          response);
    }
  }

  private String create(String name, String country, String domain, String contactId)
      throws CallFailed, JSONException {
    JSONObject properties = new JSONObject().put("name", name);
    if (StringUtils.isNotBlank(country)) {
      properties.put("country", country);
    }
    // Recorded, so the next sign-up of the same company finds it.
    if (domain != null) {
      properties.put(DOMAIN, domain);
    }
    JSONObject body = new JSONObject()
        .put(PROPERTIES, properties)
        .put("associations", new JSONArray()
            .put(association(contactId, HubSpotEventSender.COMPANY_TO_CONTACT)));
    Response response = request("POST", COMPANIES_PATH, body);
    if (!response.isSuccess()) {
      courtesyRefused("create the company '" + name + "'", "POST", COMPANIES_PATH, response);
      return null;
    }
    return StringUtils.trimToNull(new JSONObject(response.body()).optString("id", null));
  }

  /** A busy or failing HubSpot is retried; any other refusal is logged and given up. */
  private static void courtesyRefused(String what, String method, String path,
      Response response) throws CallFailed {
    String reason = describe(method, path, response);
    if (response.status() == 429 || response.status() >= 500) {
      throw new CallFailed(Outcome.retry(reason));
    }
    log.warn("HubSpot refused to {}; the deal goes on without it: {}", what, reason);
  }

  private Response request(String method, String path, JSONObject body) throws CallFailed {
    try {
      return client.send(method, path, body);
    } catch (HubSpotUnreachableException e) {
      throw new CallFailed(Outcome.retry(e.getMessage()));
    }
  }
}
