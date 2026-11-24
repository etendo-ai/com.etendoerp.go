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
import java.util.Set;

import javax.inject.Named;

import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONObject;
import org.openbravo.base.provider.OBProvider;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.core.SessionHandler;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.common.geography.Country;
import org.openbravo.model.common.geography.Location;
import org.openbravo.model.common.geography.Region;

import com.etendoerp.go.common.GoAccountResolver;
import com.etendoerp.go.schemaforge.util.StructuredAddressComposer;
import com.etendoerp.go.schemaforge.util.StructuredAddressComposer.ComposedAddress;

/**
 * NeoHandler for the tab-less {@code location} entity in the {@code warehouse} spec.
 *
 * <p>Manages a plain C_Location (physical address) with NO C_BPartner_Location link, so the
 * Warehouse window's {@code Location / Address} field can create and edit its address inline
 * (ETP-4526). The frontend {@code LocationEditorModal} (saveMode="location") calls:
 *
 * <ul>
 *   <li>POST /warehouse/location        — creates a C_Location, returns its id + display name</li>
 *   <li>PUT  /warehouse/location/{id}   — updates the C_Location by id</li>
 *   <li>GET  /warehouse/location/{id}   — returns the address fields to pre-populate the modal</li>
 * </ul>
 *
 * <p>Country/Region pickers are served generically by {@link NeoSelectorService} from the
 * entity's ETGO_SF_FIELD rows (C_Country_ID / C_Region_ID) — no code here.
 *
 * <p>The entity has no AD_Tab, so there is no generic-CRUD fallback: this handler fully owns
 * POST, PUT and GET-by-id. Any other method returns 405.
 *
 * <p>Registered via {@code JAVA_QUALIFIER = 'warehouseLocationHandler'} on the ETGO_SF_ENTITY
 * record for {@code location} in the {@code warehouse} spec, and (ETP-5806) also via
 * {@link NeoExtension}, resolved first. Both bindings name this class, so
 * {@code NeoExtensionIndex.warnOnQualifierDisagreement} never fires for it. Uses {@code @Named}
 * (never a normal CDI scope), so handler discovery via {@code @Named} on the concrete class works.
 *
 * <p><b>Also serves the Organization fiscal address</b>, not only the Warehouse window:
 * {@code OrganizationPage.jsx} points its {@code LocationModalField} at the {@code warehouse} base
 * URL on purpose, because no {@code location} entity was ever registered under the
 * {@code organization} spec. Any change to the read or write shape below must be exercised on both
 * windows; the fiscal address is the one feeding ETP-5806's SEPA remittance criterion.
 *
 * <p><b>Why there is no {@code responseEnrichedFields()} override here, unlike
 * {@link ContactsLocationAddressHandler} (ETP-5806 CP-12h).</b> That handler needs one because its
 * entity is a tabbed wrapper: the read runs the generic path, where
 * {@code McpQuerySupport#applyProjection} judges a caller's {@code fields:[…]} against the spec's
 * own {@code ETGO_SF_FIELD} rows and so cannot see the keys injected from the neighbouring table.
 * This entity is tab-less, so {@link #handle} serves the whole read in the READ pre-phase
 * ({@code McpHookExecutor#runReadProvider}) and {@code McpProvidedReadShaper#project} derives the
 * emittable set from <b>the rows themselves</b>; {@code applyProjection} is never reached, and
 * {@code etendo_schema} — the only other reader — refuses a tab-less entity outright. A declaration
 * would be dead code. What keeps the projection honest instead is that
 * {@link #buildRecord} names <b>every</b> emittable key unconditionally, writing
 * {@code JSONObject.NULL} rather than omitting it: drop a null key and that key stops being
 * emittable, so a caller asking for it on a record that merely has no value would be told it is
 * unknown. Pinned by {@code WarehouseLocationHandlerTest}'s two CP-12h tests.
 */
@Named("warehouseLocationHandler")
@NeoExtension(spec = "warehouse", entity = "location")
public class WarehouseLocationHandler implements NeoHandler {

  private static final Logger log = LogManager.getLogger(WarehouseLocationHandler.class);
  private static final String FIELD_COUNTRY = "country";
  private static final String FIELD_REGION = "region";
  private static final String FIELD_RESPONSE = "response";
  private static final String FIELD_DATA = "data";
  /**
   * ETP-5806 — structured SEPA address fields, backed by {@code org.openbravo.sepa.iso20022}'s
   * typed {@code Location} getters/setters (see {@link #applyGeoLocFields}).
   */
  private static final String FIELD_STREET_NAME = "streetName";
  private static final String FIELD_BUILDING_NUMBER = "buildingNumber";
  private static final String FIELD_BUILDING_NAME = "buildingName";
  private static final String FIELD_CITY_NAME = "cityName";

  /**
   * ETP-5806 CP-13 (QA finding) — the structured-address fields this handler requires from the
   * caller, so {@code etendo_schema(view:"create")} lists them under {@code required} instead of
   * under an {@code optional} group whose own hint says they are "accepted but not needed".
   *
   * <p>Neither {@code EM_OBSEPA_StrtNm} nor {@code C_Location.City} is NOT NULL in AD, so the
   * generic rule cannot see this mandatoriness: it is the composer's product rule, and only a
   * customization can declare it. The set is empty while {@code structured-address-for-sepa} is off
   * for the caller's account, which republishes the pre-ETP-5806 (country-only) contract unchanged.
   *
   * @return the caller-required create fields; never {@code null}
   */
  @Override
  public Set<String> callerRequiredCreateFields() {
    return StructuredAddressComposer.callerRequiredFields(GoAccountResolver.currentAccountEmail());
  }

  /**
   * ETP-5806 CP-13 (QA finding) — {@code buildingNumber}, which this handler resolves itself: a
   * blank or omitted value becomes {@code "s/n"}
   * ({@link StructuredAddressComposer#effectiveBuildingNumber}). Declaring it here is what moves it
   * to {@code optional} carrying {@code serverDefaulted: true} — "mandatory in Etendo but the
   * server already has a value, so do not ask the user" — rather than leaving it indistinguishable
   * from a field nobody needs.
   *
   * @return the server-resolved create fields; never {@code null}
   */
  @Override
  public Set<String> serverResolvedCreateFields() {
    return StructuredAddressComposer.serverDefaultedFields(GoAccountResolver.currentAccountEmail());
  }

  @Override
  public NeoResponse handle(NeoContext ctx) {
    // This handler is invoked as a pre-hook for EVERY sub-endpoint on the entity, not just
    // plain CRUD (selectors, callout, defaults, evaluate-display all route through the same
    // Java_Qualifier). Only take over the record-level CRUD path; let every other endpoint
    // type fall through to its generic default (e.g. NeoSelectorService for country/region).
    if (ctx.getEndpointType() != NeoEndpointType.CRUD) {
      return null;
    }
    String method = ctx.getHttpMethod();
    String recordId = ctx.getRecordId();
    // ETP-5806 — resolved ONCE per request, here, and threaded through every method below that
    // gates on it: validation, the write mapping and the response shape must all answer for the
    // same account, and re-resolving per call site would also repeat the account lookup 3 times
    // on a single create. No ordering trap (see BpLocationHandler#handle): NEO authenticates
    // before dispatch, so the enclosing admin mode has not replaced the caller.
    boolean structuredAddressEnabled =
        StructuredAddressComposer.isEnabled(GoAccountResolver.currentAccountEmail());
    try {
      if ("POST".equals(method) && recordId == null) {
        return handleCreate(ctx, structuredAddressEnabled);
      }
      if ("PUT".equals(method) && recordId != null) {
        return handleUpdate(ctx, structuredAddressEnabled);
      }
      if ("GET".equals(method) && recordId != null) {
        return handleGetById(recordId, structuredAddressEnabled);
      }
    } catch (Exception e) {
      SessionHandler.getInstance().rollback();
      log.error("WarehouseLocationHandler error in {}", method, e);
      return NeoResponse.error(500, "Location handler error: " + e.getMessage());
    }
    // The entity is tab-less: there is no default CRUD to fall through to for anything else
    // (GET list, DELETE) on the CRUD path itself.
    return NeoResponse.error(405, "Unsupported operation on location: " + method);
  }

  // ------------------------------------------------------------------ create

  private NeoResponse handleCreate(NeoContext ctx, boolean structuredAddressEnabled)
      throws Exception {
    JSONObject body = ctx.getRequestBody();
    OBContext obCtx = ctx.getObContext();

    NeoResponse rejected = rejectIncompleteAddressPayload(body,
        nullIfEmpty(body.optString(FIELD_COUNTRY, null)), structuredAddressEnabled);
    if (rejected != null) {
      return rejected;
    }

    OBContext.setAdminMode(true);
    try {
      Location geoLoc = OBProvider.getInstance().get(Location.class);
      geoLoc.setClient(OBDal.getInstance().get(
          org.openbravo.model.ad.system.Client.class, obCtx.getCurrentClient().getId()));
      geoLoc.setOrganization(OBDal.getInstance().get(
          org.openbravo.model.common.enterprise.Organization.class, obCtx.getCurrentOrganization().getId()));
      geoLoc.setActive(Boolean.TRUE);
      applyGeoLocFields(body, geoLoc, structuredAddressEnabled);
      OBDal.getInstance().save(geoLoc);
      OBDal.getInstance().flush();

      return wrapRecord(buildRecord(geoLoc, structuredAddressEnabled), 201);
    } finally {
      OBContext.restorePreviousMode();
    }
  }

  // ------------------------------------------------------------------ update

  private NeoResponse handleUpdate(NeoContext ctx, boolean structuredAddressEnabled)
      throws Exception {
    JSONObject body = ctx.getRequestBody();
    String locId = ctx.getRecordId();

    OBContext.setAdminMode(true);
    try {
      // ETP-5819: another tenant's address is a 404, like a missing one.
      Location geoLoc = TenantOwnership.loadOwned(Location.class, locId);
      if (geoLoc == null) {
        return NeoResponse.error(404, "Location not found: " + locId);
      }
      String bodyCountryId = nullIfEmpty(body.optString(FIELD_COUNTRY, null));
      String effectiveCountryId = resolveEffectiveCountryId(bodyCountryId, geoLoc);
      NeoResponse rejected = rejectIncompleteAddressPayload(body, effectiveCountryId,
          structuredAddressEnabled);
      if (rejected != null) {
        return rejected;
      }
      applyGeoLocFields(body, geoLoc, structuredAddressEnabled);
      OBDal.getInstance().flush();

      return wrapRecord(buildRecord(geoLoc, structuredAddressEnabled), 200);
    } finally {
      OBContext.restorePreviousMode();
    }
  }

  /**
   * ETP-5806 — country falls back to the existing record's own country when the body omits it.
   */
  private static String resolveEffectiveCountryId(String bodyCountryId, Location geoLoc) {
    if (bodyCountryId != null) {
      return bodyCountryId;
    }
    return geoLoc.getCountry() != null ? geoLoc.getCountry().getId() : null;
  }

  // ------------------------------------------------------------------ get by id

  private NeoResponse handleGetById(String locId, boolean structuredAddressEnabled)
      throws Exception {
    OBContext.setAdminMode(true);
    try {
      // ETP-5819: another tenant's address is a 404, like a missing one.
      Location geoLoc = TenantOwnership.loadOwned(Location.class, locId);
      if (geoLoc == null) {
        return NeoResponse.error(404, "Location not found: " + locId);
      }
      return wrapRecord(buildRecord(geoLoc, structuredAddressEnabled), 200);
    } finally {
      OBContext.restorePreviousMode();
    }
  }

  // ------------------------------------------------------------------ validation

  /**
   * ETP-5806 — Street, City and Country are mandatory for the Warehouse location, the same product
   * decision as {@code ContactsLocationAddressHandler} (Etendo Go is stricter than Classic, where
   * the 3 SEPA fields are optional). Building Number is not independently mandatory — a
   * blank/omitted value falls back to {@code "s/n"} when Street Name is present (ETP-5806
   * follow-up); see {@link StructuredAddressComposer#effectiveBuildingNumber}. This entity is
   * tab-less and has no "reuse an existing C_Location" mode, so the check applies unconditionally
   * on both create and update.
   */
  private static NeoResponse rejectIncompleteAddressPayload(JSONObject body, String countryId,
      boolean structuredAddressEnabled) {
    // ETP-5806 — `structured-address-for-sepa` off FOR THIS ACCOUNT: nothing to validate for
    // fields that do not exist on the wire; fall back to the pre-ETP-5806 rule (country only).
    if (!structuredAddressEnabled) {
      return countryId == null
          ? NeoResponse.error(400, "Missing required field: country (C_Country_ID)") : null;
    }
    // trimToNull, not nullIfEmpty: a streetName/buildingNumber of "   " is visually empty to
    // whoever typed it, and nullIfEmpty only rejects "". Left untrimmed a value like " Main St"
    // would pass both the mandatory and max-length checks below with a leading space baked in.
    String streetName = StringUtils.trimToNull(nullIfEmpty(body.optString(FIELD_STREET_NAME, null)));
    // ETP-5806 follow-up (QA finding) — a blank/omitted Building Number with a present Street Name
    // is not missing, it falls back to "s/n", matching the frontend convention. See
    // StructuredAddressComposer#effectiveBuildingNumber.
    String buildingNumber = StructuredAddressComposer.effectiveBuildingNumber(streetName,
        StringUtils.trimToNull(nullIfEmpty(body.optString(FIELD_BUILDING_NUMBER, null))));
    List<String> missing = StructuredAddressComposer.missingMandatoryFields(
        streetName,
        buildingNumber,
        nullIfEmpty(body.optString(FIELD_CITY_NAME, null)),
        countryId);
    if (!missing.isEmpty()) {
      return NeoResponse.error(400, "Missing required field(s): " + String.join(", ", missing));
    }
    // ETP-5806 follow-up — see StructuredAddressComposer#fieldsExceedingMaxLength: without this,
    // a value longer than the DB column (EM_OBSEPA_StrtNm/BldgNb/BldgNm) reached OBDal.flush() as
    // a raw data-truncation SQL exception, surfaced as an opaque 500 on both create and update.
    List<String> tooLong = StructuredAddressComposer.fieldsExceedingMaxLength(
        streetName,
        buildingNumber,
        StringUtils.trimToNull(nullIfEmpty(body.optString(FIELD_BUILDING_NAME, null))));
    if (!tooLong.isEmpty()) {
      return NeoResponse.error(400, "Field(s) exceed maximum length: " + String.join(", ", tooLong));
    }
    return null;
  }

  // ------------------------------------------------------------------ field mapping

  private static void applyGeoLocFields(JSONObject body, Location geoLoc,
      boolean structuredAddressEnabled) {
    // ETP-5806 — see ContactsLocationAddressHandler#applyGeoLocFields for why the 3 fields are
    // ignored entirely (not read, not written) while `structured-address-for-sepa` is off for the
    // requesting account (resolved once in handle(), threaded here).
    // trimToNull, not nullIfEmpty: see rejectIncompleteAddressPayload above for why these 3
    // fields are trimmed before being validated and persisted.
    String streetName = structuredAddressEnabled
        ? StringUtils.trimToNull(nullIfEmpty(body.optString(FIELD_STREET_NAME, null))) : null;
    // ETP-5806 follow-up — same "s/n" fallback applied during validation; persisting the raw
    // (possibly blank) buildingNumber here would silently disagree with what was just validated.
    String buildingNumber = structuredAddressEnabled
        ? StructuredAddressComposer.effectiveBuildingNumber(streetName,
            StringUtils.trimToNull(nullIfEmpty(body.optString(FIELD_BUILDING_NUMBER, null))))
        : null;
    String buildingName = structuredAddressEnabled
        ? StringUtils.trimToNull(nullIfEmpty(body.optString(FIELD_BUILDING_NAME, null))) : null;
    if (structuredAddressEnabled) {
      geoLoc.setOBSEPAStreetName(streetName);
      geoLoc.setOBSEPABuildingNumber(buildingNumber);
      geoLoc.setOBSEPABuildingName(buildingName);
    }

    // ETP-5806 — same compute-only-when-street-present rule as ContactsLocationAddressHandler.
    // See the comment there for the Classic-parity rationale.
    if (streetName != null) {
      ComposedAddress composed =
          StructuredAddressComposer.composeAddressLines(streetName, buildingNumber, buildingName);
      geoLoc.setAddressLine1(composed.getAddress1());
      geoLoc.setAddressLine2(composed.getAddress2());
    } else {
      geoLoc.setAddressLine1(nullIfEmpty(body.optString("addressLine1", null)));
      geoLoc.setAddressLine2(nullIfEmpty(body.optString("addressLine2", null)));
    }
    geoLoc.setCityName(nullIfEmpty(body.optString(FIELD_CITY_NAME, null)));
    geoLoc.setPostalCode(nullIfEmpty(body.optString("postalCode", null)));

    String countryId = nullIfEmpty(body.optString(FIELD_COUNTRY, null));
    if (countryId != null) {
      Country country = OBDal.getInstance().get(Country.class, countryId);
      if (country != null) {
        geoLoc.setCountry(country);
      }
    }

    if (body.has(FIELD_REGION)) {
      String regionId = nullIfEmpty(body.optString(FIELD_REGION, null));
      geoLoc.setRegion(regionId != null ? OBDal.getInstance().get(Region.class, regionId) : null);
    }
  }

  private static JSONObject buildRecord(Location geoLoc, boolean structuredAddressEnabled)
      throws Exception {
    JSONObject json = new JSONObject();
    json.put("id", geoLoc.getId());
    json.put("name", buildDisplayName(geoLoc));
    json.put("addressLine1", orNull(geoLoc.getAddressLine1()));
    json.put("addressLine2", orNull(geoLoc.getAddressLine2()));
    json.put(FIELD_CITY_NAME, orNull(geoLoc.getCityName()));
    json.put("postalCode", orNull(geoLoc.getPostalCode()));
    // ETP-5806 — structured SEPA address fields, so the modal can pre-populate them. Omitted
    // entirely (not even as null) while `structured-address-for-sepa` is off.
    if (structuredAddressEnabled) {
      json.put(FIELD_STREET_NAME, orNull(geoLoc.getOBSEPAStreetName()));
      json.put(FIELD_BUILDING_NUMBER, orNull(geoLoc.getOBSEPABuildingNumber()));
      json.put(FIELD_BUILDING_NAME, orNull(geoLoc.getOBSEPABuildingName()));
    }
    if (geoLoc.getCountry() != null) {
      json.put(FIELD_COUNTRY, geoLoc.getCountry().getId());
      // ETP-5022: getIdentifier(), NOT getName(). getName() is the plain Hibernate getter —
      // it calls get(prop) with no language, so it never consults C_Country_Trl and returns
      // the base-language name ("Spain") even when the request carries Accept-Language: es_ES.
      // getIdentifier() goes through IdentifierProvider, which passes the record id and so
      // resolves the translation. Country's identifier is a single column (Name), so the text
      // shown is unchanged apart from being translated.
      // Region is deliberately left on getName(): C_Region has no _Trl table, so there is
      // nothing to translate and the two calls would be equivalent.
      json.put("country$_identifier", geoLoc.getCountry().getIdentifier());
    } else {
      json.put(FIELD_COUNTRY, JSONObject.NULL);
      json.put("country$_identifier", JSONObject.NULL);
    }
    if (geoLoc.getRegion() != null) {
      json.put(FIELD_REGION, geoLoc.getRegion().getId());
      json.put("region$_identifier", geoLoc.getRegion().getName());
    } else {
      json.put(FIELD_REGION, JSONObject.NULL);
      json.put("region$_identifier", JSONObject.NULL);
    }
    return json;
  }

  private static NeoResponse wrapRecord(JSONObject json, int httpStatus) throws Exception {
    JSONArray dataArr = new JSONArray();
    dataArr.put(json);
    JSONObject responseData = new JSONObject();
    responseData.put("status", 0);
    responseData.put(FIELD_DATA, dataArr);
    JSONObject wrapper = new JSONObject();
    wrapper.put(FIELD_RESPONSE, responseData);
    return new NeoResponse(httpStatus, wrapper);
  }

  // ------------------------------------------------------------------ helpers

  private static String buildDisplayName(Location geoLoc) {
    String result = joinNonNull(nullIfEmpty(geoLoc.getCityName()), nullIfEmpty(geoLoc.getAddressLine1()));
    if (result != null) {
      return result;
    }
    String region = geoLoc.getRegion() != null ? nullIfEmpty(geoLoc.getRegion().getName()) : null;
    // ETP-5022: getIdentifier() translates, getName() does not — same reason as the
    // country$_identifier field above. This fallback is what the UI shows when the location has
    // no city or street, so leaving it on getName() kept the country untranslated there.
    // Safe because this name is computed for the response only and never written back.
    String country = geoLoc.getCountry() != null ? nullIfEmpty(geoLoc.getCountry().getIdentifier()) : null;
    result = joinNonNull(region, country);
    return result != null ? result : "Location";
  }

  private static Object orNull(String s) {
    return s != null ? s : JSONObject.NULL;
  }

  private static String joinNonNull(String... parts) {
    StringBuilder sb = new StringBuilder();
    for (String part : parts) {
      if (part != null) {
        if (sb.length() > 0) {
          sb.append(", ");
        }
        sb.append(part);
      }
    }
    return sb.length() > 0 ? sb.toString() : null;
  }

  private static String nullIfEmpty(String s) {
    return (s == null || s.isEmpty() || "null".equals(s)) ? null : s;
  }
}
