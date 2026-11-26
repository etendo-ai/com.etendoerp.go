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
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import javax.servlet.http.Cookie;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.codehaus.jettison.json.JSONObject;
import org.junit.Test;
import org.mockito.MockedStatic;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.ad.system.Client;
import org.openbravo.model.common.currency.Currency;

import com.etendoerp.go.common.PublicUrlResolver;
import com.etendoerp.go.onboarding.OnboardingCompanyProfileTransferService;
import com.etendoerp.go.payment.CheckoutRequestStore;
import com.etendoerp.go.payment.DemoDataTransferService;
import com.etendoerp.go.payment.HostedCheckoutService;
import com.etendoerp.go.payment.ProvisioningFailureReason;
import com.etendoerp.go.payment.TenantEnvironmentLifecycleService;
import com.etendoerp.go.payment.TenantPaywallService;
import com.etendoerp.go.payment.TenantPlanService;
import com.etendoerp.go.schemaforge.data.Account;
import com.etendoerp.go.schemaforge.data.CheckoutRequest;
import com.etendoerp.go.session.GoSessionRecord;
import com.etendoerp.go.session.GoSessionSecurity;
import com.etendoerp.go.session.GoSessionService;

/**
 * Unit tests for {@link EtendoGoJwtServlet} on the demo source of a purchase (ETP-5548, ETP-5047):
 * a paid retry keeps the demo persisted on the checkout and never infers one, a spent demo or a
 * productive environment (a canceled one included) is never the source of a purchase, a
 * productive-origin purchase is independent of the account's other demos, and the
 * {@code /billing/purchases} projection and validation of the selected demo.
 *
 * @covers com.etendoerp.go.rest.EtendoGoJwtServlet
 */
public class EtendoGoJwtServletPurchaseSourceTest extends EtendoGoJwtServletTestSupport {

  @Test
  public void paidRetryUsesThePersistedDemoEvenWhenSeveralFreeDemosExist() {
    Set<String> currentFreeDemos = Set.of("TRIAL-SELECTED", "TRIAL-OTHER");

    String resolved = EtendoGoJwtServlet.resolvePaidDemoClientId(
        true, "TRIAL-SELECTED", currentFreeDemos);

    assertEquals("The persisted checkout selection remains authoritative on retry",
        "TRIAL-SELECTED", resolved);
  }

  @Test
  public void paidRetryRejectsPersistedDemoWhenItIsNoLongerOwnedOrFree() {
    IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
        () -> EtendoGoJwtServlet.resolvePaidDemoClientId(
            true, "TRIAL-SELECTED", Set.of("TRIAL-OTHER")));

    assertTrue(error.getMessage().contains("no longer available"));
  }

  @Test
  public void legacyPaidRetryWithoutRecordedSelectionNeverInfersADemo() {
    assertNull(EtendoGoJwtServlet.resolvePaidDemoClientId(false, null, Set.of("TRIAL-ONLY")));
    assertNull(EtendoGoJwtServlet.resolvePaidDemoClientId(false, null, Set.of()));
    assertNull(EtendoGoJwtServlet.resolvePaidDemoClientId(
        false, null, Set.of("TRIAL-1", "TRIAL-2")));
  }

  @Test
  public void explicitlyEmptyDemoSelectionDoesNotInferADemoThatAppearsLater() {
    // A recorded null is an intentional productive-origin purchase, unlike a legacy row with
    // no recorded-selection marker. Neither case may infer a source from the current demo set.
    assertNull(EtendoGoJwtServlet.resolvePaidDemoClientId(
        true, null, Set.of("TRIAL-ADDED-AFTER-CHECKOUT")));
  }

  @Test
  public void paidOnboardingUsesPersistedDemoAndLegacySkipsTransfers()
      throws Exception {
    CheckoutRequestStore store = mock(CheckoutRequestStore.class);
    servlet.checkoutRequestStore = store;
    TenantPaywallService paywall = new TenantPaywallService();
    Field confirmation = TenantPaywallService.class.getDeclaredField("paymentConfirmation");
    confirmation.setAccessible(true);
    confirmation.set(paywall, (TenantPaywallService.PaymentConfirmation) (token, email, name) -> true);
    servlet.tenantPaywallService = paywall;
    TenantEnvironmentLifecycleService lifecycle = mock(TenantEnvironmentLifecycleService.class);
    OnboardingCompanyProfileTransferService profileTransfer =
        mock(OnboardingCompanyProfileTransferService.class);
    servlet.tenantEnvironmentLifecycleService = lifecycle;
    servlet.onboardingCompanyProfileTransferService = profileTransfer;
    when(lifecycle.associateDemoWithProductive("STORED-DEMO", "NEW-PRODUCTIVE")).thenReturn(true);
    when(store.hasRecordedDemoSelection("purchase-1", "account-1", "user@test.com"))
        .thenReturn(true, false);
    when(store.findDemoClientId("purchase-1", "account-1", "user@test.com"))
        .thenReturn("STORED-DEMO");
    when(store.claimForProvisioning("purchase-1", "account-1", "user@test.com"))
        .thenReturn(true);
    when(store.findProvisioningAttempt("purchase-1", "account-1", "user@test.com"))
        .thenReturn(7L);

    Object recorded = prepareOnboardingForPersistedSelection();
    Object recordedRequest = getField(recorded, "request");
    assertEquals("STORED-DEMO", getField(recordedRequest, "demoClientId"));
    invokeProfileTransfer("STORED-DEMO", "NEW-PRODUCTIVE");

    Object legacy = prepareOnboardingForPersistedSelection();
    Object legacyRequest = getField(legacy, "request");
    assertNull(getField(legacyRequest, "demoClientId"));
    invokeProfileTransfer(null, "LEGACY-PRODUCTIVE");
    servlet.startDemoDataTransferBestEffort("purchase-1", null, "LEGACY-PRODUCTIVE",
        "account-1", "user@test.com");
    verify(lifecycle, times(1)).associateDemoWithProductive("STORED-DEMO", "NEW-PRODUCTIVE");
    verify(lifecycle, times(1)).isAssociatedWithProductive("STORED-DEMO");
    // ETP-5047: the persisted source is checked for being a productive environment too.
    verify(lifecycle, times(1)).isProductiveEnvironment("STORED-DEMO");
    verifyNoMoreInteractions(lifecycle);
    verify(profileTransfer, times(1)).copy("STORED-DEMO", "NEW-PRODUCTIVE", "ORG-1");
    verifyNoMoreInteractions(profileTransfer);
    verify(store, times(1)).findDemoClientId("purchase-1", "account-1", "user@test.com");
  }

  /**
   * ETP-5548: two purchases started from one demo. The first to finish took the demo as its
   * origin; the second is already paid, so it is not refused — it becomes a clean productive
   * environment with no source, transfer or demo revocation.
   */
  @Test
  public void paidSetupFromAnAlreadyAssociatedDemoContinuesWithoutASource() throws Exception {
    CheckoutRequestStore store = mock(CheckoutRequestStore.class);
    servlet.checkoutRequestStore = store;
    TenantPaywallService paywall = new TenantPaywallService();
    Field confirmation = TenantPaywallService.class.getDeclaredField("paymentConfirmation");
    confirmation.setAccessible(true);
    confirmation.set(paywall, (TenantPaywallService.PaymentConfirmation) (token, email, name) -> true);
    servlet.tenantPaywallService = paywall;
    TenantEnvironmentLifecycleService lifecycle = mock(TenantEnvironmentLifecycleService.class);
    servlet.tenantEnvironmentLifecycleService = lifecycle;
    when(lifecycle.isAssociatedWithProductive("STORED-DEMO")).thenReturn(true);
    when(store.hasRecordedDemoSelection("purchase-1", "account-1", "user@test.com"))
        .thenReturn(true);
    when(store.findDemoClientId("purchase-1", "account-1", "user@test.com"))
        .thenReturn("STORED-DEMO");
    when(store.claimForProvisioning("purchase-1", "account-1", "user@test.com"))
        .thenReturn(true);
    when(store.findProvisioningAttempt("purchase-1", "account-1", "user@test.com"))
        .thenReturn(7L);

    Object prepared = prepareOnboardingForPersistedSelection();

    assertNull("A spent demo is never the origin of a second productive environment",
        getField(getField(prepared, "request"), "demoClientId"));
    verify(lifecycle, never()).associateDemoWithProductive(anyString(), anyString());
  }

  /**
   * ETP-5047: a purchase that persisted a canceled productive environment as its source (recorded
   * before eligibility excluded productive environments) is already paid, so it is not refused —
   * like the spent demo above, it becomes a clean productive environment with no source: the
   * current free demos are not even looked up (no 409 DEMO_SELECTION_REQUIRED can follow), and
   * neither the company profile copy nor the demo data transfer starts.
   */
  @Test
  public void paidSetupFromAProductiveSourceContinuesWithoutASource() throws Exception {
    CheckoutRequestStore store = mock(CheckoutRequestStore.class);
    servlet.checkoutRequestStore = store;
    TenantPaywallService paywall = new TenantPaywallService();
    Field confirmation = TenantPaywallService.class.getDeclaredField("paymentConfirmation");
    confirmation.setAccessible(true);
    confirmation.set(paywall, (TenantPaywallService.PaymentConfirmation) (token, email, name) -> true);
    servlet.tenantPaywallService = paywall;
    TenantEnvironmentLifecycleService lifecycle = mock(TenantEnvironmentLifecycleService.class);
    servlet.tenantEnvironmentLifecycleService = lifecycle;
    OnboardingCompanyProfileTransferService profileTransfer =
        mock(OnboardingCompanyProfileTransferService.class);
    servlet.onboardingCompanyProfileTransferService = profileTransfer;
    DemoDataTransferService dataTransfer = mock(DemoDataTransferService.class);
    servlet.demoDataTransferService = dataTransfer;
    when(lifecycle.isProductiveEnvironment("STORED-DEMO")).thenReturn(true);
    when(store.hasRecordedDemoSelection("purchase-1", "account-1", "user@test.com"))
        .thenReturn(true);
    when(store.findDemoClientId("purchase-1", "account-1", "user@test.com"))
        .thenReturn("STORED-DEMO");
    when(store.claimForProvisioning("purchase-1", "account-1", "user@test.com"))
        .thenReturn(true);
    when(store.findProvisioningAttempt("purchase-1", "account-1", "user@test.com"))
        .thenReturn(7L);

    AtomicInteger answeredStatus = new AtomicInteger(-1);
    AtomicReference<String> answeredBody = new AtomicReference<>();

    Object prepared = prepareOnboardingForPersistedSelection((dal, response) -> {
      dal.verify(() -> EtendoGoJwtDalHelper.findFreeTenantIdsByAccountEmail(anyString()), never());
      answeredStatus.set(response.status);
      answeredBody.set(response.body());
    });

    Object preparedRequest = getField(prepared, "request");
    assertNull("A productive environment is never the origin of another productive environment",
        getField(preparedRequest, "demoClientId"));
    assertEquals("nothing is answered while the paid setup goes on, least of all a 409",
        0, answeredStatus.get());
    assertEquals("", answeredBody.get());

    // The two downstream steps receive the source exactly as the paid setup derives it.
    Method sourceOf = EtendoGoJwtServlet.class.getDeclaredMethod("resolveDemoSourceClientId",
        boolean.class, preparedRequest.getClass());
    sourceOf.setAccessible(true);
    String demoSourceClientId = (String) sourceOf.invoke(null, true, preparedRequest);
    assertNull(demoSourceClientId);
    invokeProfileTransfer(demoSourceClientId, "NEW-PRODUCTIVE");
    servlet.startDemoDataTransferBestEffort("purchase-1", demoSourceClientId, "NEW-PRODUCTIVE",
        "account-1", "user@test.com");

    verify(lifecycle, never()).associateDemoWithProductive(anyString(), anyString());
    verifyNoInteractions(profileTransfer, dataTransfer);
    verify(store, never()).findTransferSelection(anyString(), anyString(), anyString());
  }

  /**
   * ETP-5548: a refused claim says why. A failure no retry can fix and a finished environment are
   * final, so they must not answer "still running", which invites the customer to keep retrying.
   */
  @Test
  public void refusedClaimExplainsAFinalStateInsteadOfStillRunning() throws Exception {
    CheckoutRequestStore store = mock(CheckoutRequestStore.class);
    servlet.checkoutRequestStore = store;
    CheckoutRequest nameInUse = mock(CheckoutRequest.class);
    when(nameInUse.getFailureReason()).thenReturn(ProvisioningFailureReason.encode(
        ProvisioningFailureReason.CODE_CLIENT_NAME_IN_USE, "raw cause"));
    when(store.deriveProvisioningStatus(nameInUse))
        .thenReturn(CheckoutRequestStore.DERIVED_STATUS_PROVISIONING_FAILED);
    when(store.isProvisioningRetryAllowed(nameInUse)).thenReturn(false);
    CheckoutRequest provisioned = mock(CheckoutRequest.class);
    when(store.deriveProvisioningStatus(provisioned))
        .thenReturn(CheckoutRequestStore.DERIVED_STATUS_PROVISIONED);
    CheckoutRequest running = mock(CheckoutRequest.class);
    when(store.deriveProvisioningStatus(running)).thenReturn("provisioning");

    JSONObject notRetryable = claimRefusal(nameInUse);
    assertEquals("PROVISIONING_RETRY_NOT_ALLOWED", notRetryable.getString("code"));
    assertTrue(notRetryable.getString("userMessage").startsWith(
        "The account already has a productive environment with this company name"));
    assertFalse("The raw cause never reaches the customer",
        notRetryable.toString().contains("raw cause"));
    assertEquals("PROVISIONING_ALREADY_COMPLETED", claimRefusal(provisioned).getString("code"));
    assertEquals("PROVISIONING_ALREADY_IN_PROGRESS", claimRefusal(running).getString("code"));
  }

  private JSONObject claimRefusal(CheckoutRequest checkoutRequest) throws Exception {
    ResponseCapture response = mockResponse();
    Method refusal = EtendoGoJwtServlet.class.getDeclaredMethod("writeClaimRefusal",
        HttpServletResponse.class, CheckoutRequest.class);
    refusal.setAccessible(true);
    refusal.invoke(servlet, response.response, checkoutRequest);
    assertEquals(409, response.status);
    return new JSONObject(response.body()).getJSONObject("error");
  }

  private Object prepareOnboardingForPersistedSelection() throws Exception {
    return prepareOnboardingForPersistedSelection((dal, response) -> { });
  }

  /**
   * As {@link #prepareOnboardingForPersistedSelection()}, then hands the still-open DAL helper
   * mock and the response to {@code afterPrepare}, so a spec can verify what was (not) looked up
   * and (not) answered.
   */
  private Object prepareOnboardingForPersistedSelection(
      java.util.function.BiConsumer<MockedStatic<EtendoGoJwtDalHelper>, ResponseCapture>
          afterPrepare) throws Exception {
    HttpServletRequest request = jsonRequest("/onboarding",
        "{\"clientName\":\"New Productive\",\"currency\":\"EUR\","
            + "\"paymentToken\":\"purchase-1\",\"demoClientId\":\"BODY-DEMO\","
            + "\"dataTransfer\":{\"products\":true,\"contacts\":false}}");
    when(request.getHeader("Authorization")).thenReturn("Bearer valid-token");
    ResponseCapture response = mockResponse();
    Account account = mock(Account.class);
    when(account.getId()).thenReturn("account-1");
    when(account.getEmail()).thenReturn("user@test.com");
    Currency currency = mock(Currency.class);
    when(currency.getId()).thenReturn("currency-1");
    Method prepare = EtendoGoJwtServlet.class.getDeclaredMethod("prepareOnboarding",
        HttpServletRequest.class, HttpServletResponse.class);
    prepare.setAccessible(true);
    try (MockedStatic<OBContext> context = mockStatic(OBContext.class);
        MockedStatic<EtendoGoJwtDalHelper> dal = mockStatic(EtendoGoJwtDalHelper.class);
        MockedStatic<EtendoGoJwtSupport> support = mockStatic(EtendoGoJwtSupport.class);
        MockedStatic<com.etendoerp.go.session.GoLegacyBearer> legacyBearer =
            mockStatic(com.etendoerp.go.session.GoLegacyBearer.class);
        MockedStatic<EmailVerificationDalHelper> verification =
            mockStatic(EmailVerificationDalHelper.class)) {
      legacyBearer.when(com.etendoerp.go.session.GoLegacyBearer::isEnabled).thenReturn(true);
      verification.when(() -> EmailVerificationDalHelper.isEmailVerificationPending(account))
          .thenReturn(false);
      dal.when(() -> EtendoGoJwtDalHelper.findActiveAccountByBearerToken("valid-token"))
          .thenReturn(account);
      dal.when(() -> EtendoGoJwtDalHelper.findCurrencyByIsoCode("EUR")).thenReturn(currency);
      dal.when(() -> EtendoGoJwtDalHelper.countTenantsOwnedByAccountEmail("user@test.com"))
          .thenReturn(1);
      dal.when(() -> EtendoGoJwtDalHelper.hasOwnedEnvironmentForAccountEmail("user@test.com"))
          .thenReturn(true);
      dal.when(() -> EtendoGoJwtDalHelper.findFreeTenantIdsByAccountEmail("user@test.com"))
          .thenReturn(Set.of("STORED-DEMO"));
      support.when(() -> EtendoGoJwtSupport.findClientIdByName("New Productive"))
          .thenReturn(null);
      Object result = prepare.invoke(servlet, request, response.response);
      assertNotNull("preparation should pass authentication, currency, ownership and paywall", result);
      afterPrepare.accept(dal, response);
      return result;
    }
  }

  private void invokeProfileTransfer(String sourceClientId, String targetClientId)
      throws Exception {
    Method transferProfile = EtendoGoJwtServlet.class.getDeclaredMethod(
        "transferDemoCompanyProfile", String.class, String.class, String.class, String.class);
    transferProfile.setAccessible(true);
    transferProfile.invoke(servlet, "user@test.com", sourceClientId, targetClientId, "ORG-1");
  }

  private static Object getField(Object target, String fieldName) throws Exception {
    Field field = target.getClass().getDeclaredField(fieldName);
    field.setAccessible(true);
    return field.get(target);
  }

  @Test
  public void productiveOriginCreatesIndependentPurchaseDespiteOtherOwnedDemos() throws Exception {
    Account account = mock(Account.class);
    when(account.getId()).thenReturn("account-1");
    when(account.getEmail()).thenReturn("owner@example.test");
    GoSessionService sessions = mock(GoSessionService.class);
    GoSessionRecord session = new GoSessionRecord();
    session.setAccountId("account-1");
    session.setCsrfToken("csrf-token-value-123456");
    session.setCtxClientId("PROD-1");
    when(sessions.resolve("session-cookie-token")).thenReturn(session);

    EtendoGoJwtServlet purchaseServlet = new EtendoGoJwtServlet(
        mock(TransactionalAuthEmailSender.class),
        mock(EtendoGoSsoProviderRegistry.class), sessions);
    CheckoutRequestStore requestStore = mock(CheckoutRequestStore.class);
    HostedCheckoutService checkout = mock(HostedCheckoutService.class);
    DemoDataTransferService transfer = mock(DemoDataTransferService.class);
    TenantPlanService tenantPlan = mock(TenantPlanService.class);
    purchaseServlet.checkoutRequestStore = requestStore;
    purchaseServlet.hostedCheckoutService = checkout;
    purchaseServlet.demoDataTransferService = transfer;
    purchaseServlet.tenantPlanService = tenantPlan;
    when(tenantPlan.resolvePlan("PROD-1")).thenReturn(TenantPlanService.PLAN_PRODUCTIVE);

    JSONObject checkoutResult = new JSONObject().put("requestId", "purchase-prod-1");
    when(checkout.createSession(eq("account-1"), eq("owner@example.test"), eq("New Production"),
        eq("https://app.example.test"), isNull(), argThat(
            EtendoGoJwtServletTestSupport::selectsNoDemo)))
        .thenReturn(checkoutResult);
    HttpServletRequest request = jsonRequest("/billing/purchases",
        "{\"clientName\":\"New Production\",\"demoClientId\":\"DEMO-1\","
            + "\"dataTransfer\":{\"products\":true,\"contacts\":true}}");
    when(request.getMethod()).thenReturn("POST");
    when(request.getCookies()).thenReturn(
        new Cookie[] { new Cookie(GoSessionSecurity.COOKIE_NAME, "session-cookie-token") });
    when(request.getHeader(GoSessionSecurity.CSRF_HEADER)).thenReturn("csrf-token-value-123456");
    when(request.getHeader("Origin")).thenReturn("https://app.example.test");
    when(request.getHeader("Referer")).thenReturn(null);
    when(request.getRequestURL()).thenReturn(new StringBuffer("https://app.example.test/sws/go/billing/purchases"));
    ResponseCapture response = mockResponse();

    try (MockedStatic<OBContext> context = mockStatic(OBContext.class);
        MockedStatic<EtendoGoJwtDalHelper> dal = mockStatic(EtendoGoJwtDalHelper.class);
        MockedStatic<PublicUrlResolver> urls = mockStatic(PublicUrlResolver.class)) {
      dal.when(() -> EtendoGoJwtDalHelper.findActiveAccountById("account-1")).thenReturn(account);
      dal.when(() -> EtendoGoJwtDalHelper.hasOwnedEnvironmentForAccountEmail("owner@example.test"))
          .thenReturn(true);
      dal.when(() -> EtendoGoJwtDalHelper.clientBelongsToAccountEmail("PROD-1", "owner@example.test"))
          .thenReturn(true);
      urls.when(PublicUrlResolver::resolveConfiguredAppBaseUrl).thenReturn("https://app.example.test");

      purchaseServlet.doPost(request, response.response);

      verify(checkout).createSession(eq("account-1"), eq("owner@example.test"),
          eq("New Production"), eq("https://app.example.test"), isNull(),
          argThat(EtendoGoJwtServletTestSupport::selectsNoDemo));
      verify(requestStore).findActiveForAccountAndClientName("account-1", "owner@example.test",
          "New Production");
      org.mockito.Mockito.verifyNoInteractions(transfer);
    }

    assertEquals(201, response.status);
    JSONObject responseBody = new JSONObject(response.body());
    assertFalse(responseBody.has("demoClientId"));
    assertFalse(responseBody.has("dataTransfer"));
  }

  /**
   * ETP-5548 (product decision): once a demo originated a productive environment, a later
   * purchase from it is a clean productive environment — no source, no data transfer — even when
   * the request still names that demo.
   */
  @Test
  public void purchaseFromAnAlreadyAssociatedDemoCreatesACleanProductive() throws Exception {
    assertCleanProductivePurchaseFromSessionDemo(true, true);
  }

  /**
   * ETP-5743: a demo the account was only invited into is not the buyer's to copy. Buying from
   * inside it creates a clean productive environment, even when the request names it as source.
   */
  @Test
  public void purchaseFromADemoTheAccountIsOnlyInvitedIntoCreatesACleanProductive()
      throws Exception {
    assertCleanProductivePurchaseFromSessionDemo(false, false);
  }

  private void assertCleanProductivePurchaseFromSessionDemo(boolean sessionDemoAssociated,
      boolean sessionDemoOwned) throws Exception {
    Account account = mock(Account.class);
    when(account.getId()).thenReturn("account-1");
    when(account.getEmail()).thenReturn("owner@example.test");
    GoSessionService sessions = mock(GoSessionService.class);
    GoSessionRecord session = new GoSessionRecord();
    session.setAccountId("account-1");
    session.setCsrfToken("csrf-token-value-123456");
    session.setCtxClientId("SPENT-DEMO");
    when(sessions.resolve("session-cookie-token")).thenReturn(session);

    EtendoGoJwtServlet purchaseServlet = new EtendoGoJwtServlet(
        mock(TransactionalAuthEmailSender.class),
        mock(EtendoGoSsoProviderRegistry.class), sessions);
    CheckoutRequestStore requestStore = mock(CheckoutRequestStore.class);
    HostedCheckoutService checkout = mock(HostedCheckoutService.class);
    DemoDataTransferService transfer = mock(DemoDataTransferService.class);
    TenantPlanService tenantPlan = mock(TenantPlanService.class);
    purchaseServlet.checkoutRequestStore = requestStore;
    purchaseServlet.hostedCheckoutService = checkout;
    purchaseServlet.demoDataTransferService = transfer;
    purchaseServlet.tenantPlanService = tenantPlan;
    when(tenantPlan.resolvePlan("SPENT-DEMO")).thenReturn(TenantPlanService.PLAN_FREE);
    TenantEnvironmentLifecycleService lifecycle = mock(TenantEnvironmentLifecycleService.class);
    purchaseServlet.tenantEnvironmentLifecycleService = lifecycle;
    when(lifecycle.isAssociatedWithProductive("SPENT-DEMO")).thenReturn(sessionDemoAssociated);

    JSONObject checkoutResult = new JSONObject().put("requestId", "purchase-prod-1");
    when(checkout.createSession(eq("account-1"), eq("owner@example.test"), eq("New Production"),
        eq("https://app.example.test"), isNull(), argThat(
            EtendoGoJwtServletTestSupport::selectsNoDemo)))
        .thenReturn(checkoutResult);
    HttpServletRequest request = jsonRequest("/billing/purchases",
        "{\"clientName\":\"New Production\",\"demoClientId\":\"SPENT-DEMO\","
            + "\"dataTransfer\":{\"products\":true,\"contacts\":true}}");
    when(request.getMethod()).thenReturn("POST");
    when(request.getCookies()).thenReturn(
        new Cookie[] { new Cookie(GoSessionSecurity.COOKIE_NAME, "session-cookie-token") });
    when(request.getHeader(GoSessionSecurity.CSRF_HEADER)).thenReturn("csrf-token-value-123456");
    when(request.getHeader("Origin")).thenReturn("https://app.example.test");
    when(request.getHeader("Referer")).thenReturn(null);
    when(request.getRequestURL()).thenReturn(new StringBuffer("https://app.example.test/sws/go/billing/purchases"));
    ResponseCapture response = mockResponse();

    try (MockedStatic<OBContext> context = mockStatic(OBContext.class);
        MockedStatic<EtendoGoJwtDalHelper> dal = mockStatic(EtendoGoJwtDalHelper.class);
        MockedStatic<PublicUrlResolver> urls = mockStatic(PublicUrlResolver.class)) {
      dal.when(() -> EtendoGoJwtDalHelper.findActiveAccountById("account-1")).thenReturn(account);
      dal.when(() -> EtendoGoJwtDalHelper.hasOwnedEnvironmentForAccountEmail("owner@example.test"))
          .thenReturn(true);
      dal.when(() -> EtendoGoJwtDalHelper.clientBelongsToAccountEmail("SPENT-DEMO", "owner@example.test"))
          .thenReturn(true);
      dal.when(() -> EtendoGoJwtDalHelper.clientOwnedByAccountEmail("SPENT-DEMO", "owner@example.test"))
          .thenReturn(sessionDemoOwned);
      urls.when(PublicUrlResolver::resolveConfiguredAppBaseUrl).thenReturn("https://app.example.test");

      purchaseServlet.doPost(request, response.response);

      verify(checkout).createSession(eq("account-1"), eq("owner@example.test"),
          eq("New Production"), eq("https://app.example.test"), isNull(),
          argThat(EtendoGoJwtServletTestSupport::selectsNoDemo));
      verify(requestStore).findActiveForAccountAndClientName("account-1", "owner@example.test",
          "New Production");
      org.mockito.Mockito.verifyNoInteractions(transfer);
    }

    assertEquals(201, response.status);
    JSONObject responseBody = new JSONObject(response.body());
    assertFalse(responseBody.has("demoClientId"));
    assertFalse(responseBody.has("dataTransfer"));
  }

  /**
   * ETP-5047 — a canceled productive tenant reads plan "free" (its closed row answers canceled),
   * yet it is not a demo. A purchase from its own session is a clean productive environment, as on
   * develop: no DEMO_SELECTION_REQUIRED, and never itself as the source, even when named.
   */
  @Test
  public void purchaseFromACanceledProductiveCreatesACleanProductiveWithoutASource()
      throws Exception {
    assertCanceledProductiveOriginBuysClean(
        "{\"clientName\":\"New Production\"}");
    assertCanceledProductiveOriginBuysClean(
        "{\"clientName\":\"New Production\",\"demoClientId\":\"CANCELED-PROD\","
            + "\"dataTransfer\":{\"products\":true,\"contacts\":true}}");
  }

  private void assertCanceledProductiveOriginBuysClean(String body) throws Exception {
    Account account = mock(Account.class);
    when(account.getId()).thenReturn("account-1");
    when(account.getEmail()).thenReturn("owner@example.test");
    GoSessionService sessions = mock(GoSessionService.class);
    GoSessionRecord session = new GoSessionRecord();
    session.setAccountId("account-1");
    session.setCsrfToken("csrf-token-value-123456");
    session.setCtxClientId("CANCELED-PROD");
    when(sessions.resolve("session-cookie-token")).thenReturn(session);

    EtendoGoJwtServlet purchaseServlet = new EtendoGoJwtServlet(
        mock(TransactionalAuthEmailSender.class),
        mock(EtendoGoSsoProviderRegistry.class), sessions);
    CheckoutRequestStore requestStore = mock(CheckoutRequestStore.class);
    HostedCheckoutService checkout = mock(HostedCheckoutService.class);
    DemoDataTransferService transfer = mock(DemoDataTransferService.class);
    TenantPlanService tenantPlan = mock(TenantPlanService.class);
    purchaseServlet.checkoutRequestStore = requestStore;
    purchaseServlet.hostedCheckoutService = checkout;
    purchaseServlet.demoDataTransferService = transfer;
    purchaseServlet.tenantPlanService = tenantPlan;
    when(tenantPlan.resolvePlan("CANCELED-PROD")).thenReturn(TenantPlanService.PLAN_FREE);
    TenantEnvironmentLifecycleService lifecycle = mock(TenantEnvironmentLifecycleService.class);
    purchaseServlet.tenantEnvironmentLifecycleService = lifecycle;
    when(lifecycle.isProductiveEnvironment("CANCELED-PROD")).thenReturn(true);

    JSONObject checkoutResult = new JSONObject().put("requestId", "purchase-prod-1");
    when(checkout.createSession(eq("account-1"), eq("owner@example.test"), eq("New Production"),
        eq("https://app.example.test"), isNull(), argThat(
            EtendoGoJwtServletTestSupport::selectsNoDemo)))
        .thenReturn(checkoutResult);
    HttpServletRequest request = jsonRequest("/billing/purchases", body);
    when(request.getMethod()).thenReturn("POST");
    when(request.getCookies()).thenReturn(
        new Cookie[] { new Cookie(GoSessionSecurity.COOKIE_NAME, "session-cookie-token") });
    when(request.getHeader(GoSessionSecurity.CSRF_HEADER)).thenReturn("csrf-token-value-123456");
    when(request.getHeader("Origin")).thenReturn("https://app.example.test");
    when(request.getHeader("Referer")).thenReturn(null);
    when(request.getRequestURL()).thenReturn(new StringBuffer("https://app.example.test/sws/go/billing/purchases"));
    ResponseCapture response = mockResponse();
    OBDal dalInstance = mock(OBDal.class);
    when(dalInstance.get(Client.class, "CANCELED-PROD")).thenReturn(mock(Client.class));

    try (MockedStatic<OBContext> context = mockStatic(OBContext.class);
        MockedStatic<OBDal> obDal = mockStatic(OBDal.class);
        MockedStatic<EtendoGoJwtDalHelper> dal = mockStatic(EtendoGoJwtDalHelper.class);
        MockedStatic<PublicUrlResolver> urls = mockStatic(PublicUrlResolver.class)) {
      obDal.when(OBDal::getInstance).thenReturn(dalInstance);
      dal.when(() -> EtendoGoJwtDalHelper.findActiveAccountById("account-1")).thenReturn(account);
      dal.when(() -> EtendoGoJwtDalHelper.hasOwnedEnvironmentForAccountEmail("owner@example.test"))
          .thenReturn(true);
      dal.when(() -> EtendoGoJwtDalHelper.clientBelongsToAccountEmail("CANCELED-PROD",
          "owner@example.test")).thenReturn(true);
      urls.when(PublicUrlResolver::resolveConfiguredAppBaseUrl).thenReturn("https://app.example.test");

      purchaseServlet.doPost(request, response.response);

      org.mockito.Mockito.verifyNoInteractions(transfer);
    }

    assertEquals(response.body(), 201, response.status);
    JSONObject responseBody = new JSONObject(response.body());
    assertFalse(responseBody.has("demoClientId"));
    assertFalse(responseBody.has("dataTransfer"));
    verify(checkout).createSession(eq("account-1"), eq("owner@example.test"),
        eq("New Production"), eq("https://app.example.test"), isNull(),
        argThat(EtendoGoJwtServletTestSupport::selectsNoDemo));
  }

  @Test
  public void billingPurchaseProjectionIncludesCreatedClientIdForSelectorReconciliation()
      throws Exception {
    CheckoutRequest purchase = mock(CheckoutRequest.class);
    Client createdClient = mock(Client.class);
    when(purchase.getRequest()).thenReturn("purchase-123");
    when(purchase.getCheckoutRequestStatus()).thenReturn("PROVISIONED");
    when(purchase.getClientName()).thenReturn("Acme Production");
    when(purchase.getCreatedClient()).thenReturn(createdClient);
    when(createdClient.getId()).thenReturn("created-client-456");

    Method projection = EtendoGoJwtServlet.class.getDeclaredMethod(
        "buildBillingPurchaseJson", CheckoutRequest.class);
    projection.setAccessible(true);
    JSONObject body = (JSONObject) projection.invoke(servlet, purchase);

    assertEquals("created-client-456", body.getString("createdClientId"));
  }

  @Test
  public void freeOriginWithoutDemoSelectionReturnsDemoSelectionRequired() throws Exception {
    Account account = mock(Account.class);
    when(account.getId()).thenReturn("account-1");
    when(account.getEmail()).thenReturn("owner@example.test");
    GoSessionService sessions = mock(GoSessionService.class);
    GoSessionRecord session = new GoSessionRecord();
    session.setAccountId("account-1");
    session.setCsrfToken("csrf-token-value-123456");
    session.setCtxClientId("FREE-1");
    when(sessions.resolve("session-cookie-token")).thenReturn(session);

    EtendoGoJwtServlet purchaseServlet = new EtendoGoJwtServlet(
        mock(TransactionalAuthEmailSender.class),
        mock(EtendoGoSsoProviderRegistry.class), sessions);
    CheckoutRequestStore requestStore = mock(CheckoutRequestStore.class);
    HostedCheckoutService checkout = mock(HostedCheckoutService.class);
    TenantPlanService tenantPlan = mock(TenantPlanService.class);
    purchaseServlet.checkoutRequestStore = requestStore;
    purchaseServlet.hostedCheckoutService = checkout;
    purchaseServlet.tenantPlanService = tenantPlan;
    purchaseServlet.tenantEnvironmentLifecycleService = mock(TenantEnvironmentLifecycleService.class);
    when(tenantPlan.resolvePlan("FREE-1")).thenReturn(TenantPlanService.PLAN_FREE);
    HttpServletRequest request = jsonRequest("/billing/purchases",
        "{\"clientName\":\"New Production\"}");
    when(request.getMethod()).thenReturn("POST");
    when(request.getCookies()).thenReturn(
        new Cookie[] { new Cookie(GoSessionSecurity.COOKIE_NAME, "session-cookie-token") });
    when(request.getHeader(GoSessionSecurity.CSRF_HEADER)).thenReturn("csrf-token-value-123456");
    when(request.getHeader("Origin")).thenReturn("https://app.example.test");
    when(request.getHeader("Referer")).thenReturn(null);
    when(request.getRequestURL()).thenReturn(
        new StringBuffer("https://app.example.test/sws/go/billing/purchases"));
    ResponseCapture response = mockResponse();

    try (MockedStatic<OBContext> context = mockStatic(OBContext.class);
        MockedStatic<EtendoGoJwtDalHelper> dal = mockStatic(EtendoGoJwtDalHelper.class)) {
      dal.when(() -> EtendoGoJwtDalHelper.findActiveAccountById("account-1"))
          .thenReturn(account);
      dal.when(() -> EtendoGoJwtDalHelper.hasOwnedEnvironmentForAccountEmail("owner@example.test"))
          .thenReturn(true);
      dal.when(() -> EtendoGoJwtDalHelper.clientBelongsToAccountEmail("FREE-1", "owner@example.test"))
          .thenReturn(true);
      dal.when(() -> EtendoGoJwtDalHelper.clientOwnedByAccountEmail("FREE-1", "owner@example.test"))
          .thenReturn(true);

      purchaseServlet.doPost(request, response.response);

      verify(requestStore).findActiveForAccountAndClientName("account-1", "owner@example.test",
          "New Production");
    }

    assertEquals(400, response.status);
    assertEquals("DEMO_SELECTION_REQUIRED",
        new JSONObject(response.body()).getJSONObject("error").getString("code"));
    org.mockito.Mockito.verify(checkout, never()).createSession(anyString(), anyString(), anyString(),
        anyString(), any(), any(HostedCheckoutService.SessionOptions.class));
  }

  @Test
  public void billingPurchaseRejectsDemoOwnedByAnotherAccount() throws Exception {
    assertInvalidDemoSelectionRejected(false, false, TenantPlanService.PLAN_FREE, false, false);
  }

  /**
   * ETP-5743: an invited member has a user in the owner's demo, but choosing it as the source
   * would copy and lock someone else's environment.
   */
  @Test
  public void billingPurchaseRejectsDemoTheAccountIsOnlyInvitedInto() throws Exception {
    assertInvalidDemoSelectionRejected(true, false, TenantPlanService.PLAN_FREE, false, false);
  }

  @Test
  public void billingPurchaseRejectsOwnedDemoThatIsNoLongerFree() throws Exception {
    assertInvalidDemoSelectionRejected(true, true, TenantPlanService.PLAN_PRODUCTIVE, false, false);
  }

  /** ETP-5548: a demo that already originated a productive environment is not a source again. */
  @Test
  public void billingPurchaseRejectsDemoThatAlreadyOriginatedAProductive() throws Exception {
    assertInvalidDemoSelectionRejected(true, true, TenantPlanService.PLAN_FREE, true, false);
  }

  /**
   * ETP-5047 — a canceled productive environment reads plan "free" and carries no association
   * marker, but it is not a demo: naming it as the source of a purchase is refused.
   */
  @Test
  public void billingPurchaseRejectsACanceledProductiveAsDemoSource() throws Exception {
    assertInvalidDemoSelectionRejected(true, true, TenantPlanService.PLAN_FREE, false, true);
  }

  private void assertInvalidDemoSelectionRejected(boolean selectedDemoMember,
      boolean selectedDemoOwned, String selectedDemoPlan, boolean selectedDemoAssociated,
      boolean selectedDemoProductive) throws Exception {
    Account account = mock(Account.class);
    when(account.getId()).thenReturn("account-1");
    when(account.getEmail()).thenReturn("owner@example.test");
    GoSessionService sessions = mock(GoSessionService.class);
    GoSessionRecord session = new GoSessionRecord();
    session.setAccountId("account-1");
    session.setCsrfToken("csrf-token-value-123456");
    session.setCtxClientId("FREE-1");
    when(sessions.resolve("session-cookie-token")).thenReturn(session);

    EtendoGoJwtServlet purchaseServlet = new EtendoGoJwtServlet(
        mock(TransactionalAuthEmailSender.class),
        mock(EtendoGoSsoProviderRegistry.class), sessions);
    CheckoutRequestStore requestStore = mock(CheckoutRequestStore.class);
    HostedCheckoutService checkout = mock(HostedCheckoutService.class);
    TenantPlanService tenantPlan = mock(TenantPlanService.class);
    purchaseServlet.checkoutRequestStore = requestStore;
    purchaseServlet.hostedCheckoutService = checkout;
    purchaseServlet.tenantPlanService = tenantPlan;
    purchaseServlet.tenantEnvironmentLifecycleService = mock(TenantEnvironmentLifecycleService.class);
    when(tenantPlan.resolvePlan("FREE-1")).thenReturn(TenantPlanService.PLAN_FREE);
    when(tenantPlan.resolvePlan("DEMO-1")).thenReturn(selectedDemoPlan);
    when(purchaseServlet.tenantEnvironmentLifecycleService.isAssociatedWithProductive("DEMO-1"))
        .thenReturn(selectedDemoAssociated);
    when(purchaseServlet.tenantEnvironmentLifecycleService.isProductiveEnvironment("DEMO-1"))
        .thenReturn(selectedDemoProductive);

    HttpServletRequest request = jsonRequest("/billing/purchases",
        "{\"clientName\":\"New Production\",\"demoClientId\":\"DEMO-1\"}");
    when(request.getMethod()).thenReturn("POST");
    when(request.getCookies()).thenReturn(
        new Cookie[] { new Cookie(GoSessionSecurity.COOKIE_NAME, "session-cookie-token") });
    when(request.getHeader(GoSessionSecurity.CSRF_HEADER)).thenReturn("csrf-token-value-123456");
    when(request.getHeader("Origin")).thenReturn("https://app.example.test");
    when(request.getHeader("Referer")).thenReturn(null);
    when(request.getRequestURL()).thenReturn(
        new StringBuffer("https://app.example.test/sws/go/billing/purchases"));
    ResponseCapture response = mockResponse();
    OBDal dalInstance = mock(OBDal.class);
    when(dalInstance.get(Client.class, "DEMO-1")).thenReturn(mock(Client.class));

    try (MockedStatic<OBContext> context = mockStatic(OBContext.class);
        MockedStatic<OBDal> obDal = mockStatic(OBDal.class);
        MockedStatic<EtendoGoJwtDalHelper> dal = mockStatic(EtendoGoJwtDalHelper.class);
        MockedStatic<PublicUrlResolver> urls = mockStatic(PublicUrlResolver.class)) {
      obDal.when(OBDal::getInstance).thenReturn(dalInstance);
      dal.when(() -> EtendoGoJwtDalHelper.findActiveAccountById("account-1")).thenReturn(account);
      dal.when(() -> EtendoGoJwtDalHelper.hasOwnedEnvironmentForAccountEmail("owner@example.test"))
          .thenReturn(true);
      dal.when(() -> EtendoGoJwtDalHelper.clientBelongsToAccountEmail("FREE-1",
          "owner@example.test")).thenReturn(true);
      dal.when(() -> EtendoGoJwtDalHelper.clientOwnedByAccountEmail("FREE-1",
          "owner@example.test")).thenReturn(true);
      dal.when(() -> EtendoGoJwtDalHelper.clientBelongsToAccountEmail("DEMO-1",
          "owner@example.test")).thenReturn(selectedDemoMember);
      dal.when(() -> EtendoGoJwtDalHelper.clientOwnedByAccountEmail("DEMO-1",
          "owner@example.test")).thenReturn(selectedDemoOwned);
      urls.when(PublicUrlResolver::resolveConfiguredAppBaseUrl).thenReturn("https://app.example.test");

      purchaseServlet.doPost(request, response.response);
    }

    assertEquals(400, response.status);
    assertEquals("INVALID_DEMO_SELECTION",
        new JSONObject(response.body()).getJSONObject("error").getString("code"));
    org.mockito.Mockito.verifyNoInteractions(checkout);
  }
}
