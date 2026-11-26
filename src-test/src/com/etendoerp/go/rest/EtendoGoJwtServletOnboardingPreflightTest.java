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
package com.etendoerp.go.rest;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockConstruction;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.BufferedReader;
import java.io.PrintWriter;
import java.io.StringReader;
import java.io.StringWriter;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.codehaus.jettison.json.JSONObject;
import org.junit.Test;
import org.mockito.MockedStatic;
import org.openbravo.base.secureApp.VariablesSecureApp;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBDal;
import org.openbravo.erpCommon.businessUtility.InitialClientSetup;
import org.openbravo.erpCommon.utility.OBError;
import org.openbravo.model.ad.access.Role;
import org.openbravo.model.ad.access.User;
import org.openbravo.model.ad.access.UserRoles;
import org.openbravo.model.common.currency.Currency;
import org.openbravo.model.common.enterprise.Organization;

import com.etendoerp.go.featureflags.GoFeatureFlags;
import com.etendoerp.go.schemaforge.data.Account;

/**
 * Unit tests for {@link EtendoGoJwtServlet} on the {@code POST /onboarding} pre-flight: token,
 * client name, JSON, currency and structured-address validation, resuming an existing client,
 * {@code resolveOrCreateClient}, {@code applySocialName} (ETP-4749), and the ETP-5806
 * {@code structured-address-for-sepa} flag: {@code validateStructuredAddressBlock}/
 * {@code validateStructuredAddressLength} are a no-op while it is off and still enforce their
 * rules when it is on, and {@code buildSessionCapabilities()} mirrors its live value.
 *
 * @covers com.etendoerp.go.rest.EtendoGoJwtServlet
 */
public class EtendoGoJwtServletOnboardingPreflightTest extends EtendoGoJwtServletTestSupport {

  // ===================== POST /onboarding — pre-flight branches =====================

  @Test
  public void onboardingTokenValidationDatabaseErrorReturnsServerError() throws Exception {
    ResponseCapture resp = mockResponse();
    HttpServletRequest req = mockRequest("/onboarding");
    when(req.getHeader("Authorization")).thenReturn("Bearer valid-token");

    try (var ctxMock = mockStatic(OBContext.class);
         var dalMock = mockStatic(EtendoGoJwtDalHelper.class)) {
      dalMock.when(() -> EtendoGoJwtDalHelper.findActiveAccountByBearerToken("valid-token"))
          .thenThrow(new RuntimeException("db down"));

      servlet.doPost(req, resp.response);
    }

    assertEquals(500, resp.status);
  }

  @Test
  public void onboardingMissingClientNameReturnsBadRequest() throws Exception {
    ResponseCapture resp = mockResponse();
    HttpServletRequest req = jsonRequest("/onboarding", "{}");
    when(req.getHeader("Authorization")).thenReturn("Bearer valid-token");

    Account account = mock(Account.class);
    when(account.getEmail()).thenReturn("user@test.com");
    try (var ctxMock = mockStatic(OBContext.class);
         var dalMock = mockStatic(EtendoGoJwtDalHelper.class)) {
      stubAuthenticatedAccount(dalMock);

      servlet.doPost(req, resp.response);
    }

    assertEquals(400, resp.status);
    assertTrue(new JSONObject(resp.body()).toString().contains("clientName"));
  }

  @Test
  public void resolveOrCreateClientReturnsTheIdFromTheCreationSession() throws Exception {
    StringWriter stream = new StringWriter();
    PrintWriter writer = new PrintWriter(stream);
    VariablesSecureApp vars = mock(VariablesSecureApp.class);
    when(vars.getSessionValue("AD_Client_ID")).thenReturn("created-client-id");

    Class<?> requestType = Class.forName(
        "com.etendoerp.go.rest.EtendoGoJwtServlet$OnboardingRequestData");
    Constructor<?> constructor = requestType.getDeclaredConstructor();
    constructor.setAccessible(true);
    Object requestData = constructor.newInstance();
    Field clientName = requestType.getDeclaredField("clientName");
    clientName.setAccessible(true);
    clientName.set(requestData, "Acme");
    Field fullName = requestType.getDeclaredField("fullName");
    fullName.setAccessible(true);
    fullName.set(requestData, "Ada Lovelace");

    Method resolver = EtendoGoJwtServlet.class.getDeclaredMethod("resolveOrCreateClient",
        PrintWriter.class, VariablesSecureApp.class, String.class, requestType, String.class,
        String.class, String.class);
    resolver.setAccessible(true);

    OBError success = new OBError();
    success.setType("Success");
    String[] createdUnder = new String[1];

    try (var supportMock = mockStatic(EtendoGoJwtSupport.class);
         var setupMock = mockConstruction(InitialClientSetup.class, (setup, context) ->
             when(setup.createClient(any(VariablesSecureApp.class), anyString(), anyString(),
                 anyString(), anyString(), anyString(), anyString(), anyString(), anyBoolean(),
                 isNull(), anyBoolean(), anyBoolean(), anyBoolean(), anyBoolean(), anyBoolean()))
                 .thenAnswer(invocation -> {
                   // InitialClientSetup.insertClient stores the actual created ID in this session.
                   VariablesSecureApp creationVars = invocation.getArgument(0);
                   creationVars.setSessionValue("AD_Client_ID", "created-client-id");
                   createdUnder[0] = invocation.getArgument(2);
                   return success;
                 }))) {
      supportMock.when(() -> EtendoGoJwtSupport.findClientIdByName(PAID_PROVISIONING_NAME))
          .thenReturn(null, "stale-name-lookup-id");
      supportMock.when(() -> EtendoGoJwtSupport.buildClientUsername("owner@test.com", "Acme"))
          .thenReturn("acme-admin");

      Object result = resolver.invoke(servlet, writer, vars, "owner@test.com", requestData,
          PAID_PROVISIONING_NAME, "currency-1", "temporary-password");

      assertEquals("created-client-id", result);
      supportMock.verify(() -> EtendoGoJwtSupport.findClientIdByName(PAID_PROVISIONING_NAME));
      // ETP-5548: the client is created under the provisioning name, never the company name.
      assertEquals(PAID_PROVISIONING_NAME, createdUnder[0]);
    }
  }

  // ── ETP-5806: structured-address-for-sepa — onboarding validation + session capability ──

  private static Object newOnboardingRequestData() throws Exception {
    Class<?> requestType = Class.forName(
        "com.etendoerp.go.rest.EtendoGoJwtServlet$OnboardingRequestData");
    Constructor<?> constructor = requestType.getDeclaredConstructor();
    constructor.setAccessible(true);
    return constructor.newInstance();
  }

  private static void setOnboardingField(Object data, String fieldName, String value)
      throws Exception {
    Field field = data.getClass().getDeclaredField(fieldName);
    field.setAccessible(true);
    field.set(data, value);
  }

  /**
   * ETP-5806 follow-up — the onboarding validators take the requesting account's email and
   * evaluate {@code structured-address-for-sepa} for it (staged rollout), instead of the
   * account-less constant they used to share with {@code StructuredAddressComposer}.
   */
  private static final String ACCOUNT_EMAIL_FOR_FLAG = "lola@example.com";

  private static MockedStatic<GoFeatureFlags> mockStructuredAddressFlag(boolean enabled) {
    MockedStatic<GoFeatureFlags> flags = mockStatic(GoFeatureFlags.class);
    flags.when(() -> GoFeatureFlags.isEnabled(
        eq(GoFeatureFlags.FLAG_STRUCTURED_ADDRESS_FOR_SEPA), any())).thenReturn(enabled);
    return flags;
  }

  /**
   * Flag off: {@code validateStructuredAddressBlock} is a no-op — a street present with
   * buildingNumber/city both blank would be a 400 "Missing required field(s)" with the flag on,
   * but is skipped entirely while it is off.
   */
  @Test
  public void validateStructuredAddressBlockIsANoOpWhenTheFlagIsOff() throws Exception {
    Object data = newOnboardingRequestData();
    setOnboardingField(data, "streetName", "Gran Via");
    // buildingNumber and city intentionally left unset (null) — would trigger the mandatory
    // check with the flag on.

    Method validate = EtendoGoJwtServlet.class.getDeclaredMethod(
        "validateStructuredAddressBlock", data.getClass(), HttpServletResponse.class,
        String.class);
    validate.setAccessible(true);
    ResponseCapture resp = mockResponse();

    try (MockedStatic<GoFeatureFlags> flags = mockStructuredAddressFlag(false)) {
      boolean result = (boolean) validate.invoke(servlet, data, resp.response, ACCOUNT_EMAIL_FOR_FLAG);

      assertTrue("flag off: validation never runs, so the incomplete block is accepted", result);
      verify(resp.response, never()).setStatus(anyInt());
    }
  }

  /**
   * Flag on (for comparison): a street with no city is still rejected with a clean 400 — but
   * NOT for a missing Building Number any more (ETP-5806 follow-up): a blank Building Number
   * with a present Street Name falls back to "s/n" via {@link
   * StructuredAddressComposer#effectiveBuildingNumber}, the same rule already applied by the 3
   * NEO handlers, so it must no longer appear in the "missing" list.
   */
  @Test
  public void validateStructuredAddressBlockStillRejectsAnIncompleteBlockWhenTheFlagIsOn()
      throws Exception {
    Object data = newOnboardingRequestData();
    setOnboardingField(data, "streetName", "Gran Via");
    setOnboardingField(data, "countryCode", "ES");
    // buildingNumber and city intentionally left unset (null): buildingNumber now falls back to
    // "s/n" (not reported missing), city stays mandatory.

    Method validate = EtendoGoJwtServlet.class.getDeclaredMethod(
        "validateStructuredAddressBlock", data.getClass(), HttpServletResponse.class,
        String.class);
    validate.setAccessible(true);
    ResponseCapture resp = mockResponse();

    try (MockedStatic<GoFeatureFlags> flags = mockStructuredAddressFlag(true)) {
      boolean result = (boolean) validate.invoke(servlet, data, resp.response, ACCOUNT_EMAIL_FOR_FLAG);

      assertFalse(result);
      assertEquals(400, resp.status);
      assertFalse(resp.body(), resp.body().contains("buildingNumber"));
      assertTrue(resp.body(), resp.body().contains("city"));
    }
  }

  /**
   * ETP-5806 follow-up — a Building Number genuinely ABSENT from the payload (not blank, not
   * "s/n": the field is simply never set) is not mandatory when Street Name and City are both
   * present. {@link StructuredAddressComposer#effectiveBuildingNumber} fills the gap with "s/n"
   * before {@code missingMandatoryFields} ever sees it, so the block passes validation — the same
   * contract already covered for persistence in {@code OnboardingOrgInfoServiceTest}, exercised
   * here at the servlet validation layer.
   */
  @Test
  public void validateStructuredAddressBlockAcceptsAnAbsentBuildingNumber() throws Exception {
    Object data = newOnboardingRequestData();
    setOnboardingField(data, "streetName", "Gran Via");
    setOnboardingField(data, "city", "Madrid");
    setOnboardingField(data, "countryCode", "ES");
    // buildingNumber intentionally left unset (null): absent from the payload entirely.

    Method validate = EtendoGoJwtServlet.class.getDeclaredMethod(
        "validateStructuredAddressBlock", data.getClass(), HttpServletResponse.class,
        String.class);
    validate.setAccessible(true);
    ResponseCapture resp = mockResponse();

    try (MockedStatic<GoFeatureFlags> flags = mockStructuredAddressFlag(true)) {
      boolean result = (boolean) validate.invoke(servlet, data, resp.response, ACCOUNT_EMAIL_FOR_FLAG);

      assertTrue("an absent buildingNumber falls back to s/n and does not block the request",
          result);
      verify(resp.response, never()).setStatus(anyInt());
    }
  }

  /**
   * ETP-5806 follow-up — the onboarding validation resolves the flag for the REQUESTING ACCOUNT.
   * A provider rule that names only the localization team turns the structured block on for that
   * account while every other account in the same environment, and the account-less evaluation
   * this used to do, resolve false.
   */
  @Test
  public void validateStructuredAddressBlockIsTargetedAtTheGivenAccount() throws Exception {
    Object data = newOnboardingRequestData();
    setOnboardingField(data, "streetName", "Gran Via");

    Method validate = EtendoGoJwtServlet.class.getDeclaredMethod(
        "validateStructuredAddressBlock", data.getClass(), HttpServletResponse.class,
        String.class);
    validate.setAccessible(true);

    try (MockedStatic<GoFeatureFlags> flags = mockStructuredAddressFlagTargetedAt()) {
      ResponseCapture targeted = mockResponse();
      assertFalse("targeted account: the incomplete block is validated and rejected",
          (boolean) validate.invoke(servlet, data, targeted.response, ACCOUNT_EMAIL_FOR_FLAG));
      assertEquals(400, targeted.status);

      ResponseCapture other = mockResponse();
      assertTrue("untargeted account: unchanged off behaviour, nothing is validated",
          (boolean) validate.invoke(servlet, data, other.response, "someone.else@example.com"));
      verify(other.response, never()).setStatus(anyInt());
    }
  }

  /** Same per-account targeting for the length check, which gates the same 3 fields. */
  @Test
  public void validateStructuredAddressLengthIsTargetedAtTheGivenAccount() throws Exception {
    Object data = newOnboardingRequestData();
    setOnboardingField(data, "streetName", "A".repeat(71)); // EM_OBSEPA_StrtNm is VARCHAR(70)

    Method validate = EtendoGoJwtServlet.class.getDeclaredMethod(
        "validateStructuredAddressLength", data.getClass(), HttpServletResponse.class,
        String.class);
    validate.setAccessible(true);

    try (MockedStatic<GoFeatureFlags> flags = mockStructuredAddressFlagTargetedAt()) {
      ResponseCapture targeted = mockResponse();
      assertFalse("targeted account: the oversized street is rejected",
          (boolean) validate.invoke(servlet, data, targeted.response, ACCOUNT_EMAIL_FOR_FLAG));
      assertEquals(400, targeted.status);

      ResponseCapture other = mockResponse();
      assertTrue("untargeted account: the oversized field is never checked",
          (boolean) validate.invoke(servlet, data, other.response, "someone.else@example.com"));
      verify(other.response, never()).setStatus(anyInt());
    }
  }

  /**
   * Stubs the flag true for {@link #ACCOUNT_EMAIL_FOR_FLAG} ONLY — any other targeting key, and
   * the account-less context the gating used before this follow-up, resolve false.
   */
  private static MockedStatic<GoFeatureFlags> mockStructuredAddressFlagTargetedAt() {
    MockedStatic<GoFeatureFlags> flags = mockStatic(GoFeatureFlags.class);
    flags.when(() -> GoFeatureFlags.isEnabled(
        eq(GoFeatureFlags.FLAG_STRUCTURED_ADDRESS_FOR_SEPA), any())).thenReturn(false);
    flags.when(() -> GoFeatureFlags.isEnabled(
        eq(GoFeatureFlags.FLAG_STRUCTURED_ADDRESS_FOR_SEPA),
        argThat(ctx -> ctx != null && ACCOUNT_EMAIL_FOR_FLAG.equals(ctx.getTargetingKey()))))
        .thenReturn(true);
    return flags;
  }

  /**
   * Flag off: {@code validateStructuredAddressLength} is a no-op — a street longer than its DB
   * column would be a 400 with the flag on, but is skipped entirely while it is off.
   */
  @Test
  public void validateStructuredAddressLengthIsANoOpWhenTheFlagIsOff() throws Exception {
    Object data = newOnboardingRequestData();
    setOnboardingField(data, "streetName", "A".repeat(71)); // EM_OBSEPA_StrtNm is VARCHAR(70)

    Method validate = EtendoGoJwtServlet.class.getDeclaredMethod(
        "validateStructuredAddressLength", data.getClass(), HttpServletResponse.class,
        String.class);
    validate.setAccessible(true);
    ResponseCapture resp = mockResponse();

    try (MockedStatic<GoFeatureFlags> flags = mockStructuredAddressFlag(false)) {
      boolean result = (boolean) validate.invoke(servlet, data, resp.response, ACCOUNT_EMAIL_FOR_FLAG);

      assertTrue("flag off: the oversized field is never checked", result);
      verify(resp.response, never()).setStatus(anyInt());
    }
  }

  /** Flag on (for comparison): the same oversized field is rejected with a clean 400. */
  @Test
  public void validateStructuredAddressLengthStillRejectsAnOversizedFieldWhenTheFlagIsOn()
      throws Exception {
    Object data = newOnboardingRequestData();
    setOnboardingField(data, "streetName", "A".repeat(71));

    Method validate = EtendoGoJwtServlet.class.getDeclaredMethod(
        "validateStructuredAddressLength", data.getClass(), HttpServletResponse.class,
        String.class);
    validate.setAccessible(true);
    ResponseCapture resp = mockResponse();

    try (MockedStatic<GoFeatureFlags> flags = mockStructuredAddressFlag(true)) {
      boolean result = (boolean) validate.invoke(servlet, data, resp.response, ACCOUNT_EMAIL_FOR_FLAG);

      assertFalse(result);
      assertEquals(400, resp.status);
      assertTrue(resp.body(), resp.body().contains("streetName"));
    }
  }

  /**
   * {@code buildSessionCapabilities(String)} (the onboarding session-bootstrap response) mirrors
   * the live flag value in both directions.
   */
  @Test
  public void buildSessionCapabilitiesReflectsTheFlagOn() throws Exception {
    Method build = EtendoGoJwtServlet.class.getDeclaredMethod("buildSessionCapabilities", String.class);
    build.setAccessible(true);

    try (MockedStatic<GoFeatureFlags> flags = mockStructuredAddressFlag(true)) {
      JSONObject capabilities = (JSONObject) build.invoke(null, "user@example.com");
      assertTrue(capabilities.getBoolean("structuredAddressForSepa"));
    }
  }

  @Test
  public void buildSessionCapabilitiesReflectsTheFlagOff() throws Exception {
    Method build = EtendoGoJwtServlet.class.getDeclaredMethod("buildSessionCapabilities", String.class);
    build.setAccessible(true);

    try (MockedStatic<GoFeatureFlags> flags = mockStructuredAddressFlag(false)) {
      JSONObject capabilities = (JSONObject) build.invoke(null, "user@example.com");
      assertFalse(capabilities.getBoolean("structuredAddressForSepa"));
    }
  }

  /**
   * ETP-5806 follow-up — {@code buildSessionCapabilities} now targets the flag on the restored
   * session's real account email (not an account-less context), the same per-account pattern
   * {@code bp-portal-link} already uses, so a Datadog targeting rule can match it.
   */
  @Test
  public void buildSessionCapabilitiesTargetsTheRealAccountEmail() throws Exception {
    Method build = EtendoGoJwtServlet.class.getDeclaredMethod("buildSessionCapabilities", String.class);
    build.setAccessible(true);

    try (MockedStatic<GoFeatureFlags> flags = mockStatic(GoFeatureFlags.class)) {
      flags.when(() -> GoFeatureFlags.isEnabled(
          eq(GoFeatureFlags.FLAG_STRUCTURED_ADDRESS_FOR_SEPA), any())).thenReturn(false);

      build.invoke(null, "lola@example.com");

      flags.verify(() -> GoFeatureFlags.isEnabled(
          eq(GoFeatureFlags.FLAG_STRUCTURED_ADDRESS_FOR_SEPA),
          argThat(ctx -> "lola@example.com".equals(ctx.getTargetingKey()))));
    }
  }

  /**
   * A session restored with no resolvable account email (should not happen in practice, but the
   * flag-evaluation contract is "never block, never fail, default false") degrades to an
   * untargeted context instead of throwing.
   */
  @Test
  public void buildSessionCapabilitiesDegradesGracefullyWithNoAccountEmail() throws Exception {
    Method build = EtendoGoJwtServlet.class.getDeclaredMethod("buildSessionCapabilities", String.class);
    build.setAccessible(true);

    try (MockedStatic<GoFeatureFlags> flags = mockStructuredAddressFlag(false)) {
      JSONObject capabilities = (JSONObject) build.invoke(null, (Object) null);
      assertFalse(capabilities.getBoolean("structuredAddressForSepa"));
    }
  }

  @Test
  public void onboardingEmptyClientNameReturnsBadRequest() throws Exception {
    ResponseCapture resp = mockResponse();
    HttpServletRequest req = jsonRequest("/onboarding", "{\"clientName\":\"  \"}");
    when(req.getHeader("Authorization")).thenReturn("Bearer valid-token");

    Account account = mock(Account.class);
    when(account.getEmail()).thenReturn("user@test.com");
    try (var ctxMock = mockStatic(OBContext.class);
         var dalMock = mockStatic(EtendoGoJwtDalHelper.class)) {
      stubAuthenticatedAccount(dalMock);

      servlet.doPost(req, resp.response);
    }

    assertEquals(400, resp.status);
  }

  @Test
  public void onboardingInvalidJsonReturnsBadRequest() throws Exception {
    ResponseCapture resp = mockResponse();
    HttpServletRequest req = mockRequest("/onboarding");
    when(req.getHeader("Authorization")).thenReturn("Bearer valid-token");
    when(req.getContentType()).thenReturn("application/json");
    when(req.getReader()).thenReturn(new BufferedReader(new StringReader("not json")));

    Account account = mock(Account.class);
    when(account.getEmail()).thenReturn("user@test.com");
    try (var ctxMock = mockStatic(OBContext.class);
         var dalMock = mockStatic(EtendoGoJwtDalHelper.class)) {
      stubAuthenticatedAccount(dalMock);

      servlet.doPost(req, resp.response);
    }

    assertEquals(400, resp.status);
  }

  @Test
  public void onboardingUnknownCurrencyReturnsBadRequest() throws Exception {
    ResponseCapture resp = mockResponse();
    HttpServletRequest req = jsonRequest("/onboarding",
        "{\"clientName\":\"Acme\",\"currency\":\"XYZ\"}");
    when(req.getHeader("Authorization")).thenReturn("Bearer valid-token");

    Account account = mock(Account.class);
    when(account.getEmail()).thenReturn("user@test.com");
    try (var ctxMock = mockStatic(OBContext.class);
         var dalMock = mockStatic(EtendoGoJwtDalHelper.class)) {
      stubAuthenticatedAccount(dalMock);
      dalMock.when(() -> EtendoGoJwtDalHelper.findCurrencyByIsoCode("XYZ"))
          .thenReturn(null);

      servlet.doPost(req, resp.response);
    }

    assertEquals(400, resp.status);
    assertTrue(new JSONObject(resp.body()).toString().contains("Unknown currency"));
  }

  /**
   * ETP-5806 — the structured address block (streetName/city) is optional on the minimalist
   * onboarding step, but once the user starts filling it in, Street Name and City become
   * mandatory together — same rule as the Contacts/Warehouse address popups. Building Number is
   * NOT part of that mandatory pair any more (ETP-5806 follow-up): a blank value with a present
   * Street Name falls back to "s/n" ({@link StructuredAddressComposer#effectiveBuildingNumber}),
   * so it must not appear in the "missing" list.
   */
  @Test
  public void onboardingPartiallyStartedAddressBlockReturnsBadRequest() throws Exception {
    ResponseCapture resp = mockResponse();
    HttpServletRequest req = jsonRequest("/onboarding",
        "{\"clientName\":\"Acme\",\"streetName\":\"Gran Via\"}");
    when(req.getHeader("Authorization")).thenReturn("Bearer valid-token");

    try (var ctxMock = mockStatic(OBContext.class);
         var dalMock = mockStatic(EtendoGoJwtDalHelper.class);
         MockedStatic<GoFeatureFlags> flags = mockStructuredAddressFlag(true)) {
      stubAuthenticatedAccount(dalMock);

      servlet.doPost(req, resp.response);
    }

    assertEquals(400, resp.status);
    String body = new JSONObject(resp.body()).toString();
    assertFalse(body, body.contains("buildingNumber"));
    assertTrue(body, body.contains("city"));
  }

  /**
   * The counterweight: an entirely empty address block (the pre-ETP-5806 shape, or a caller that
   * never asks about addresses at all) must NOT be rejected — it is optional. Proven by letting
   * the request fail at the UNRELATED currency check instead, which is only reached once the
   * address validation has already passed.
   */
  @Test
  public void onboardingEmptyAddressBlockIsAcceptedByValidation() throws Exception {
    ResponseCapture resp = mockResponse();
    HttpServletRequest req = jsonRequest("/onboarding",
        "{\"clientName\":\"Acme\",\"currency\":\"XYZ\"}");
    when(req.getHeader("Authorization")).thenReturn("Bearer valid-token");

    try (var ctxMock = mockStatic(OBContext.class);
         var dalMock = mockStatic(EtendoGoJwtDalHelper.class)) {
      stubAuthenticatedAccount(dalMock);
      dalMock.when(() -> EtendoGoJwtDalHelper.findCurrencyByIsoCode("XYZ")).thenReturn(null);

      servlet.doPost(req, resp.response);
    }

    assertEquals(400, resp.status);
    assertTrue(new JSONObject(resp.body()).toString().contains("Unknown currency"));
  }

  /**
   * ETP-5806 follow-up (the production 500 bug class): a structured field longer than its DB
   * column is rejected with a clean 400 naming it, via the same
   * {@code StructuredAddressComposer#fieldsExceedingMaxLength} check the Contacts/Warehouse
   * handlers use — never a downstream data-truncation failure mid-provisioning.
   */
  @Test
  public void onboardingOversizedStructuredAddressFieldReturnsBadRequest() throws Exception {
    ResponseCapture resp = mockResponse();
    String tooLongStreetName = "A".repeat(71); // EM_OBSEPA_StrtNm is VARCHAR(70)
    HttpServletRequest req = jsonRequest("/onboarding",
        "{\"clientName\":\"Acme\",\"streetName\":\"" + tooLongStreetName
            + "\",\"buildingNumber\":\"1\",\"city\":\"Madrid\"}");
    when(req.getHeader("Authorization")).thenReturn("Bearer valid-token");

    try (var ctxMock = mockStatic(OBContext.class);
         var dalMock = mockStatic(EtendoGoJwtDalHelper.class);
         MockedStatic<GoFeatureFlags> flags = mockStructuredAddressFlag(true)) {
      stubAuthenticatedAccount(dalMock);

      servlet.doPost(req, resp.response);
    }

    assertEquals(400, resp.status);
    assertTrue(new JSONObject(resp.body()).toString().contains("streetName"));
  }

  /**
   * City is validated through {@code OnboardingFieldLimits.CITY} (it is not one of the 3
   * structured SEPA fields {@code StructuredAddressComposer} covers), and reported through the
   * machine-readable {@code FIELD_TOO_LONG} envelope rather than a plain message string.
   */
  @Test
  public void onboardingCityExceedingFieldLimitReturnsFieldTooLong() throws Exception {
    ResponseCapture resp = mockResponse();
    String tooLongCity = "A".repeat(61); // OnboardingFieldLimits.CITY is 60
    HttpServletRequest req = jsonRequest("/onboarding",
        "{\"clientName\":\"Acme\",\"streetName\":\"Gran Via\",\"buildingNumber\":\"1\",\"city\":\""
            + tooLongCity + "\"}");
    when(req.getHeader("Authorization")).thenReturn("Bearer valid-token");

    try (var ctxMock = mockStatic(OBContext.class);
         var dalMock = mockStatic(EtendoGoJwtDalHelper.class)) {
      stubAuthenticatedAccount(dalMock);

      servlet.doPost(req, resp.response);
    }

    assertEquals(400, resp.status);
    JSONObject error = new JSONObject(resp.body()).getJSONObject("error");
    assertEquals("FIELD_TOO_LONG", error.getString("code"));
    assertEquals("city", error.getString("field"));
    assertEquals(60, error.getInt("max"));
  }

  @Test
  public void onboardingExistingClientOwnedByAnotherAccountStreamsFailure() throws Exception {
    ResponseCapture resp = mockResponse();
    HttpServletRequest req = jsonRequest("/onboarding",
        "{\"clientName\":\"Acme\",\"currency\":\"EUR\",\"language\":\"en_US\"}");
    when(req.getHeader("Authorization")).thenReturn("Bearer valid-token");

    Currency currency = mock(Currency.class);
    when(currency.getId()).thenReturn("currency-1");
    Account account = mock(Account.class);
    when(account.getEmail()).thenReturn("user@test.com");

    try (var ctxMock = mockStatic(OBContext.class);
         var supportMock = mockStatic(EtendoGoJwtSupport.class);
         var dalMock = mockStatic(EtendoGoJwtDalHelper.class)) {
      Account authenticated = stubAuthenticatedAccount(dalMock);
      when(authenticated.getId()).thenReturn("account-1");
      dalMock.when(() -> EtendoGoJwtDalHelper.findCurrencyByIsoCode("EUR"))
          .thenReturn(currency);
      // The attempt's half-built client owned by ANOTHER account -> resume refused (tenant
      // isolation, ETP-4428; looked up by provisioning name since ETP-5548).
      supportMock.when(() -> EtendoGoJwtSupport.findClientIdByName(FREE_PROVISIONING_NAME))
          .thenReturn("client-1");
      dalMock.when(() -> EtendoGoJwtDalHelper.clientBelongsToAccountEmail("client-1", "user@test.com"))
          .thenReturn(false);

      servlet.doPost(req, resp.response);
    }

    // NDJSON stream: the servlet sets 200 before streaming, then emits a failure result line.
    String ndjson = resp.body();
    assertTrue(ndjson.contains("\"success\":false"));
    assertTrue(ndjson.contains("belongs to another account"));
  }

  @Test
  public void onboardingExistingClientMissingAdminRoleStreamsFailure() throws Exception {
    ResponseCapture resp = mockResponse();
    HttpServletRequest req = jsonRequest("/onboarding",
        "{\"clientName\":\"Acme\",\"currency\":\"EUR\",\"language\":\"en_US\"}");
    when(req.getHeader("Authorization")).thenReturn("Bearer valid-token");

    Currency currency = mock(Currency.class);
    when(currency.getId()).thenReturn("currency-1");
    Account account = mock(Account.class);
    when(account.getEmail()).thenReturn("user@test.com");

    try (var ctxMock = mockStatic(OBContext.class);
         var supportMock = mockStatic(EtendoGoJwtSupport.class);
         var dalMock = mockStatic(EtendoGoJwtDalHelper.class)) {
      Account authenticated = stubAuthenticatedAccount(dalMock);
      when(authenticated.getId()).thenReturn("account-1");
      dalMock.when(() -> EtendoGoJwtDalHelper.findCurrencyByIsoCode("EUR"))
          .thenReturn(currency);
      supportMock.when(() -> EtendoGoJwtSupport.findClientIdByName(FREE_PROVISIONING_NAME))
          .thenReturn("client-1");
      dalMock.when(() -> EtendoGoJwtDalHelper.clientBelongsToAccountEmail("client-1", "user@test.com"))
          .thenReturn(true);
      // No admin user-role for the resolved client -> resolveAdminContextData fails.
      dalMock.when(() -> EtendoGoJwtDalHelper.findClientAdminUserRole("client-1"))
          .thenReturn(null);

      servlet.doPost(req, resp.response);
    }

    String ndjson = resp.body();
    assertTrue(ndjson.contains("\"success\":false"));
    assertTrue(ndjson.contains("Admin role"));
  }

  @Test
  public void onboardingExistingClientResumesUntilDatasetOrgMissing() throws Exception {
    ResponseCapture resp = mockResponse();
    HttpServletRequest req = jsonRequest("/onboarding",
        "{\"clientName\":\"Acme\",\"currency\":\"EUR\",\"language\":\"en_US\"}");
    when(req.getHeader("Authorization")).thenReturn("Bearer valid-token");

    Currency currency = mock(Currency.class);
    when(currency.getId()).thenReturn("currency-1");
    Account account = mock(Account.class);
    when(account.getEmail()).thenReturn("user@test.com");

    UserRoles adminUserRole = mock(UserRoles.class);
    Role role = mock(Role.class);
    when(role.getId()).thenReturn("role-1");
    User contact = mock(User.class);
    when(contact.getId()).thenReturn("user-1");
    when(adminUserRole.getRole()).thenReturn(role);
    when(adminUserRole.getUserContact()).thenReturn(contact);

    try (var ctxMock = mockStatic(OBContext.class);
         var supportMock = mockStatic(EtendoGoJwtSupport.class);
         var dalMock = mockStatic(EtendoGoJwtDalHelper.class)) {
      Account authenticated = stubAuthenticatedAccount(dalMock);
      when(authenticated.getId()).thenReturn("account-1");
      dalMock.when(() -> EtendoGoJwtDalHelper.findCurrencyByIsoCode("EUR"))
          .thenReturn(currency);
      supportMock.when(() -> EtendoGoJwtSupport.findClientIdByName(FREE_PROVISIONING_NAME))
          .thenReturn("client-1");
      dalMock.when(() -> EtendoGoJwtDalHelper.clientBelongsToAccountEmail("client-1", "user@test.com"))
          .thenReturn(true);
      dalMock.when(() -> EtendoGoJwtDalHelper.findClientAdminUserRole("client-1"))
          .thenReturn(adminUserRole);
      supportMock.when(() -> EtendoGoJwtSupport.findStarOrgId("client-1"))
          .thenReturn("star-org");
      // Organization already exists (resume path) but no first org can be resolved afterwards.
      supportMock.when(() -> EtendoGoJwtSupport.organizationExists("client-1"))
          .thenReturn(true);
      dalMock.when(() -> EtendoGoJwtDalHelper.findFirstOrganization("client-1"))
          .thenReturn(null);

      servlet.doPost(req, resp.response);
    }

    String ndjson = resp.body();
    assertTrue(ndjson.contains("\"step\":\"organization\""));
    assertTrue(ndjson.contains("\"success\":false"));
    assertTrue(ndjson.contains("Organization not found"));
  }

  // ===================== applySocialName() — ETP-4749 =====================
  //
  // AD_Org.SocialName ("Nombre comercial" in the Organization settings window) was never
  // set anywhere in the onboarding flow — InitialOrgSetup/InitialSetupUtility (Etendo core)
  // only set Name/SearchKey. applySocialName() reuses the same clientName already used for
  // Name (which the wizard's CompanyStep.jsx already resolves to the user's Full Name for
  // Freelancers, since that business type has no separate Company Name field) and persists
  // it once, right after organization creation succeeds — never as part of
  // OnboardingOrgInfoService's idempotent reconcile chain, so a resumed/retried onboarding
  // call never overwrites a "Nombre comercial" the user already edited by hand.

  @Test
  public void applySocialNameSetsSocialNameAndSavesWhenOrganizationFound() {
    Organization org = mock(Organization.class);
    OBDal dal = mock(OBDal.class);

    try (var dalHelperMock = mockStatic(EtendoGoJwtDalHelper.class);
         var obDalMock = mockStatic(OBDal.class)) {
      dalHelperMock.when(() -> EtendoGoJwtDalHelper.findFirstOrganization("client-1"))
          .thenReturn(org);
      obDalMock.when(OBDal::getInstance).thenReturn(dal);

      boolean result = servlet.applySocialName("client-1", "Acme Corp");

      assertTrue(result);
      verify(org).setSocialName("Acme Corp");
      verify(dal).save(org);
      verify(dal).flush();
    }
  }

  @Test
  public void applySocialNameUsesTheFreelancerFullNameFallbackAlreadyResolvedByTheWizard() {
    // CompanyStep.jsx (schema_forge_core/packages/etendo-go-core) already resolves clientName
    // to the Freelancer's Full Name before this ever reaches Java — applySocialName has no
    // businessType branching of its own, it just persists whatever clientName it is given.
    Organization org = mock(Organization.class);
    OBDal dal = mock(OBDal.class);

    try (var dalHelperMock = mockStatic(EtendoGoJwtDalHelper.class);
         var obDalMock = mockStatic(OBDal.class)) {
      dalHelperMock.when(() -> EtendoGoJwtDalHelper.findFirstOrganization("client-1"))
          .thenReturn(org);
      obDalMock.when(OBDal::getInstance).thenReturn(dal);

      boolean result = servlet.applySocialName("client-1", "Jane Freelancer");

      assertTrue(result);
      verify(org).setSocialName("Jane Freelancer");
    }
  }

  @Test
  public void applySocialNameReturnsFalseAndDoesNotSaveWhenOrganizationNotFound() {
    OBDal dal = mock(OBDal.class);

    try (var dalHelperMock = mockStatic(EtendoGoJwtDalHelper.class);
         var obDalMock = mockStatic(OBDal.class)) {
      dalHelperMock.when(() -> EtendoGoJwtDalHelper.findFirstOrganization("client-1"))
          .thenReturn(null);
      obDalMock.when(OBDal::getInstance).thenReturn(dal);

      boolean result = servlet.applySocialName("client-1", "Acme Corp");

      assertFalse(result);
      verify(dal, never()).save(any());
      verify(dal, never()).flush();
    }
  }

  @Test
  public void applySocialNameHasNoBlankGuardUnlikeApplyTaxId() {
    // Unlike OnboardingOrgInfoService.applyTaxId() (a deliberate no-op on blank, because
    // Tax ID is genuinely optional), the clientName reaching this method is guaranteed
    // non-blank by parseOnboardingRequest()'s upstream validation (FIELD_CLIENT_NAME must
    // not be empty — see parseOnboardingRequest's own validation branch). This method
    // intentionally carries no blank guard of its own: a blank value would still be
    // persisted as-is. Locking this in so a future "harmonize with applyTaxId" refactor
    // doesn't silently mask an upstream validation bug behind a no-op here.
    Organization org = mock(Organization.class);
    OBDal dal = mock(OBDal.class);

    try (var dalHelperMock = mockStatic(EtendoGoJwtDalHelper.class);
         var obDalMock = mockStatic(OBDal.class)) {
      dalHelperMock.when(() -> EtendoGoJwtDalHelper.findFirstOrganization("client-1"))
          .thenReturn(org);
      obDalMock.when(OBDal::getInstance).thenReturn(dal);

      boolean result = servlet.applySocialName("client-1", "");

      assertTrue(result);
      verify(org).setSocialName("");
      verify(dal).save(org);
      verify(dal).flush();
    }
  }
}
