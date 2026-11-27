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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockConstruction;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.servlet.http.HttpServletRequest;

import org.apache.logging.log4j.Level;
import org.codehaus.jettison.json.JSONObject;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatchers;
import org.mockito.MockedConstruction;
import org.mockito.MockedStatic;
import org.openbravo.base.exception.OBException;
import org.openbravo.base.exception.OBSecurityException;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.ad.access.Role;
import org.openbravo.model.ad.access.User;
import org.openbravo.model.ad.access.UserRoles;
import org.openbravo.model.ad.system.Client;
import org.openbravo.model.common.currency.Currency;
import org.openbravo.model.common.enterprise.Organization;

import com.etendoerp.go.onboarding.OnboardingForceTestModeService;
import com.etendoerp.go.payment.CheckoutRequestStore;
import com.etendoerp.go.payment.ProvisioningFailureReason;
import com.etendoerp.go.payment.SubscriptionService;
import com.etendoerp.go.payment.TenantEnvironmentLifecycleService;
import com.etendoerp.go.payment.TenantPaywallService;
import com.etendoerp.go.payment.TenantPlanService;
import com.etendoerp.go.schemaforge.data.Account;
import com.etendoerp.go.schemaforge.data.CheckoutRequest;

/**
 * Unit tests for {@link EtendoGoJwtServlet} on paid onboarding: the provisioning alert of a paid
 * run (only after a confirmed rollback, never a false error after commit),
 * {@code fillSubscriptionPeriodBestEffort} (ETP-5047) and {@code clearOnboardingDraftBestEffort}
 * after the onboarding commit, the recorded failure code and the checkout-status polling contract
 * (ETP-5548), and {@code provisionPaidPurchase} (ETP-5048), the browser-less paid onboarding the
 * billing reconciliation drives through {@code PaidProvisioningService}: it runs under the same
 * fenced {@code claimForProvisioning} as the HTTP path, a refused claim runs nothing, the draft's
 * address and fiscal identifier reach the organization (an invalid value is dropped with a WARN),
 * and an ineligible purchase is refused before the claim.
 *
 * @covers com.etendoerp.go.rest.EtendoGoJwtServlet
 */
public class EtendoGoJwtServletPaidOnboardingTest extends EtendoGoJwtServletTestSupport {

  @Test
  public void paidPoolBooleanFailureAlertsErrorOnlyAfterConfirmedRollback() throws Exception {
    assertProvisioningAlertOutcome(true, false, true, false, false);
  }

  @Test
  public void failedRollbackSuppressesAlertAndItsIndependentCommit() throws Exception {
    assertProvisioningAlertOutcome(true, false, false, false, false);
  }

  @Test
  public void alertDeliveryFailureDoesNotChangePaidProvisioningFailure() throws Exception {
    assertProvisioningAlertOutcome(true, false, true, true, false);
  }

  @Test
  public void paidPoolCommitAlertsOkAndStillClosesCheckoutWhenDeliveryThrows() throws Exception {
    assertProvisioningAlertOutcome(true, true, true, true, false);
  }

  @Test
  public void committedProvisioningFollowupFailureNeverEmitsFalseErrorAlert() throws Exception {
    assertProvisioningAlertOutcome(true, true, true, false, true);
  }

  @Test
  public void failedRollbackAfterCommitAlsoSuppressesDirtyDiagnosticCommit() throws Exception {
    assertProvisioningAlertOutcome(true, true, false, false, true);
  }

  @Test
  public void classicBooleanFailureAlsoProducesOneSettledErrorAlert() throws Exception {
    assertProvisioningAlertOutcome(false, false, true, false, false);
  }

  // ===================== fillSubscriptionPeriodBestEffort — the post-commit period read ========
  //
  // A paid onboarding opens the subscription row without a billing period: the purchase's first
  // lifecycle webhooks arrived before the row existed and were ignored for good. So, after the
  // onboarding commit, the servlet asks SubscriptionService to read the period from the provider,
  // in its own transaction, and nothing that step does may fail a tenant that is already
  // committed and paid for.

  private static final String PERIOD_STEP = "subscription billing period";

  @Test
  public void aFilledPeriodIsCommittedInItsOwnTransaction() {
    SubscriptionService subscriptions = mock(SubscriptionService.class);
    when(subscriptions.fillPeriodFromProviderAfterCommit("client-1")).thenReturn(true);
    servlet.subscriptionService = subscriptions;

    try (MockedStatic<EtendoGoDalHelper> transactions = mockStatic(EtendoGoDalHelper.class)) {
      servlet.fillSubscriptionPeriodBestEffort("client-1");

      transactions.verify(() -> EtendoGoDalHelper.commitDalChanges(eq(PERIOD_STEP), any()));
      transactions.verify(() -> EtendoGoDalHelper.rollbackDalChanges(anyString(), any(), any()),
          never());
    }
    verify(subscriptions).fillPeriodFromProviderAfterCommit("client-1");
  }

  @Test
  public void aFillThatWritesNothingCommitsNothing() {
    SubscriptionService subscriptions = mock(SubscriptionService.class);
    when(subscriptions.fillPeriodFromProviderAfterCommit("client-1")).thenReturn(false);
    servlet.subscriptionService = subscriptions;

    try (MockedStatic<EtendoGoDalHelper> transactions = mockStatic(EtendoGoDalHelper.class)) {
      servlet.fillSubscriptionPeriodBestEffort("client-1");

      transactions.verifyNoInteractions();
    }
    verify(subscriptions).fillPeriodFromProviderAfterCommit("client-1");
  }

  @Test
  public void aFailingFillIsRolledBackAndNeverEscapes() {
    SubscriptionService subscriptions = mock(SubscriptionService.class);
    IllegalStateException failure = new IllegalStateException("no session");
    when(subscriptions.fillPeriodFromProviderAfterCommit("client-1")).thenThrow(failure);
    servlet.subscriptionService = subscriptions;

    try (MockedStatic<EtendoGoDalHelper> transactions = mockStatic(EtendoGoDalHelper.class)) {
      // The contract under test: nothing escapes. JUnit 4 fails the spec on any throw here.
      servlet.fillSubscriptionPeriodBestEffort("client-1");

      transactions.verify(() -> EtendoGoDalHelper.commitDalChanges(anyString(), any()), never());
      transactions.verify(() -> EtendoGoDalHelper.rollbackDalChanges(eq(PERIOD_STEP),
          eq(failure), any()));
    }
  }

  @Test
  public void aFailingPeriodCommitIsRolledBackAndNeverEscapes() {
    SubscriptionService subscriptions = mock(SubscriptionService.class);
    when(subscriptions.fillPeriodFromProviderAfterCommit("client-1")).thenReturn(true);
    servlet.subscriptionService = subscriptions;
    IllegalStateException failure = new IllegalStateException("commit refused");

    try (MockedStatic<EtendoGoDalHelper> transactions = mockStatic(EtendoGoDalHelper.class)) {
      transactions.when(() -> EtendoGoDalHelper.commitDalChanges(eq(PERIOD_STEP), any()))
          .thenThrow(failure);

      // The contract under test: nothing escapes. JUnit 4 fails the spec on any throw here.
      servlet.fillSubscriptionPeriodBestEffort("client-1");

      transactions.verify(() -> EtendoGoDalHelper.rollbackDalChanges(eq(PERIOD_STEP),
          eq(failure), any()));
    }
  }

  /**
   * The step runs after the checkout is closed and before the costing schedule is activated:
   * after the onboarding commit, so the row it reads exists; after {@code recordProvisioned}, so a
   * slow provider call never widens the window in which the checkout is not {@code PROVISIONED}
   * and names no created client — a crash there lets a retry provision a second tenant for the
   * same payment.
   */
  @Test
  public void aPaidOnboardingFillsThePeriodAfterClosingTheCheckout() throws Exception {
    SubscriptionService subscriptions = mock(SubscriptionService.class);
    List<String> steps = new ArrayList<>();
    when(subscriptions.fillPeriodFromProviderAfterCommit("client-1")).thenAnswer(invocation -> {
      steps.add("fill");
      return true;
    });

    String body = runCommittedPaidOnboarding(true, subscriptions, steps);

    assertEquals(List.of("commit:onboarding", "recordProvisioned", "fill",
        "commit:" + PERIOD_STEP, "activateSchedule"), steps);
    verify(subscriptions).fillPeriodFromProviderAfterCommit("client-1");
    assertTrue(body, body.contains("\"success\":true"));
  }

  /** The tenant is committed and paid for: a failing period read costs the period, nothing else. */
  @Test
  public void aPaidOnboardingStillSucceedsWhenThePeriodFillThrows() throws Exception {
    SubscriptionService subscriptions = mock(SubscriptionService.class);
    List<String> steps = new ArrayList<>();
    when(subscriptions.fillPeriodFromProviderAfterCommit("client-1")).thenAnswer(invocation -> {
      steps.add("fill");
      throw new IllegalStateException("provider read exploded");
    });

    String body = runCommittedPaidOnboarding(true, subscriptions, steps);

    assertEquals(List.of("commit:onboarding", "recordProvisioned", "fill",
        "rollback:" + PERIOD_STEP, "activateSchedule"), steps);
    assertTrue(body, body.contains("\"success\":true"));
    assertEquals("The client keeps exactly one result line", 1, countResultLines(body));
  }

  // ===================== clearOnboardingDraftBestEffort — the account is a client-0 row ======

  private static final String DRAFT_CLEAR_STEP = "onboarding draft clear";

  /**
   * The draft lives on {@code ETGO_ACCOUNT}, a client-0 row, and the clear runs after the
   * onboarding commit with the thread still in the new tenant's context. The DAL write check
   * refuses a client-0 row from there at flush, so the clear must run as system — and hand the
   * tenant's context back afterwards.
   */
  @Test
  public void theDraftClearWritesTheClientZeroAccountAsSystem() throws Exception {
    Account account = mock(Account.class);
    OBContext tenant = mock(OBContext.class);
    List<String> steps = new ArrayList<>();
    try (var context = mockStatic(OBContext.class);
         var dal = mockStatic(EtendoGoJwtDalHelper.class);
         var transactions = mockStatic(EtendoGoDalHelper.class)) {
      context.when(OBContext::getOBContext).thenReturn(tenant);
      context.when(() -> OBContext.setOBContext("0", "0", "0", "0"))
          .thenAnswer(invocation -> steps.add("system"));
      context.when(() -> OBContext.setOBContext(tenant))
          .thenAnswer(invocation -> steps.add("tenant"));
      dal.when(() -> EtendoGoJwtDalHelper.updateOnboardingDraft(account, null))
          .thenAnswer(invocation -> steps.add("clear"));

      invokeClearOnboardingDraft(account);

      assertEquals(List.of("system", "clear", "tenant"), steps);
      transactions.verifyNoInteractions();
    }
  }

  /**
   * A refused clear must not leave the dirty account in the session: the next commit on the
   * thread — the period fill, the ready email — would flush it again, in whatever context it runs.
   */
  @Test
  public void aFailedDraftClearRollsBackItsOwnWork() throws Exception {
    Account account = mock(Account.class);
    OBSecurityException refused = new OBSecurityException("Client (0) is not present");
    try (var context = mockStatic(OBContext.class);
         var dal = mockStatic(EtendoGoJwtDalHelper.class);
         var transactions = mockStatic(EtendoGoDalHelper.class)) {
      dal.when(() -> EtendoGoJwtDalHelper.updateOnboardingDraft(account, null)).thenThrow(refused);

      // The contract under test: nothing escapes. JUnit 4 fails the spec on any throw here.
      invokeClearOnboardingDraft(account);

      transactions.verify(() -> EtendoGoDalHelper.rollbackDalChanges(eq(DRAFT_CLEAR_STEP),
          eq(refused), any()));
    }
  }

  private void invokeClearOnboardingDraft(Account account) throws Exception {
    Method clear = EtendoGoJwtServlet.class.getDeclaredMethod("clearOnboardingDraftBestEffort",
        Account.class);
    clear.setAccessible(true);
    clear.invoke(servlet, account);
  }

  /**
   * The classic path (no pooled tenant: here a half-built client this purchase left behind, which
   * the attempt resumes) commits the tenant the same way, so it must fill the period at the same
   * point: after the checkout is closed, before the costing schedule.
   */
  @Test
  public void aClassicPaidOnboardingAlsoFillsThePeriodAfterClosingTheCheckout()
      throws Exception {
    SubscriptionService subscriptions = mock(SubscriptionService.class);
    List<String> steps = new ArrayList<>();
    when(subscriptions.fillPeriodFromProviderAfterCommit("client-1")).thenAnswer(invocation -> {
      steps.add("fill");
      return true;
    });

    String body = runCommittedPaidOnboarding(false, subscriptions, steps);

    assertEquals(List.of("dataset", "commit:onboarding", "recordProvisioned", "fill",
        "commit:" + PERIOD_STEP, "activateSchedule"), steps);
    verify(servlet.pooledTenantClaimService, never()).claim(any(), any(), anyString());
    verify(subscriptions).fillPeriodFromProviderAfterCommit("client-1");
    assertTrue(body, body.contains("\"success\":true"));
  }

  /**
   * Drives a paid onboarding to a successful commit, recording, in order, every DAL commit and
   * rollback by operation name, the closing of the checkout request and the costing schedule.
   *
   * <p>Pooled: the pool hands out {@code client-1}. Classic: {@code client-1} is the half-built
   * client this purchase left under its provisioning name, resumed with its organization in
   * place; the provisioning chain's dataset pass is stubbed (recorded as {@code dataset}) and the
   * requested-name step finds no client to rename.
   *
   * @param pooled which path the tenant takes
   * @param subscriptions the subscription service the servlet uses
   * @param steps where the steps are recorded; the caller's own stubs may add to it
   * @return the NDJSON body written to the client
   */
  private String runCommittedPaidOnboarding(boolean pooled, SubscriptionService subscriptions,
      List<String> steps) throws Exception {
    if (!pooled) {
      try (MockedStatic<OBDal> obDal = mockStatic(OBDal.class);
           MockedConstruction<OnboardingProvisioningChain> chains = mockConstruction(
               OnboardingProvisioningChain.class, withSettings().defaultAnswer(CALLS_REAL_METHODS),
               (chain, context) -> doAnswer(invocation -> steps.add("dataset")).when(chain)
                   .ensureOnboardingDataset(any(), anyString(), anyString(), anyString(),
                       anyString(), any()))) {
        obDal.when(OBDal::getInstance).thenReturn(mock(OBDal.class));
        return runCommittedPaidOnboardingOnPath(false, subscriptions, steps);
      }
    }
    return runCommittedPaidOnboardingOnPath(true, subscriptions, steps);
  }

  private String runCommittedPaidOnboardingOnPath(boolean pooled,
      SubscriptionService subscriptions, List<String> steps) throws Exception {
    PaidOnboardingStage stage = stagePaidOnboarding(pooled);
    doAnswer(invocation -> steps.add("recordProvisioned")).when(stage.store)
        .recordProvisioned("purchase-1", "client-1", 1L);
    servlet.subscriptionService = subscriptions;
    when(servlet.tenantPlanService.markProductive("client-1", "star-org")).thenReturn(true);
    when(servlet.tenantEnvironmentLifecycleService.markProductive("client-1")).thenReturn(true);
    servlet.onboardingWarehouseAddressService =
        mock(com.etendoerp.go.onboarding.OnboardingWarehouseAddressService.class);
    servlet.onboardingCostingScheduleService =
        mock(com.etendoerp.go.onboarding.OnboardingCostingScheduleService.class);
    servlet.onboardingInitialPeriodStateService =
        mock(com.etendoerp.go.onboarding.OnboardingInitialPeriodStateService.class);
    // ETP-6148: the remittance setup reads a flag a developer may have on locally; keep it inert.
    servlet.onboardingRemittanceSetupService =
        mock(com.etendoerp.go.onboarding.OnboardingRemittanceSetupService.class);
    doAnswer(invocation -> steps.add("activateSchedule"))
        .when(servlet.onboardingCostingScheduleService).activateSchedule("client-1");
    runStagedPaidOnboarding(stage, (support, dal, transactions) -> {
      dal.when(() -> EtendoGoJwtDalHelper.clientBelongsToAccountEmail("client-1", "user@test.com"))
          .thenReturn(true);
      if (!pooled) {
        support.when(() -> EtendoGoJwtSupport.findClientIdByName(PAID_PROVISIONING_NAME))
            .thenReturn("client-1");
        support.when(() -> EtendoGoJwtSupport.organizationExists("client-1")).thenReturn(true);
      }
      transactions.when(() -> EtendoGoDalHelper.commitDalChanges(anyString(), any()))
          .thenAnswer(invocation -> steps.add("commit:" + invocation.getArgument(0)));
      transactions.when(() -> EtendoGoDalHelper.rollbackDalChanges(anyString(), any(), any()))
          .thenAnswer(invocation -> steps.add("rollback:" + invocation.getArgument(0)));
    }, (support, dal, transactions) -> { });
    return stage.response.body();
  }

  /**
   * ETP-5548: the productive marker is the last write before the commit. A step that fails after
   * the tenant exists (here the pooled org-info wiring) must leave no plan, lifecycle or override
   * write behind, and the client must receive exactly one result line: the step's own.
   */
  @Test
  public void paidStepFailureNeverWritesProductiveMetadataAndKeepsItsOwnResult() throws Exception {
    PaidOnboardingRun run = runPaidOnboarding(true, false);

    verify(servlet.tenantPlanService, never()).markProductive(anyString(), anyString());
    verify(servlet.tenantEnvironmentLifecycleService, never()).markProductive(anyString());
    verify(servlet.onboardingForceTestModeService, never())
        .revertTestModeForProductiveTenant(anyString());
    assertEquals("The client keeps the last result line, so there must be only one", 1,
        countResultLines(run.response.body()));
    assertTrue(run.response.body().contains("Org info unavailable"));
    verify(run.store).recordFailureReason("purchase-1",
        ProvisioningFailureReason.CODE_PROVISIONING_FAILED + ": Org info unavailable");
  }

  /**
   * ETP-5548: a company name the account already uses for a productive environment reaches the
   * client once, coded, and is recorded as such. The same name on another account's environment
   * is not a collision.
   */
  @Test
  public void paidNameCollisionIsRecordedWithItsNonRetryableCode() throws Exception {
    PaidOnboardingRun run = runPaidOnboarding(false, true);

    verify(servlet.pooledTenantClaimService, never()).claim(any(), any(), anyString());

    assertEquals(1, countResultLines(run.response.body()));
    assertTrue(run.response.body().contains(
        "\"code\":\"" + ProvisioningFailureReason.CODE_CLIENT_NAME_IN_USE + "\""));
    verify(run.store).recordFailureReason(eq("purchase-1"),
        org.mockito.ArgumentMatchers.startsWith(
            ProvisioningFailureReason.CODE_CLIENT_NAME_IN_USE + ": "));
    verify(servlet.tenantPlanService, never()).markProductive(anyString(), anyString());
  }

  /** ETP-5548: the polling contract exposes a code and a fixed text, never the stored cause. */
  @Test
  public void checkoutStatusNeverReturnsTheRawFailureCause() throws Exception {
    ResponseCapture response = mockResponse();
    HttpServletRequest request = mockRequest("/checkout/sessions/purchase-1");
    when(request.getHeader("Authorization")).thenReturn("Bearer valid-token");
    CheckoutRequestStore store = mock(CheckoutRequestStore.class);
    servlet.checkoutRequestStore = store;
    CheckoutRequest purchase = mock(CheckoutRequest.class);
    when(purchase.getCheckoutRequestStatus()).thenReturn("PROVISIONING");
    when(purchase.getClientName()).thenReturn("Acme");
    when(purchase.getFailureReason()).thenReturn(
        "CLIENT_NAME_IN_USE: ERROR duplicate key ad_client_name client 5775CB18");
    when(purchase.getUpdated()).thenReturn(new Date(1_790_000_000_000L));
    when(store.find("purchase-1", "account-1", "user@test.com")).thenReturn(purchase);
    when(store.deriveProvisioningStatus(any())).thenCallRealMethod();
    when(store.isProvisioningRetryAllowed(any())).thenCallRealMethod();

    try (var context = mockStatic(OBContext.class);
         var dal = mockStatic(EtendoGoJwtDalHelper.class)) {
      Account account = stubAuthenticatedAccount(dal);
      when(account.getId()).thenReturn("account-1");
      servlet.doGet(request, response.response);
    }

    JSONObject body = new JSONObject(response.body());
    assertEquals("provisioning_failed", body.getString("status"));
    assertEquals(ProvisioningFailureReason.CODE_CLIENT_NAME_IN_USE,
        body.getString("failureCode"));
    assertFalse("A deterministic failure is not offered for retry",
        body.getBoolean("retryAllowed"));
    assertFalse(body.getString("failureReason").contains("duplicate key"));
    assertFalse(body.getString("failureReason").contains("5775CB18"));
    assertEquals(new Date(1_790_000_000_000L).toInstant().toString(),
        body.getString("updatedAt"));
  }

  private static int countResultLines(String body) {
    int count = 0;
    for (String line : body.split("\n")) {
      if (line.contains("\"type\":\"result\"")) count++;
    }
    return count;
  }

  private static final class PaidOnboardingRun {
    final ResponseCapture response;
    final CheckoutRequestStore store;

    PaidOnboardingRun(ResponseCapture response, CheckoutRequestStore store) {
      this.response = response;
      this.store = store;
    }
  }

  /**
   * Drives a paid onboarding that fails gracefully: either the pooled org-info step throws after
   * the tenant was claimed, or the account already has a productive environment with the company
   * name (ETP-5548).
   */
  private PaidOnboardingRun runPaidOnboarding(boolean pooled, boolean productiveNameTaken)
      throws Exception {
    PaidOnboardingStage stage = stagePaidOnboarding(pooled);
    doThrow(new OBException("Org info unavailable")).when(servlet.onboardingOrgInfoService)
        .ensureOrgInfo(anyString(), anyString(), anyString(), anyString(), any());
    when(servlet.tenantPlanService.resolvePlan("own-productive"))
        .thenReturn(TenantPlanService.PLAN_PRODUCTIVE);
    runStagedPaidOnboarding(stage, (support, dal, transactions) -> {
      support.when(() -> EtendoGoJwtSupport.findClientIdsByName("Acme"))
          .thenReturn(productiveNameTaken ? List.of("other-account-acme", "own-productive")
              : List.of());
      dal.when(() -> EtendoGoJwtDalHelper.clientBelongsToAccountEmail(
          "own-productive", "user@test.com")).thenReturn(true);
      transactions.when(() -> EtendoGoDalHelper.rollbackDalChangesAndConfirm(eq("onboarding"), any(), any()))
          .thenReturn(true);
    }, (support, dal, transactions) ->
        transactions.verify(() -> EtendoGoDalHelper.commitDalChanges(eq("onboarding"), any()), never()));
    return new PaidOnboardingRun(stage.response, stage.store);
  }

  /** What one staged paid onboarding sends and the checkout store it closes. */
  private static final class PaidOnboardingStage {
    final ResponseCapture response;
    final HttpServletRequest request;
    final CheckoutRequestStore store;

    PaidOnboardingStage(ResponseCapture response, HttpServletRequest request,
        CheckoutRequestStore store) {
      this.response = response;
      this.request = request;
      this.store = store;
    }
  }

  /** What a staged run executes instead of {@code doPost}, when set (ETP-5048). */
  private StagedAction stagedAction;

  /** A staged run's body other than the HTTP POST. */
  @FunctionalInterface
  private interface StagedAction {
    void run() throws Exception;
  }

  /** Records what a browser-less paid onboarding reports to its sink. */
  private static final class RecordingSink implements com.etendoerp.go.onboarding.OnboardingProgressSink {
    final List<String> lines = new ArrayList<>();

    @Override
    public void progress(String step, String status, String message) {
      lines.add("progress:" + step + ":" + status);
    }

    @Override
    public void result(boolean success, String message, String code) {
      lines.add("result:" + success + ":" + code);
    }
  }

  private static PaidProvisioningService.PaidInput paidInput(String currencyIso) {
    return new PaidProvisioningService.PaidInput("purchase-1", "account-1", "user@test.com",
        "User", "Acme", null, "en_US", currencyIso, "ES", null, null);
  }

  private static PaidProvisioningService.PaidInput paidInput(String countryCode, String address,
      String taxId) {
    return new PaidProvisioningService.PaidInput("purchase-1", "account-1", "user@test.com",
        "User", "Acme", null, "en_US", "EUR", countryCode, address, taxId);
  }

  /**
   * Runs a committed browser-less paid onboarding with these inputs and returns the address and
   * the fiscal identifier the organization step received, plus the outcome and the servlet WARNs.
   */
  private String[] provisionWithCompanyData(PaidProvisioningService.PaidInput input,
      List<String> warnings) throws Exception {
    PaidProvisioningService.Outcome[] outcome = new PaidProvisioningService.Outcome[1];
    stagedAction = () -> outcome[0] = servlet.provisionPaidPurchase(input, new RecordingSink());
    com.etendoerp.go.payment.TestLogCapture log = com.etendoerp.go.payment.TestLogCapture
        .attachTo(EtendoGoJwtServlet.class, org.apache.logging.log4j.Level.WARN);
    try {
      runCommittedPaidOnboarding(true, mock(SubscriptionService.class), new ArrayList<>());
      warnings.addAll(log.messagesAt(org.apache.logging.log4j.Level.WARN));
    } finally {
      log.detach();
    }
    assertEquals("Company data never fails the provisioning",
        PaidProvisioningService.Status.PROVISIONED, outcome[0].status());
    ArgumentCaptor<com.etendoerp.go.onboarding.OrgInfoInput> orgInfo =
        ArgumentCaptor.forClass(com.etendoerp.go.onboarding.OrgInfoInput.class);
    verify(servlet.onboardingOrgInfoService).ensureOrgInfo(anyString(), anyString(), anyString(),
        anyString(), orgInfo.capture());
    assertEquals(input.countryCode(), orgInfo.getValue().countryCode());
    return new String[] { orgInfo.getValue().address(), orgInfo.getValue().taxId() };
  }

  /** ETP-5048 S4 — the draft's address and fiscal identifier reach the organization. */
  @Test
  public void aPaidPurchaseCarriesTheDraftAddressAndTaxIdToTheOrganization() throws Exception {
    List<String> warnings = new ArrayList<>();

    String[] orgInfo = provisionWithCompanyData(paidInput("ES", " Calle Mayor 1 ", "12345678Z"),
        warnings);

    assertEquals("Calle Mayor 1", orgInfo[0]);
    assertEquals("12345678Z", orgInfo[1]);
    assertTrue(warnings.toString(), warnings.stream().noneMatch(w -> w.contains("left out")));
  }

  /** ETP-5048 S4 — an address over the HTTP limit is dropped with a WARN, never truncated. */
  @Test
  public void anOverLongDraftAddressIsDroppedWithAWarning() throws Exception {
    List<String> warnings = new ArrayList<>();
    String tooLong = org.apache.commons.lang3.StringUtils.repeat('a',
        OnboardingFieldLimits.ADDRESS + 1);

    String[] orgInfo = provisionWithCompanyData(paidInput("ES", tooLong, "12345678Z"), warnings);

    assertEquals("", orgInfo[0]);
    assertEquals("The tax id is unaffected", "12345678Z", orgInfo[1]);
    assertTrue(warnings.toString(), warnings.contains("Server-side provisioning of purchase-1: "
        + "the draft address exceeds " + OnboardingFieldLimits.ADDRESS + " characters and is "
        + "left out"));
  }

  /** ETP-5048 S4 — an invalid Spanish tax id is dropped with a WARN, never failing the run. */
  @Test
  public void anInvalidSpanishDraftTaxIdIsDroppedWithAWarning() throws Exception {
    List<String> badCheckDigit = new ArrayList<>();
    String[] orgInfo = provisionWithCompanyData(paidInput("ES", "Calle Mayor 1", "12345678A"),
        badCheckDigit);

    assertEquals("Calle Mayor 1", orgInfo[0]);
    assertEquals("", orgInfo[1]);
    assertTrue(badCheckDigit.toString(), badCheckDigit.contains("Server-side provisioning of "
        + "purchase-1: the draft fiscal identifier is not valid (BAD_CHECK_DIGIT) and is left out"));
  }

  @Test
  public void aMalformedSpanishDraftTaxIdIsDroppedToo() throws Exception {
    List<String> warnings = new ArrayList<>();

    String[] orgInfo = provisionWithCompanyData(paidInput("ES", "", "not-a-nif"), warnings);

    assertEquals("", orgInfo[1]);
    assertTrue(warnings.toString(), warnings.stream()
        .anyMatch(w -> w.contains("is not valid (BAD_FORMAT) and is left out")));
  }

  /** Only a Spanish identifier is checked: another country's is passed through as given. */
  @Test
  public void aForeignDraftTaxIdIsPassedThroughUnchecked() throws Exception {
    List<String> warnings = new ArrayList<>();

    String[] orgInfo = provisionWithCompanyData(paidInput("FR", null, "FR12345678901"), warnings);

    assertEquals("A missing address is blank, not null", "", orgInfo[0]);
    assertEquals("FR12345678901", orgInfo[1]);
  }

  /** ETP-5048 — a currency the instance does not know needs review; nothing is claimed. */
  @Test
  public void aPaidPurchaseInAnUnknownCurrencyNeedsReviewAndIsNeverClaimed() throws Exception {
    PaidOnboardingStage stage = stagePaidOnboarding(true);
    PaidProvisioningService.Outcome[] outcome = new PaidProvisioningService.Outcome[1];
    stagedAction = () -> outcome[0] = servlet.provisionPaidPurchase(paidInput("XXX"),
        new RecordingSink());

    runStagedPaidOnboarding(stage, (support, dal, transactions) -> { },
        (support, dal, transactions) -> { });

    assertEquals(PaidProvisioningService.Status.NEEDS_REVIEW, outcome[0].status());
    assertEquals("UNKNOWN_CURRENCY", outcome[0].code());
    verify(stage.store, never()).claimForProvisioning(anyString(), anyString(), anyString());
  }

  /**
   * ETP-5048 — the reconciliation provisions under the same fence as the browser: when another
   * attempt holds the purchase the claim is refused and no tenant is touched.
   */
  @Test
  public void aPaidPurchaseWhoseClaimIsRefusedRunsNothing() throws Exception {
    PaidOnboardingStage stage = stagePaidOnboarding(true);
    when(stage.store.claimForProvisioning("purchase-1", "account-1", "user@test.com"))
        .thenReturn(false);
    RecordingSink sink = new RecordingSink();
    PaidProvisioningService.Outcome[] outcome = new PaidProvisioningService.Outcome[1];
    stagedAction = () -> outcome[0] = servlet.provisionPaidPurchase(paidInput("EUR"), sink);

    runStagedPaidOnboarding(stage, (support, dal, transactions) -> { },
        (support, dal, transactions) -> { });

    assertEquals(PaidProvisioningService.Status.NOT_CLAIMED, outcome[0].status());
    assertNull(outcome[0].attempt());
    verify(stage.store).claimForProvisioning("purchase-1", "account-1", "user@test.com");
    verify(servlet.pooledTenantClaimService, never()).claim(any(), any(), anyString());
    assertTrue(sink.lines.toString(), sink.lines.isEmpty());
  }

  /**
   * ETP-5048 — a claimed purchase runs the shared onboarding to its commit; the outcome carries
   * the claimed attempt and the sink receives the run's progress and its successful result.
   */
  @Test
  public void aClaimedPaidPurchaseIsProvisionedAndReportsToTheSink() throws Exception {
    RecordingSink sink = new RecordingSink();
    PaidProvisioningService.Outcome[] outcome = new PaidProvisioningService.Outcome[1];
    stagedAction = () -> outcome[0] = servlet.provisionPaidPurchase(paidInput("EUR"), sink);
    List<String> steps = new ArrayList<>();

    runCommittedPaidOnboarding(true, mock(SubscriptionService.class), steps);

    assertEquals(PaidProvisioningService.Status.PROVISIONED, outcome[0].status());
    assertEquals(Long.valueOf(1L), outcome[0].attempt());
    assertTrue(steps.toString(), steps.contains("recordProvisioned"));
    assertTrue(sink.lines.toString(), sink.lines.stream().anyMatch(l -> l.startsWith("progress:")));
    assertEquals("result:true:null", sink.lines.get(sink.lines.size() - 1));
  }

  /** Runs a browser-less paid onboarding that is refused before its claim, and returns why. */
  private PaidProvisioningService.Outcome refusedPaidPurchase(PaidOnboardingStage stage,
      StagedStatics before) throws Exception {
    PaidProvisioningService.Outcome[] outcome = new PaidProvisioningService.Outcome[1];
    stagedAction = () -> outcome[0] = servlet.provisionPaidPurchase(paidInput("EUR"),
        new RecordingSink());
    runStagedPaidOnboarding(stage, before, (support, dal, transactions) -> { });
    verify(stage.store, never()).claimForProvisioning(anyString(), anyString(), anyString());
    verify(servlet.pooledTenantClaimService, never()).claim(any(), any(), anyString());
    return outcome[0];
  }

  private static void confirmPayments(TenantPaywallService paywall, boolean confirmed)
      throws Exception {
    Field confirmation = TenantPaywallService.class.getDeclaredField("paymentConfirmation");
    confirmation.setAccessible(true);
    confirmation.set(paywall,
        (TenantPaywallService.PaymentConfirmation) (token, email, name) -> confirmed);
  }

  /** ETP-5048 G1 — an account owning environments must be a marked owner to be provisioned. */
  @Test
  public void aPaidPurchaseOfAnAccountThatIsNotTheOwnerNeedsReviewAndIsNeverClaimed()
      throws Exception {
    PaidOnboardingStage stage = stagePaidOnboarding(true);

    PaidProvisioningService.Outcome outcome = refusedPaidPurchase(stage,
        (support, dal, transactions) -> dal.when(
            () -> EtendoGoJwtDalHelper.hasOwnedEnvironmentForAccountEmail("user@test.com"))
            .thenReturn(false));

    assertEquals(PaidProvisioningService.Status.NEEDS_REVIEW, outcome.status());
    assertEquals("BILLING_OWNER_REQUIRED", outcome.code());
    assertNull(outcome.attempt());
  }

  /** ETP-5048 G1 — a paywall that blocks the purchase (no confirmed payment) is NOT_PAID. */
  @Test
  public void aPaidPurchaseThePaywallBlocksIsNotPaidAndIsNeverClaimed() throws Exception {
    PaidOnboardingStage stage = stagePaidOnboarding(true);
    confirmPayments(servlet.tenantPaywallService, false);

    PaidProvisioningService.Outcome outcome = refusedPaidPurchase(stage,
        (support, dal, transactions) -> { });

    assertEquals(PaidProvisioningService.Status.NOT_PAID, outcome.status());
    assertNull(outcome.code());
  }

  /**
   * ETP-5048 G1 — a paywall that allows the purchase only as a free one (the account owns no
   * environment, and no payment is confirmed) is not productive, so it is NOT_PAID too.
   */
  @Test
  public void aPaidPurchaseThePaywallDoesNotConfirmAsProductiveIsNotPaid() throws Exception {
    PaidOnboardingStage stage = stagePaidOnboarding(true);
    confirmPayments(servlet.tenantPaywallService, false);

    PaidProvisioningService.Outcome outcome = refusedPaidPurchase(stage,
        (support, dal, transactions) -> dal.when(
            () -> EtendoGoJwtDalHelper.countTenantsOwnedByAccountEmail("user@test.com"))
            .thenReturn(0));

    assertEquals(PaidProvisioningService.Status.NOT_PAID, outcome.status());
  }

  /** ETP-5048 G1 — a recorded demo source that is no longer a free demo of the account. */
  @Test
  public void aPaidPurchaseWhoseRecordedDemoIsGoneNeedsReviewAndIsNeverClaimed()
      throws Exception {
    PaidOnboardingStage stage = stagePaidOnboarding(true);
    when(stage.store.hasRecordedDemoSelection("purchase-1", "account-1", "user@test.com"))
        .thenReturn(true);
    when(stage.store.findDemoClientId("purchase-1", "account-1", "user@test.com"))
        .thenReturn("demo-1");

    PaidProvisioningService.Outcome outcome = refusedPaidPurchase(stage,
        (support, dal, transactions) -> dal.when(
            () -> EtendoGoJwtDalHelper.findFreeTenantIdsByAccountEmail("user@test.com"))
            .thenReturn(java.util.Set.of("demo-other")));

    assertEquals(PaidProvisioningService.Status.NEEDS_REVIEW, outcome.status());
    assertEquals(EtendoGoJwtServlet.CODE_DEMO_SELECTION_REQUIRED, outcome.code());
    assertTrue(outcome.detail(), outcome.detail().contains("no longer available"));
  }

  /**
   * ETP-5048 G4 (decision 10) — a browser-less repair runs the same post-commit steps as the
   * browser: the internal {@code environment-provisioning} alert and the customer's
   * environment-ready email both go out.
   */
  @Test
  public void aServerSideRepairSendsTheProvisioningAlertAndTheEnvironmentReadyEmail()
      throws Exception {
    PaidProvisioningService.Outcome[] outcome = new PaidProvisioningService.Outcome[1];
    stagedAction = () -> outcome[0] = servlet.provisionPaidPurchase(paidInput("EUR"),
        new RecordingSink());

    runCommittedPaidOnboarding(true, mock(SubscriptionService.class), new ArrayList<>());

    assertEquals(PaidProvisioningService.Status.PROVISIONED, outcome[0].status());
    ArgumentCaptor<com.etendoerp.go.schemaforge.email.InternalAlertEvent> alerts =
        ArgumentCaptor.forClass(com.etendoerp.go.schemaforge.email.InternalAlertEvent.class);
    verify(servlet.internalAlertService, org.mockito.Mockito.atLeastOnce())
        .sendAfterTransaction(alerts.capture());
    assertTrue(alerts.getAllValues().stream().anyMatch(alert ->
        "environment-provisioning".equals(alert.getEvent())
            && alert.getStatus() == com.etendoerp.go.schemaforge.email.InternalAlertEvent.Status.OK
            && "PRODUCTIVE".equals(alert.getEnvironmentType())
            && "client-1".equals(alert.getClientId())));
    Field sender = EtendoGoJwtServlet.class.getDeclaredField("authEmailSender");
    sender.setAccessible(true);
    TransactionalAuthEmailSender emails = (TransactionalAuthEmailSender) sender.get(servlet);
    verify(emails).sendEnvironmentReady(any(), eq("client-1"), eq("en_US"));
  }

  /** ETP-5048 — a failed run is classified FAILED with the step's own code and the attempt. */
  @Test
  public void aFailedPaidPurchaseRunIsReportedAsFailedWithItsAttempt() throws Exception {
    RecordingSink sink = new RecordingSink();
    PaidProvisioningService.Outcome[] outcome = new PaidProvisioningService.Outcome[1];
    stagedAction = () -> outcome[0] = servlet.provisionPaidPurchase(paidInput("EUR"), sink);

    runPaidOnboarding(true, false);

    assertEquals(PaidProvisioningService.Status.FAILED, outcome[0].status());
    assertEquals(Long.valueOf(1L), outcome[0].attempt());
    assertNotNull(outcome[0].code());
    assertTrue(sink.lines.toString(), sink.lines.get(sink.lines.size() - 1)
        .startsWith("result:false:"));
  }

  /** Per-spec stubs or verifications on the static mocks of a staged paid onboarding. */
  @FunctionalInterface
  private interface StagedStatics {
    void apply(MockedStatic<EtendoGoJwtSupport> support, MockedStatic<EtendoGoJwtDalHelper> dal,
        MockedStatic<EtendoGoDalHelper> transactions);
  }

  /**
   * The setup every paid onboarding spec shares: a confirmed purchase {@code purchase-1} the
   * account {@code account-1} has claimed, the tenant {@code client-1} (pooled or classic), and
   * mocked plan, lifecycle, force-test-mode and org-info services. Spec-specific stubs go on the
   * returned stage or on the servlet's mocks before {@link #runStagedPaidOnboarding}.
   *
   * @param pooled whether the pool hands out {@code client-1}
   */
  private PaidOnboardingStage stagePaidOnboarding(boolean pooled) throws Exception {
    ResponseCapture response = mockResponse();
    HttpServletRequest request = jsonRequest("/onboarding",
        "{\"clientName\":\"Acme\",\"currency\":\"EUR\",\"language\":\"en_US\","
            + "\"paymentToken\":\"purchase-1\"}");
    when(request.getHeader("Authorization")).thenReturn("Bearer valid-token");
    CheckoutRequestStore store = mock(CheckoutRequestStore.class);
    servlet.checkoutRequestStore = store;
    when(store.claimForProvisioning("purchase-1", "account-1", "user@test.com")).thenReturn(true);
    when(store.findProvisioningAttempt("purchase-1", "account-1", "user@test.com")).thenReturn(1L);
    TenantPaywallService paywall = new TenantPaywallService();
    Field confirmation = TenantPaywallService.class.getDeclaredField("paymentConfirmation");
    confirmation.setAccessible(true);
    confirmation.set(paywall, (TenantPaywallService.PaymentConfirmation) (token, email, name) -> true);
    servlet.tenantPaywallService = paywall;
    servlet.pooledTenantClaimService = mock(PooledTenantClaimService.class);
    when(servlet.pooledTenantClaimService.claim(any(), any(), anyString()))
        .thenReturn(pooled ? "client-1" : null);
    servlet.devProvisioningFailureFixtureService = mock(DevProvisioningFailureFixtureService.class);
    servlet.tenantPlanService = mock(TenantPlanService.class);
    servlet.tenantEnvironmentLifecycleService = mock(TenantEnvironmentLifecycleService.class);
    servlet.onboardingForceTestModeService = mock(OnboardingForceTestModeService.class);
    servlet.onboardingOrgInfoService = mock(com.etendoerp.go.onboarding.OnboardingOrgInfoService.class);
    // ETP-5577 (develop) — finishTenantMetadata marks the initial period state pending, which
    // writes an AD_Preference; a mock keeps the staged run DB-free.
    servlet.onboardingInitialPeriodStateService =
        mock(com.etendoerp.go.onboarding.OnboardingInitialPeriodStateService.class);
    // ETP-6148: the remittance setup reads a flag a developer may have on locally; keep it inert.
    servlet.onboardingRemittanceSetupService =
        mock(com.etendoerp.go.onboarding.OnboardingRemittanceSetupService.class);
    return new PaidOnboardingStage(response, request, store);
  }

  /**
   * Posts a staged paid onboarding with the authenticated account, its currency and the
   * {@code client-1} admin role and organizations resolved, inside the static mocks.
   *
   * @param before spec stubs applied before the request
   * @param after spec verifications run before the static mocks close
   */
  private void runStagedPaidOnboarding(PaidOnboardingStage stage, StagedStatics before,
      StagedStatics after) throws Exception {
    Currency currency = mock(Currency.class); when(currency.getId()).thenReturn("currency-1");
    UserRoles admin = mock(UserRoles.class); Role role = mock(Role.class); User user = mock(User.class);
    when(role.getId()).thenReturn("role-1"); when(user.getId()).thenReturn("user-1");
    when(admin.getRole()).thenReturn(role); when(admin.getUserContact()).thenReturn(user);
    Organization org = mock(Organization.class); when(org.getId()).thenReturn("org-1");
    try (var context = mockStatic(OBContext.class);
         var support = mockStatic(EtendoGoJwtSupport.class);
         var dal = mockStatic(EtendoGoJwtDalHelper.class);
         var transactions = mockStatic(EtendoGoDalHelper.class)) {
      Account authenticated = stubAuthenticatedAccount(dal);
      when(authenticated.getId()).thenReturn("account-1");
      dal.when(() -> EtendoGoJwtDalHelper.findCurrencyByIsoCode("EUR")).thenReturn(currency);
      dal.when(() -> EtendoGoJwtDalHelper.countTenantsOwnedByAccountEmail("user@test.com")).thenReturn(1);
      dal.when(() -> EtendoGoJwtDalHelper.hasOwnedEnvironmentForAccountEmail("user@test.com")).thenReturn(true);
      dal.when(() -> EtendoGoJwtDalHelper.findClientAdminUserRole("client-1")).thenReturn(admin);
      support.when(() -> EtendoGoJwtSupport.findStarOrgId("client-1")).thenReturn("star-org");
      dal.when(() -> EtendoGoJwtDalHelper.findFirstOrganization("client-1")).thenReturn(org);
      before.apply(support, dal, transactions);
      if (stagedAction == null) {
        servlet.doPost(stage.request, stage.response.response);
      } else {
        stagedAction.run();
      }
      after.apply(support, dal, transactions);
    }
  }

  private void assertProvisioningAlertOutcome(boolean pooled, boolean complete,
      boolean rollbackConfirmed, boolean deliveryThrows, boolean failAfterCommit) throws Exception {
    ResponseCapture response = mockResponse();
    HttpServletRequest request = jsonRequest("/onboarding",
        "{\"clientName\":\"Acme\",\"currency\":\"EUR\",\"language\":\"en_US\","
            + "\"paymentToken\":\"purchase-1\"}");
    when(request.getHeader("Authorization")).thenReturn("Bearer valid-token");
    CheckoutRequestStore store = mock(CheckoutRequestStore.class);
    servlet.checkoutRequestStore = store;
    when(store.claimForProvisioning("purchase-1", "account-1", "user@test.com")).thenReturn(true);
    when(store.findProvisioningAttempt("purchase-1", "account-1", "user@test.com")).thenReturn(1L);
    TenantPaywallService paywall = new TenantPaywallService();
    Field confirmation = TenantPaywallService.class.getDeclaredField("paymentConfirmation");
    confirmation.setAccessible(true);
    confirmation.set(paywall, (TenantPaywallService.PaymentConfirmation) (token, email, name) -> true);
    servlet.tenantPaywallService = paywall;
    servlet.pooledTenantClaimService = mock(PooledTenantClaimService.class);
    when(servlet.pooledTenantClaimService.claim(any(), any(), anyString()))
        .thenReturn(pooled ? "client-1" : null);
    servlet.devProvisioningFailureFixtureService = mock(
        DevProvisioningFailureFixtureService.class);
    servlet.tenantPlanService = mock(TenantPlanService.class);
    when(servlet.tenantPlanService.markProductive("client-1", "star-org")).thenReturn(true);
    servlet.tenantEnvironmentLifecycleService = mock(TenantEnvironmentLifecycleService.class);
    when(servlet.tenantEnvironmentLifecycleService.markProductive("client-1")).thenReturn(true);
    servlet.onboardingForceTestModeService = mock(OnboardingForceTestModeService.class);
    servlet.onboardingOrgInfoService = mock(com.etendoerp.go.onboarding.OnboardingOrgInfoService.class);
    servlet.onboardingWarehouseAddressService = mock(com.etendoerp.go.onboarding.OnboardingWarehouseAddressService.class);
    servlet.onboardingCostingScheduleService = mock(com.etendoerp.go.onboarding.OnboardingCostingScheduleService.class);
    servlet.onboardingInitialPeriodStateService =
        mock(com.etendoerp.go.onboarding.OnboardingInitialPeriodStateService.class);
    // ETP-6148: the remittance setup reads a flag a developer may have on locally; keep it inert.
    servlet.onboardingRemittanceSetupService =
        mock(com.etendoerp.go.onboarding.OnboardingRemittanceSetupService.class);
    if (failAfterCommit) doThrow(new RuntimeException("post-commit scheduler failure"))
        .when(servlet.onboardingCostingScheduleService).activateSchedule("client-1");
    Currency currency = mock(Currency.class); when(currency.getId()).thenReturn("currency-1");
    UserRoles admin = mock(UserRoles.class); Role role = mock(Role.class); User user = mock(User.class);
    when(role.getId()).thenReturn("role-1"); when(user.getId()).thenReturn("user-1");
    when(admin.getRole()).thenReturn(role); when(admin.getUserContact()).thenReturn(user);
    Organization org = mock(Organization.class); when(org.getId()).thenReturn("org-1");
    java.util.concurrent.atomic.AtomicBoolean settled = new java.util.concurrent.atomic.AtomicBoolean();
    java.util.List<com.etendoerp.go.schemaforge.email.InternalAlertEvent> events = new java.util.ArrayList<>();
    doAnswer(invocation -> {
      assertTrue("Alert must follow the business transaction boundary", settled.get());
      events.add(invocation.getArgument(0));
      if (deliveryThrows) throw new RuntimeException("fake provider unavailable");
      return null;
    }).when(servlet.internalAlertService).sendAfterTransaction(any());
    try (var context = mockStatic(OBContext.class);
         var support = mockStatic(EtendoGoJwtSupport.class);
         var dal = mockStatic(EtendoGoJwtDalHelper.class);
         var transactions = mockStatic(EtendoGoDalHelper.class)) {
      Account authenticated = stubAuthenticatedAccount(dal);
      when(authenticated.getId()).thenReturn("account-1");
      dal.when(() -> EtendoGoJwtDalHelper.findCurrencyByIsoCode("EUR")).thenReturn(currency);
      dal.when(() -> EtendoGoJwtDalHelper.countTenantsOwnedByAccountEmail("user@test.com")).thenReturn(1);
      dal.when(() -> EtendoGoJwtDalHelper.hasOwnedEnvironmentForAccountEmail("user@test.com")).thenReturn(true);
      support.when(() -> EtendoGoJwtSupport.findClientIdByName(PAID_PROVISIONING_NAME))
          .thenReturn(pooled ? null : "client-1");
      dal.when(() -> EtendoGoJwtDalHelper.clientBelongsToAccountEmail("client-1", "user@test.com")).thenReturn(true);
      dal.when(() -> EtendoGoJwtDalHelper.findClientAdminUserRole("client-1")).thenReturn(complete ? admin : null);
      support.when(() -> EtendoGoJwtSupport.findStarOrgId("client-1")).thenReturn("star-org");
      dal.when(() -> EtendoGoJwtDalHelper.findFirstOrganization("client-1")).thenReturn(org);
      transactions.when(() -> EtendoGoDalHelper.commitDalChanges(eq("onboarding"), any()))
          .thenAnswer(i -> { settled.set(true); return null; });
      transactions.when(() -> EtendoGoDalHelper.rollbackDalChangesAndConfirm(eq("onboarding"), any(), any()))
          .thenAnswer(i -> { settled.set(rollbackConfirmed); return rollbackConfirmed; });
      servlet.doPost(request, response.response);
      if (complete) {
        transactions.verify(() -> EtendoGoDalHelper.commitDalChanges(eq("onboarding"), any()));
        verify(store).recordProvisioned("purchase-1", "client-1", 1L);
        if (!rollbackConfirmed && failAfterCommit)
          verify(store, never()).recordFailureReason(anyString(), anyString());
      } else {
        transactions.verify(() -> EtendoGoDalHelper.rollbackDalChangesAndConfirm(eq("onboarding"), any(), any()));
        verify(store, never()).recordProvisioned(anyString(), anyString(), any());
        if (rollbackConfirmed) verify(store).recordFailureReason(eq("purchase-1"), anyString());
        else verify(store, never()).recordFailureReason(anyString(), anyString());
      }
    }
    if (!rollbackConfirmed) verify(unitSession).setDoRollback(true);
    if (!complete && !rollbackConfirmed) {
      assertTrue(events.isEmpty()); verifyNoMoreInteractions(servlet.internalAlertService);
    } else {
      assertEquals(1, events.size());
      com.etendoerp.go.schemaforge.email.InternalAlertEvent event = events.get(0);
      assertEquals(complete ? com.etendoerp.go.schemaforge.email.InternalAlertEvent.Status.OK
          : com.etendoerp.go.schemaforge.email.InternalAlertEvent.Status.ERROR, event.getStatus());
      assertEquals("PRODUCTIVE", event.getEnvironmentType());
      assertEquals(pooled ? "POOL" : "CLASSIC", event.getPath());
      assertEquals("client-1", event.getClientId());
      assertFalse(event.getAttemptId().equals("purchase-1"));
    }
    assertTrue(response.body().contains(complete && !failAfterCommit ? "\"success\":true" : "\"success\":false"));
  }
}
