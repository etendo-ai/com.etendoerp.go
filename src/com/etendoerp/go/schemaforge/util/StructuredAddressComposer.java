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
 * All portions are Copyright © 2021–2026 FUTIT SERVICES, S.L
 * All Rights Reserved.
 * Contributor(s): Futit Services S.L.
 * *************************************************************************
 */
package com.etendoerp.go.schemaforge.util;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import org.apache.commons.lang3.StringUtils;

import com.etendoerp.go.featureflags.FeatureFlagContext;
import com.etendoerp.go.featureflags.GoFeatureFlags;

/**
 * Composes {@code C_Location.Address1}/{@code Address2} from the structured SEPA address fields
 * (ETP-5806): Street Name, Building Number and Building Name.
 *
 * <p><b>This ports {@code LocationSEPA.java::fillAddressLines} EXACTLY</b> (Classic,
 * {@code org.openbravo.sepa.iso20022}, branch {@code epic/ETP-3504}, lines 146-180) — not the
 * simplified "Line1=Street+Number / Line2=BuildingName" rule described in the ticket text or the
 * design mockup. The acceptance criterion "an address created in Etendo Go looks the same in
 * Etendo Classic, and vice versa" requires real parity: the simplified rule would diverge from
 * Classic whenever the building name fits on the same 60-character line as street+number.
 *
 * <p>Used by both {@code ContactsLocationAddressHandler} and {@code WarehouseLocationHandler} so
 * the two entities that wrap a {@code C_Location} compute the exact same lines, on every channel
 * (REST and MCP reach the same handler code, so there is nothing channel-specific to port twice).
 *
 * <p><b>Gating is per-account (ETP-5806 staged rollout).</b> {@link #isEnabled(String)} takes the
 * {@code ETGO_ACCOUNT} email to evaluate the flag for; there is no account-less overload, by
 * design. Validation, persistence and MCP-schema exposure therefore resolve the flag for the same
 * account the capability signal the UI reads was resolved for, so a targeted account can never see
 * the structured fields in the UI while the backend silently drops what it fills in.
 */
public final class StructuredAddressComposer {

  /** Length of {@code C_Location.Address1}/{@code Address2} — NOT the 70 of EM_OBSEPA_StrtNm. */
  private static final int ADDRESS_LINE_MAX_LENGTH = 60;

  /**
   * Field lengths of the 3 structured SEPA columns themselves. Building Number and Building Name
   * mirror {@code org.openbravo.sepa.iso20022}'s own {@code AD_COLUMN.FIELDLENGTH} for
   * {@code EM_OBSEPA_BldgNb}/{@code BldgNm}: VARCHAR(16)/(35). ETP-5806 follow-up: neither the UI
   * nor this handler enforced them before a value was written, so a value longer than the DB
   * column reached {@code OBDal.flush()} as a raw data-truncation SQL exception — surfaced to the
   * caller as an opaque 500 with no indication of which field or why. {@link
   * #fieldsExceedingMaxLength} turns that into a clean 400 before the write is attempted, on both
   * create and update, for every handler that calls into this composer.
   *
   * <p><b>Street Name is 60, NOT the SEPA column's 70 (CP-6, agreed with product + QA).</b> The
   * underlying column, {@code EM_OBSEPA_StrtNm}, legitimately stays {@code VARCHAR(70)} — it is a
   * real SEPA/ISO20022 field other consumers may fill up to that width, and shrinking the column
   * itself would both break existing 61-70 char data on {@code update.database} and wrongly
   * restrict SEPA export for callers unrelated to this composer. The composer's OWN cap is 60,
   * stricter than the column, specifically so {@link #composeAddressLines}'s street-truncating
   * branches become structurally unreachable for valid input: a street that cannot fit on the
   * 60-char {@link #ADDRESS_LINE_MAX_LENGTH} line is now rejected here with a clean 400 before
   * {@code composeAddressLines} ever sees it, instead of being silently cut mid-word when composed
   * into {@code Address1}/{@code Address2}.
   */
  private static final int STREET_NAME_MAX_LENGTH = 60;
  private static final int BUILDING_NUMBER_MAX_LENGTH = 16;
  private static final int BUILDING_NAME_MAX_LENGTH = 35;

  private StructuredAddressComposer() {
  }

  /**
   * The Street Name cap this composer enforces — <b>60, not the 70 of {@code EM_OBSEPA_StrtNm}</b>.
   *
   * <p>ETP-5806 CP-6b (QA finding): {@code etendo_schema} derived every {@code maxLength} from the
   * AD column's own {@code FIELDLENGTH}, so it advertised 70 for a field {@link
   * #fieldsExceedingMaxLength} rejects at 61 — an agent that trusted the schema got a {@code 400
   * validation_error} for a value the schema had just told it was legal. The composer's caps are
   * the only enforced ones, so they are the only honest ones to publish; the schema now reads
   * them from here instead of from the column. See {@link #STREET_NAME_MAX_LENGTH} for why the
   * column itself legitimately stays wider.
   *
   * @return the maximum number of characters a Street Name may carry
   */
  public static int streetNameMaxLength() {
    return STREET_NAME_MAX_LENGTH;
  }

  /**
   * The Building Number cap this composer enforces (ETP-5806 CP-6b) — see {@link
   * #streetNameMaxLength()} for why the published schema reads the cap from here.
   *
   * @return the maximum number of characters a Building Number may carry
   */
  public static int buildingNumberMaxLength() {
    return BUILDING_NUMBER_MAX_LENGTH;
  }

  /**
   * The Building Name cap this composer enforces (ETP-5806 CP-6b) — see {@link
   * #streetNameMaxLength()} for why the published schema reads the cap from here.
   *
   * @return the maximum number of characters a Building Name may carry
   */
  public static int buildingNameMaxLength() {
    return BUILDING_NAME_MAX_LENGTH;
  }

  /**
   * Whether the {@code structured-address-for-sepa} backend-only flag (ETP-5806, TTL 2026-11-15)
   * is on <b>for the given account</b>.
   *
   * <p>The 3 call sites below — {@link #missingMandatoryFields}, {@link #fieldsExceedingMaxLength}
   * and {@link #composeAddressLines} — are each only meaningful once the structured fields exist
   * on the wire; every handler that calls into this composer MUST check this first and, when
   * {@code false}, skip straight to the pre-ETP-5806 legacy path (country-only mandatoriness,
   * {@code addressLine1}/{@code addressLine2} passed through as given) rather than calling any of
   * them. Centralized here rather than duplicated per handler because the composer is already the
   * one place {@code ContactsLocationAddressHandler}, {@code WarehouseLocationHandler} and {@code
   * BpLocationHandler} share for structured-address-for-sepa logic.
   *
   * <p><b>The account email is a parameter, not resolved here.</b> Each caller knows where its own
   * account comes from and nothing else does: a NEO/MCP request resolves the authenticated user's
   * account with {@code GoAccountResolver.currentAccountEmail()}, while the (pre-tenant)
   * onboarding path already has the account in hand and runs under an admin execution context
   * where no caller identity is recoverable. Resolving implicitly inside this method would be
   * wrong for the second group and would hide a DB lookup behind what reads as a constant. Same
   * explicit-at-the-call-site shape the capability signal uses ({@code SFWindowAccessMap},
   * {@code EtendoGoJwtServlet#buildSessionCapabilities}), so the backend's write/read gating and
   * the signal the UI renders from can never disagree for the same account.
   *
   * @param accountEmail the {@code ETGO_ACCOUNT} email to target the evaluation at; {@code null}
   *     or blank yields an untargeted evaluation ({@link FeatureFlagContext#forAccount(String)}),
   *     which every provider must treat as "no targeting key", never as "allow"
   * @return {@code true} when the flag resolves on for that account, {@code false} otherwise —
   *     including whenever it cannot be resolved at all, since the off state is the safe one
   */
  public static boolean isEnabled(String accountEmail) {
    return GoFeatureFlags.isEnabled(GoFeatureFlags.FLAG_STRUCTURED_ADDRESS_FOR_SEPA,
        FeatureFlagContext.forAccount(accountEmail));
  }

  /**
   * ETP-5806 CP-13 (QA finding) — the published field names a caller MUST supply on a
   * structured-address create, for {@code NeoHandler#callerRequiredCreateFields()}.
   *
   * <p><b>Why the schema cannot work this out by itself.</b> {@code etendo_schema} derives
   * mandatoriness from {@code AD_Column.isMandatory()}, and none of {@code EM_OBSEPA_StrtNm},
   * {@code EM_OBSEPA_BldgNb} or {@code C_Location.City} is NOT NULL — the mandatoriness is this
   * composer's own product rule, stricter than Classic's. So {@code view:"create"} answered
   * {@code requiredCount: 0} with {@code streetName}/{@code cityName} sitting under
   * {@code optional} and a hint reading "accepted but not needed", while the write path refused the
   * very create the schema had just described: {@code etendo_create bp-location
   * {cityName, country}} came back {@code 400 Missing required field(s): streetName}. The schema was
   * publishing the flag-OFF truth for an account the flag was ON for.
   *
   * <p>{@code country} is absent on purpose: its column IS mandatory in AD, so the generic rule
   * already reports it. {@code buildingName} is absent because it is genuinely optional, and
   * {@code buildingNumber} because the server resolves it — see {@link #serverDefaultedFields}.
   *
   * @param accountEmail the {@code ETGO_ACCOUNT} email to evaluate the flag for — see
   *     {@link #isEnabled(String)}
   * @return the caller-required field names while the flag is on for that account; empty when it is
   *     off, so the pre-ETP-5806 (country-only) mandatoriness is published unchanged
   */
  public static Set<String> callerRequiredFields(String accountEmail) {
    return isEnabled(accountEmail) ? Set.of(FIELD_STREET_NAME, "cityName") : Set.of();
  }

  /**
   * ETP-5806 CP-13 (QA finding) — the published field names the server resolves on a
   * structured-address create, for {@code NeoHandler#serverResolvedCreateFields()}.
   *
   * <p>{@code buildingNumber} and nothing else. It is mandatory in Etendo at the level of the
   * stored row and never at the level of what the caller supplies: a blank or omitted value becomes
   * {@code "s/n"} ({@link #effectiveBuildingNumber}). {@code view:"create"} has exact vocabulary for
   * that state — {@code optional} carrying {@code serverDefaulted: true}, which its own hint reads
   * as "mandatory in Etendo but the server already has a value for them, so do not ask the user" —
   * and this is what puts it there. Listing it under {@code required} instead would tell an agent to
   * interrogate a user for a door number the server fills in regardless; leaving it a bare
   * {@code optional} would hide that the column never ends up empty.
   *
   * @param accountEmail the {@code ETGO_ACCOUNT} email to evaluate the flag for — see
   *     {@link #isEnabled(String)}
   * @return {@code buildingNumber} while the flag is on for that account; empty when it is off
   */
  public static Set<String> serverDefaultedFields(String accountEmail) {
    return isEnabled(accountEmail) ? Set.of("buildingNumber") : Set.of();
  }

  /** The two computed address lines. Either may be {@code null} when empty. */
  public static final class ComposedAddress {
    private final String address1;
    private final String address2;

    private ComposedAddress(String address1, String address2) {
      this.address1 = address1;
      this.address2 = address2;
    }

    public String getAddress1() {
      return address1;
    }

    public String getAddress2() {
      return address2;
    }
  }

  /**
   * Composes Address1/Address2 from the structured fields, replicating
   * {@code LocationSEPA.fillAddressLines} line for line.
   *
   * <p>Separators: {@code ", "} between street and number; {@code " - "} (space-dash-space)
   * between street+number and building name in Line 1. {@code append} only inserts a separator
   * when both sides are non-blank.
   *
   * <p><b>Every branch below is reachable with valid input — including the last one (ETP-5806 CP-13,
   * QA finding).</b> Two earlier revisions of this javadoc claimed the street-truncating branch was
   * unreachable once {@link #STREET_NAME_MAX_LENGTH} dropped to 60. That is wrong, and QA verified
   * it live: the third branch is taken whenever {@code streetAndNumber} exceeds
   * {@link #ADDRESS_LINE_MAX_LENGTH}, and {@code streetAndNumber} is {@code street + ", " + number}
   * — so a street of exactly 60 characters (the maximum this composer itself publishes and accepts)
   * plus any non-blank number already measures 63. {@code Address1} then holds the street alone (it
   * is 60 long, so {@code StringUtils.left} does not actually cut it) and {@code Address2} holds
   * {@code number - name}. Nothing is lost; the number simply moves to the second line.
   *
   * <p><b>Consequence worth knowing before changing any of this (ETP-5806 CP-13, product decision —
   * NOT a bug).</b> Because a blank Building Number resolves to the literal {@code "s/n"} (see
   * {@link #effectiveBuildingNumber}), a street of 58 characters or more with no Building Number and
   * no Building Name composes to {@code Address2 == "s/n"} on its own — a second address line that
   * reads as nothing but "no number". This is faithful parity with Classic's
   * {@code LocationSEPA::fillAddressLines}, which this method ports line for line, and it diverges
   * from Classic for the same input only because {@code "s/n"} is Etendo Go's own convention and
   * Classic would have left the number blank there. Left as is on purpose: changing it would either
   * break the Classic parity this port exists for, or make the {@code "s/n"} convention conditional
   * on the street's length.
   *
   * <p>The third branch still matches Classic's {@code LocationSEPA.java::fillAddressLines}
   * ({@code org.openbravo.sepa.iso20022}) exactly. The building-name-only truncation path
   * (street+number fits, the composed whole does not) is likewise reachable — Building Name has no
   * line-width cap of its own.
   *
   * @param streetName     Street Name ({@code EM_OBSEPA_StrtNm}), may be {@code null}/blank
   * @param buildingNumber Building Number ({@code EM_OBSEPA_BldgNb}), may be {@code null}/blank
   * @param buildingName   Building Name ({@code EM_OBSEPA_BldgNm}), may be {@code null}/blank
   * @return the composed lines; both {@code null} when all three inputs are blank
   */
  public static ComposedAddress composeAddressLines(String streetName, String buildingNumber,
      String buildingName) {
    String street = StringUtils.trimToEmpty(streetName);
    String number = StringUtils.trimToEmpty(buildingNumber);
    String name = StringUtils.trimToEmpty(buildingName);

    String streetAndNumber = append(street, ", ", number);
    String wholeAddress = append(streetAndNumber, " - ", name);

    if (wholeAddress.length() <= ADDRESS_LINE_MAX_LENGTH) {
      // Everything fits on one line.
      return new ComposedAddress(emptyToNull(wholeAddress), null);
    }
    if (streetAndNumber.length() <= ADDRESS_LINE_MAX_LENGTH) {
      return new ComposedAddress(emptyToNull(streetAndNumber),
          emptyToNull(StringUtils.left(name, ADDRESS_LINE_MAX_LENGTH)));
    }
    // street+number does not fit one line: the street keeps line 1 and the number moves to
    // line 2. StringUtils.left only cuts a street that is itself longer than the line, which
    // fieldsExceedingMaxLength already refuses — but street+number together overflowing is
    // ordinary valid input (a 60-char street plus any number is 63). See the javadoc above.
    return new ComposedAddress(
        emptyToNull(StringUtils.left(street, ADDRESS_LINE_MAX_LENGTH)),
        emptyToNull(StringUtils.left(append(number, " - ", name), ADDRESS_LINE_MAX_LENGTH)));
  }

  /** The fallback value used for Building Number when it is blank and Street Name is present. */
  private static final String EMPTY_BUILDING_NUMBER_FALLBACK = "s/n";

  /** Published payload key of the Street Name field — see {@link #callerRequiredFields}. */
  private static final String FIELD_STREET_NAME = "streetName";

  /**
   * The effective Building Number to validate and persist (ETP-5806 follow-up, QA finding): when
   * {@code buildingNumber} is blank but {@code streetName} is present, the field is not treated as
   * missing — it falls back to the literal {@code "s/n"} ("sin número"), the same convention the
   * frontend ({@code LocationEditorModal.jsx}, onboarding {@code api.js}) already applies before
   * ever reaching this handler.
   *
   * <p>This closes the gap for callers that do NOT go through the frontend — an MCP {@code
   * etendo_create}/{@code etendo_update} call, or a direct API integration — which previously got
   * a {@code 400 Missing required field: buildingNumber} even though the product convention is to
   * accept the omission and fill in {@code "s/n"}. Centralized here, instead of duplicated per
   * handler, for the same reason the frontend centralized its own copy in one constant rather than
   * re-implementing the rule at each call site.
   *
   * <p>When {@code streetName} itself is blank, no fallback is applied: the raw (likely blank)
   * {@code buildingNumber} is returned unchanged, so {@link #missingMandatoryFields} still reports
   * both {@code streetName} and {@code buildingNumber} as missing in that case — there is nothing
   * to anchor a "no building number" convention to without a street.
   *
   * @param streetName     Street Name ({@code EM_OBSEPA_StrtNm}), may be {@code null}/blank
   * @param buildingNumber Building Number ({@code EM_OBSEPA_BldgNb}), may be {@code null}/blank
   * @return {@code buildingNumber} trimmed when non-blank; {@code "s/n"} when blank and {@code
   *     streetName} is non-blank; the original (blank/{@code null}) {@code buildingNumber}
   *     otherwise
   */
  public static String effectiveBuildingNumber(String streetName, String buildingNumber) {
    if (StringUtils.isNotBlank(buildingNumber)) {
      return StringUtils.trim(buildingNumber);
    }
    if (StringUtils.isNotBlank(streetName)) {
      return EMPTY_BUILDING_NUMBER_FALLBACK;
    }
    return buildingNumber;
  }

  /**
   * The structured-address-for-sepa mandatory fields (ETP-5806 product decision: Street, City and
   * Country are mandatory in Etendo Go, stricter than Classic's optional SEPA fields) that are
   * missing from the given values.
   *
   * <p><b>Building Number is never reported here (ETP-5806 CP-13, QA finding).</b> It is mandatory
   * at the level of the stored row — the column always ends up with a value — but never at the level
   * of what a caller has to supply: a blank or omitted Building Number resolves to the literal
   * {@code "s/n"} (see {@link #effectiveBuildingNumber}), so there is nothing the caller could have
   * done about it and nothing to ask a user for. This method answers "what must the caller still
   * send", so naming {@code buildingNumber} in it is a contract lie whatever the other fields hold.
   *
   * <p>It used to be reported in exactly one case — a blank Street Name, which suppresses the
   * {@code "s/n"} fallback — so a create missing only {@code streetName} came back
   * {@code 400 Missing required field(s): streetName, buildingNumber} and sent the caller looking
   * for a value it was never responsible for, while the very same create WITH a street and without
   * a number succeeded at {@code 201} and persisted {@code "s/n"}. The two answers contradicted
   * each other; this one is the one that was wrong. {@code etendo_schema} publishes the matching
   * truth through {@link #serverDefaultedFields} ({@code optional} + {@code serverDefaulted:true}).
   *
   * <p>The Building Number parameter this method used to take is gone with the check: it was the
   * only reason callers had to resolve {@link #effectiveBuildingNumber} before validating, and
   * keeping an argument nothing reads is how the rule gets reintroduced by someone restoring a
   * "missing" check for it.
   *
   * @param streetName     Street Name ({@code EM_OBSEPA_StrtNm}), may be {@code null}/blank
   * @param cityName       City Name, may be {@code null}/blank
   * @param countryId      Country id, may be {@code null}/blank
   * @return the missing field names (payload keys), in a stable order; empty when none are missing
   */
  public static List<String> missingMandatoryFields(String streetName, String cityName,
      String countryId) {
    List<String> missing = new ArrayList<>();
    if (StringUtils.isBlank(streetName)) {
      missing.add(FIELD_STREET_NAME);
    }
    if (StringUtils.isBlank(cityName)) {
      missing.add("cityName");
    }
    if (StringUtils.isBlank(countryId)) {
      missing.add("country");
    }
    return missing;
  }

  /**
   * The structured-address-for-sepa fields whose value exceeds the DB column's own length — see the
   * constants' doc comment above for why this check exists. A blank/{@code null} value is never
   * "too long" regardless of length (that is {@link #missingMandatoryFields}'s concern).
   *
   * @param streetName     Street Name ({@code EM_OBSEPA_StrtNm}), may be {@code null}/blank
   * @param buildingNumber Building Number ({@code EM_OBSEPA_BldgNb}), may be {@code null}/blank
   * @param buildingName   Building Name ({@code EM_OBSEPA_BldgNm}), may be {@code null}/blank
   * @return the offending field names (payload keys), in a stable order; empty when none are too long
   */
  public static List<String> fieldsExceedingMaxLength(String streetName, String buildingNumber,
      String buildingName) {
    List<String> tooLong = new ArrayList<>();
    if (streetName != null && streetName.length() > STREET_NAME_MAX_LENGTH) {
      tooLong.add(FIELD_STREET_NAME);
    }
    if (buildingNumber != null && buildingNumber.length() > BUILDING_NUMBER_MAX_LENGTH) {
      tooLong.add("buildingNumber");
    }
    if (buildingName != null && buildingName.length() > BUILDING_NAME_MAX_LENGTH) {
      tooLong.add("buildingName");
    }
    return tooLong;
  }

  /**
   * ETP-5806 CP-12b (QA finding) — whether an update's write path should merge the stored record
   * into the body before validating mandatory fields: always for a PATCH, and for a PUT whenever
   * the body alone (with the flag on) cannot satisfy them on its own.
   *
   * <p>Kept here, not in the calling handler, specifically so the handler's own update method
   * stays a straight sequence of calls with no extra branching of its own — {@code
   * ContactsLocationAddressHandler#handleUpdate} already carries plenty of control flow, and
   * every {@code &&}/{@code ||} added to it directly raises its own Cognitive Complexity. The
   * branching that decides this one boolean belongs in a single-purpose method instead, the same
   * way {@link #missingMandatoryFields} and {@link #fieldsExceedingMaxLength} already centralize
   * the other mandatory-field decisions this composer owns.
   *
   * <p>MCP's {@code etendo_update} always maps to HTTP PUT ({@code McpToolRouter#handleUpdate}),
   * so gating the merge purely on PATCH vs PUT meant a single-field MCP edit (e.g. only {@code
   * buildingName}) reached validation with the rest of the structured fields genuinely absent
   * from the body and a clean 400, even though they already existed on the stored record. A true
   * full-replace PUT (the UI form, a REST client resending the complete address) is unaffected:
   * it already satisfies every mandatory field, so this returns {@code false} and the merge is
   * skipped exactly as before.
   *
   * <p>Building Number is not a parameter: it stopped being one the moment
   * {@link #missingMandatoryFields} stopped asking for it (ETP-5806 CP-13), and a body carrying
   * only a Building Number has never been self-sufficient anyway — its missing street already
   * forces the merge.
   *
   * @param httpMethod               the request's HTTP method
   * @param structuredAddressEnabled whether {@code structured-address-for-sepa} is on for the
   *                                 caller's account; with it off, only a PATCH merges
   * @param streetName               the raw body value, before any merge — see
   *                                 {@code ContactsLocationAddressHandler#readAlias}
   * @param cityName                 the raw body value, before any merge
   * @param countryId                the raw body value, before any merge — NOT falling back to
   *                                 the stored record's own country, unlike
   *                                 {@code effectiveCountryForValidation}: this method asks
   *                                 whether the body is self-sufficient, so a country the body
   *                                 never mentions must count as missing here
   * @return {@code true} when the stored record should be merged into the body first
   */
  public static boolean requiresMergeOnUpdate(String httpMethod, boolean structuredAddressEnabled,
      String streetName, String cityName, String countryId) {
    if ("PATCH".equals(httpMethod)) {
      return true;
    }
    if (!structuredAddressEnabled) {
      return false;
    }
    return !missingMandatoryFields(streetName, cityName, countryId).isEmpty();
  }

  private static String append(String base, String separator, String part) {
    if (base.isEmpty()) {
      return part;
    }
    if (part.isEmpty()) {
      return base;
    }
    return base + separator + part;
  }

  private static String emptyToNull(String value) {
    return (value == null || value.isEmpty()) ? null : value;
  }
}
