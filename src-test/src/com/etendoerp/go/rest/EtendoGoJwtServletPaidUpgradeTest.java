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
import static org.junit.Assert.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.lang.reflect.Method;
import java.util.List;

import org.apache.logging.log4j.Level;
import org.codehaus.jettison.json.JSONObject;
import org.junit.Test;
import org.mockito.MockedStatic;
import org.openbravo.base.exception.OBException;
import org.openbravo.dal.service.OBDal;

import com.etendoerp.go.onboarding.OnboardingForceTestModeService;
import com.etendoerp.go.payment.CheckoutRequestStore;
import com.etendoerp.go.payment.SubscriptionModelSwitch;
import com.etendoerp.go.payment.TenantEnvironmentLifecycleService;
import com.etendoerp.go.payment.TenantPlanService;
import com.etendoerp.go.schemaforge.data.Account;
import com.etendoerp.go.schemaforge.data.CheckoutRequest;
import com.etendoerp.go.schemaforge.data.Plan;
import com.etendoerp.go.schemaforge.data.Subscription;

/**
 * Unit tests for {@code applyPaidUpgradeSideEffects} of {@link EtendoGoJwtServlet} (ETP-5046):
 * which store records a paid tenant's plan — decided first by the environment's
 * subscription-model switch, then by whether the row could be opened — that the per-tenant
 * retirement of the legacy ETGO_TenantPlan preference can never fail an upgrade that has already
 * been paid for, that an unrecordable payment aborts the paid transaction, and that
 * {@code applyCheckoutPaid} records null provider ids blank (ETP-5047).
 *
 * @covers com.etendoerp.go.rest.EtendoGoJwtServlet
 */
public class EtendoGoJwtServletPaidUpgradeTest extends EtendoGoJwtServletTestSupport {

  // ===================== applyPaidUpgradeSideEffects — ETP-5046 per-tenant retirement ==========
  //
  // The paid-upgrade path used to write BOTH stores on every upgrade: the ETGO_SUBSCRIPTION row
  // AND the legacy ETGO_TenantPlan preference. Since ETP-5046 the subscription is the source of
  // truth, so writing the preference alongside it was not a safety measure but a second answer
  // that drifts. The preference is now written ONLY when the subscription write failed — which is
  // exactly when TenantPlanPreferenceFallback needs it — and is RETIRED on the success path, so a
  // newly paid tenant lands directly in the post-cutover state. Grep marker for the Phase F
  // deletion: ETP-5046-TRANSITIONAL-FALLBACK.

  private void applyPaidUpgrade() {
    // The static DAL helper is mocked so no stray OBDal read can add an ERROR line to the log
    // assertions below. The demo link is no longer made here: paid onboarding links the demo
    // recorded on the purchase, in transferDemoCompanyProfile.
    try (MockedStatic<EtendoGoJwtDalHelper> dalMock = mockStatic(EtendoGoJwtDalHelper.class)) {
      servlet.applyPaidUpgradeSideEffects(PAID_CLIENT_ID, PAID_STAR_ORG_ID, "Acme S.L.",
          "user@test.com", PAID_TOKEN);
    }
  }

  @Test
  public void paidUpgradeWithASubscriptionDoesNotWriteThePreferenceAndRetiresTheStaleOne() {
    PaidUpgradeFixture fixture = givenPaidUpgrade(true);
    when(fixture.tenantPlanService.retireProductivePreference(PAID_CLIENT_ID)).thenReturn(true);

    applyPaidUpgrade();

    verify(fixture.subscriptionService)
        .openSubscription(eq(PAID_CLIENT_ID), any(), any(), any(), any(), any());
    // The whole point: no parallel truth is written any more.
    verify(fixture.tenantPlanService, never()).markProductive(anyString(), anyString());
    // ...and whatever marker this tenant still carried is retired, so it is immediately in the
    // post-cutover state and the fleet-wide count moves one closer to zero.
    verify(fixture.tenantPlanService).retireProductivePreference(PAID_CLIENT_ID);
    // A recorded productive tenant still gets its ETSG_ForceTestMode override reverted (ETP-5117).
    verify(fixture.forceTestModeService).revertTestModeForProductiveTenant(PAID_CLIENT_ID);
  }

  @Test
  public void paidUpgradeWhoseSubscriptionWriteFailsWritesThePreferenceAsTheSafetyNet() {
    PaidUpgradeFixture fixture = givenPaidUpgrade(false);
    when(fixture.tenantPlanService.markProductive(PAID_CLIENT_ID, PAID_STAR_ORG_ID))
        .thenReturn(true);

    applyPaidUpgrade();

    // Without a subscription row, the preference is the ONLY record that this tenant paid, and
    // TenantPlanPreferenceFallback is what will read it back. This is the case the fallback and
    // the whole transitional apparatus exist for.
    verify(fixture.tenantPlanService).markProductive(PAID_CLIENT_ID, PAID_STAR_ORG_ID);
    // Nothing is retired: retiring the safety net in the same breath as writing it would leave the
    // tenant with no record at all.
    verify(fixture.tenantPlanService, never()).retireProductivePreference(anyString());
    verify(fixture.forceTestModeService).revertTestModeForProductiveTenant(PAID_CLIENT_ID);
  }

  @Test
  public void paidUpgradeWithNoCheckoutRequestFallsBackToThePreferenceAndRetiresNothing() {
    // The third way the subscription write can fail to record anything: the token resolves to no
    // request at all, so there is no plan to open a subscription on. Same answer as a throwing
    // write — the safety net is written, nothing is retired.
    PaidUpgradeFixture fixture = new PaidUpgradeFixture();
    when(fixture.checkoutRequestStore.find(eq(PAID_TOKEN), anyString())).thenReturn(null);
    servlet.checkoutRequestStore = fixture.checkoutRequestStore;
    servlet.subscriptionService = fixture.subscriptionService;
    servlet.tenantPlanService = fixture.tenantPlanService;
    servlet.onboardingForceTestModeService = fixture.forceTestModeService;
    // Stubbed to succeed as in givenPaidUpgrade: a failed projection now fails the upgrade closed
    // (ETP-5548), which is its own concern and not what this spec is about.
    when(fixture.lifecycleService.markProductive(anyString())).thenReturn(true);
    servlet.tenantEnvironmentLifecycleService = fixture.lifecycleService;
    when(fixture.tenantPlanService.markProductive(PAID_CLIENT_ID, PAID_STAR_ORG_ID))
        .thenReturn(true);

    applyPaidUpgrade();

    verifyNoInteractions(fixture.subscriptionService);
    verify(fixture.tenantPlanService).markProductive(PAID_CLIENT_ID, PAID_STAR_ORG_ID);
    verify(fixture.tenantPlanService, never()).retireProductivePreference(anyString());
  }

  @Test
  public void aFailedRetirementIsLoggedAndNeverFailsAnUpgradeThatWasAlreadyPaidFor() {
    // A failed retirement is the one failure with nothing contradictory left behind — the
    // subscription records the payment, the transitional fallback simply keeps answering for the
    // tenant and the R37 backfill retires the row later — so unlike a missing payment record
    // (ETP-5548 aborts on that) it must be logged loudly and then ignored.
    PaidUpgradeFixture fixture = givenPaidUpgrade(true);
    when(fixture.tenantPlanService.retireProductivePreference(PAID_CLIENT_ID))
        .thenThrow(new IllegalStateException("no session"));
    LogCapture errors = LogCapture.attachTo(EtendoGoJwtServlet.class, Level.ERROR);

    try {
      applyPaidUpgrade();

      List<String> logged = errors.messagesAt(Level.ERROR);
      assertEquals("the failure must be searchable, not silent: " + logged, 1, logged.size());
      assertTrue("the line must name the tenant: " + logged.get(0),
          logged.get(0).contains(PAID_CLIENT_ID));
      assertTrue("the line must name the preference that survived: " + logged.get(0),
          logged.get(0).contains(TenantPlanService.PREFERENCE_ATTRIBUTE));
      // The upgrade completed all the same: the subscription was written and the fiscal test-mode
      // override was still reverted.
      verify(fixture.subscriptionService)
          .openSubscription(eq(PAID_CLIENT_ID), any(), any(), any(), any(), any());
      verify(fixture.forceTestModeService).revertTestModeForProductiveTenant(PAID_CLIENT_ID);
      // And the failed retirement must NOT make the servlet fall back to writing the marker: the
      // subscription exists, so the tenant is recorded.
      verify(fixture.tenantPlanService, never()).markProductive(anyString(), anyString());
    } finally {
      errors.detach();
    }
  }

  @Test
  public void aFallbackPurchaseOpensOnTheGrandfatheredPlanWithTheChargedPrice() {
    // Bought under the legacy price fallback: the request carries legacy-productive, which has no
    // price of its own, and the configured price that was actually charged. The subscription must
    // snapshot THAT price — it is the only record of what this subscriber pays.
    PaidUpgradeFixture fixture = new PaidUpgradeFixture();
    Plan legacy = mock(Plan.class);
    when(legacy.getSearchKey()).thenReturn("legacy-productive");
    Account payer = mock(Account.class);
    CheckoutRequest checkoutRequest = mock(CheckoutRequest.class);
    when(checkoutRequest.getPlan()).thenReturn(legacy);
    when(checkoutRequest.getEtendoGoAccount()).thenReturn(payer);
    when(checkoutRequest.getStripeCustomer()).thenReturn("cus_legacy");
    when(checkoutRequest.getStripeSubscription()).thenReturn("sub_legacy");
    when(checkoutRequest.getStripePrice()).thenReturn("price_LEGACY_configured");
    when(fixture.checkoutRequestStore.find(eq(PAID_TOKEN), anyString())).thenReturn(checkoutRequest);
    Subscription opened = mock(Subscription.class);
    when(fixture.subscriptionService.openSubscription(anyString(), any(), any(), any(), any(),
        any())).thenReturn(opened);
    servlet.checkoutRequestStore = fixture.checkoutRequestStore;
    servlet.subscriptionService = fixture.subscriptionService;
    servlet.tenantPlanService = fixture.tenantPlanService;
    servlet.onboardingForceTestModeService = fixture.forceTestModeService;
    when(fixture.lifecycleService.markProductive(anyString())).thenReturn(true);
    servlet.tenantEnvironmentLifecycleService = fixture.lifecycleService;

    applyPaidUpgrade();

    verify(fixture.subscriptionService).openSubscription(PAID_CLIENT_ID, legacy, payer,
        "cus_legacy", "sub_legacy", "price_LEGACY_configured");
    verify(fixture.tenantPlanService, never()).markProductive(anyString(), anyString());
  }

  @Test
  public void paidUpgradeWithTheSubscriptionModelOffWritesThePreferenceAndOpensNoRow() {
    // ETGO_SubscriptionModel is not 'Y' in this environment: the purchase is recorded exactly as
    // before ETP-5046 — the ETGO_TenantPlan marker, no ETGO_SUBSCRIPTION row — even though the
    // checkout request carries a plan and the row WOULD open.
    PaidUpgradeFixture fixture = givenPaidUpgrade(true);
    servlet.subscriptionModelSwitch = switchThatIs(false);
    when(fixture.tenantPlanService.markProductive(PAID_CLIENT_ID, PAID_STAR_ORG_ID))
        .thenReturn(true);

    applyPaidUpgrade();

    // The only subscription call is the "does this tenant already have a row?" lookup.
    verify(fixture.subscriptionService).hasAnySubscription(PAID_CLIENT_ID);
    verify(fixture.subscriptionService, never())
        .openSubscription(anyString(), any(), any(), any(), any(), any());
    verifyNoInteractions(fixture.checkoutRequestStore);
    verify(fixture.tenantPlanService).markProductive(PAID_CLIENT_ID, PAID_STAR_ORG_ID);
    // The marker just written is the tenant's only record: retiring it would erase the payment.
    verify(fixture.tenantPlanService, never()).retireProductivePreference(anyString());
    // The rest of the paid upgrade is unchanged: lifecycle projection and the demo override.
    verify(fixture.lifecycleService).markProductive(PAID_CLIENT_ID);
    verify(fixture.forceTestModeService).revertTestModeForProductiveTenant(PAID_CLIENT_ID);
  }

  @Test
  public void paidUpgradeWithTheSubscriptionModelOffIsNotLoggedAsAFailure() {
    // OFF is the default state of every environment until its operator switches, so the
    // preference path it takes must not read like the failed-subscription safety net.
    PaidUpgradeFixture fixture = givenPaidUpgrade(true);
    servlet.subscriptionModelSwitch = switchThatIs(false);
    when(fixture.tenantPlanService.markProductive(PAID_CLIENT_ID, PAID_STAR_ORG_ID))
        .thenReturn(true);
    LogCapture errors = LogCapture.attachTo(EtendoGoJwtServlet.class, Level.ERROR);

    try {
      applyPaidUpgrade();

      assertTrue("a switched-off environment is not an error: " + errors.messagesAt(Level.ERROR),
          errors.messagesAt(Level.ERROR).isEmpty());
    } finally {
      errors.detach();
    }
  }

  @Test
  public void paidUpgradeWithTheSubscriptionModelOffStillAbortsWhenThePreferenceCannotBeWritten() {
    // With no row to fall back on, the marker is the payment record: failing to write it must
    // fail the upgrade closed (ETP-5548) exactly as on the subscription path.
    PaidUpgradeFixture fixture = givenPaidUpgrade(true);
    servlet.subscriptionModelSwitch = switchThatIs(false);
    when(fixture.tenantPlanService.markProductive(PAID_CLIENT_ID, PAID_STAR_ORG_ID))
        .thenReturn(false);

    OBException failure = assertThrows(OBException.class, this::applyPaidUpgrade);

    verify(fixture.subscriptionService, never())
        .openSubscription(anyString(), any(), any(), any(), any(), any());
    verify(fixture.lifecycleService, never()).markProductive(anyString());
    // No subscription was attempted, so the failure reads as it did before ETP-5046.
    assertTrue(failure.getMessage(),
        failure.getMessage().contains("could not be marked as plan 'productive'"));
  }

  @Test
  public void aFailedSubscriptionAndAFailedMarkerStillNameBothRecords() {
    // The other fail-closed wording: a row WAS attempted, so the message says neither exists.
    PaidUpgradeFixture fixture = givenPaidUpgrade(false);
    when(fixture.tenantPlanService.markProductive(PAID_CLIENT_ID, PAID_STAR_ORG_ID))
        .thenReturn(false);

    OBException failure = assertThrows(OBException.class, this::applyPaidUpgrade);

    assertTrue(failure.getMessage(),
        failure.getMessage().contains("has neither a subscription nor the fallback plan marker"));
  }

  @Test
  public void aTenantThatAlreadyHasARowIsRecordedOnTheRowEvenWithTheModelOff() {
    // Every read answers from a tenant's row whenever it has one (open or closed). Recording its
    // new payment only in the ETGO_TenantPlan marker would be invisible: a canceled or closed row
    // would keep the paying tenant free and blocked. So the switch decides only for rowless
    // tenants — "a tenant with a row is decided by the row" holds for writes too.
    PaidUpgradeFixture fixture = givenPaidUpgrade(true);
    servlet.subscriptionModelSwitch = switchThatIs(false);
    when(fixture.subscriptionService.hasAnySubscription(PAID_CLIENT_ID)).thenReturn(true);

    applyPaidUpgrade();

    verify(fixture.subscriptionService)
        .openSubscription(eq(PAID_CLIENT_ID), any(), any(), any(), any(), any());
    verify(fixture.tenantPlanService, never()).markProductive(anyString(), anyString());
    verify(fixture.tenantPlanService).retireProductivePreference(PAID_CLIENT_ID);
  }

  @Test
  public void withTheModelOnTheExistingRowLookupIsNotEvenNeeded() {
    // ON already means "open a row"; the extra lookup is only paid for on a switched-off
    // environment.
    PaidUpgradeFixture fixture = givenPaidUpgrade(true);

    applyPaidUpgrade();

    verify(fixture.subscriptionService)
        .openSubscription(eq(PAID_CLIENT_ID), any(), any(), any(), any(), any());
    verify(fixture.subscriptionService, never()).hasAnySubscription(anyString());
  }

  @Test
  public void aFailedRowLookupWithTheModelOffFallsBackToTheMarkerAndIsLogged() {
    PaidUpgradeFixture fixture = givenPaidUpgrade(true);
    servlet.subscriptionModelSwitch = switchThatIs(false);
    when(fixture.subscriptionService.hasAnySubscription(PAID_CLIENT_ID))
        .thenThrow(new IllegalStateException("no session"));
    when(fixture.tenantPlanService.markProductive(PAID_CLIENT_ID, PAID_STAR_ORG_ID))
        .thenReturn(true);
    LogCapture errors = LogCapture.attachTo(EtendoGoJwtServlet.class, Level.ERROR);

    try {
      applyPaidUpgrade();

      verify(fixture.tenantPlanService).markProductive(PAID_CLIENT_ID, PAID_STAR_ORG_ID);
      verify(fixture.subscriptionService, never())
          .openSubscription(anyString(), any(), any(), any(), any(), any());
      List<String> logged = errors.messagesAt(Level.ERROR);
      assertEquals("the failed lookup must be searchable: " + logged, 1, logged.size());
      assertTrue(logged.get(0), logged.get(0).contains(PAID_CLIENT_ID));
    } finally {
      errors.detach();
    }
  }

  @Test
  public void theSubscriptionModelSwitchIsReadOnEveryPurchase() {
    // No caching: an operator who flips the switch must not need a restart. The second purchase
    // of the same servlet instance sees the new value.
    PaidUpgradeFixture fixture = givenPaidUpgrade(true);
    SubscriptionModelSwitch modelSwitch = mock(SubscriptionModelSwitch.class);
    when(modelSwitch.isOn()).thenReturn(false, true);
    servlet.subscriptionModelSwitch = modelSwitch;
    when(fixture.tenantPlanService.markProductive(PAID_CLIENT_ID, PAID_STAR_ORG_ID))
        .thenReturn(true);

    applyPaidUpgrade();
    verify(fixture.subscriptionService, never())
        .openSubscription(anyString(), any(), any(), any(), any(), any());

    applyPaidUpgrade();
    verify(fixture.subscriptionService)
        .openSubscription(eq(PAID_CLIENT_ID), any(), any(), any(), any(), any());
    verify(modelSwitch, times(2)).isOn();
  }

  /**
   * A paid onboarding must abort when its payment cannot be recorded at all: no subscription row
   * (no checkout request behind the token) and no fallback plan marker either.
   */
  @Test
  public void paidUpgradeMustAbortWhenProductivePlanMarkerCannotBeWritten() throws Exception {
    TenantPlanService plans = mock(TenantPlanService.class);
    TenantEnvironmentLifecycleService lifecycle = mock(TenantEnvironmentLifecycleService.class);
    when(plans.markProductive("CLIENT-PAID", "ORG-STAR")).thenReturn(false);
    servlet.tenantPlanService = plans;
    servlet.tenantEnvironmentLifecycleService = lifecycle;
    servlet.checkoutRequestStore = mock(CheckoutRequestStore.class);

    assertThrows(OBException.class,
        () -> servlet.applyPaidUpgradeSideEffects("CLIENT-PAID", "ORG-STAR", "Paid Co",
            "paid@example.test", PAID_TOKEN));
    verify(lifecycle, never()).markProductive(anyString());
  }

  /** A lifecycle projection failure must also abort before the pooled tenant can commit. */
  @Test
  public void paidUpgradeMustAbortWhenLifecycleProjectionCannotBeWritten() throws Exception {
    TenantPlanService plans = mock(TenantPlanService.class);
    TenantEnvironmentLifecycleService lifecycle = mock(TenantEnvironmentLifecycleService.class);
    when(plans.markProductive("CLIENT-PAID", "ORG-STAR")).thenReturn(true);
    when(lifecycle.markProductive("CLIENT-PAID")).thenReturn(false);
    servlet.tenantPlanService = plans;
    servlet.tenantEnvironmentLifecycleService = lifecycle;
    servlet.checkoutRequestStore = mock(CheckoutRequestStore.class);

    assertThrows(OBException.class,
        () -> servlet.applyPaidUpgradeSideEffects("CLIENT-PAID", "ORG-STAR", "Paid Co",
            "paid@example.test", PAID_TOKEN));
    verify(lifecycle).markProductive("CLIENT-PAID");
  }

  /** A failed demo override removal must abort the paid transaction as well. */
  @Test
  public void paidUpgradeMustAbortWhenDemoOverrideCannotBeRemoved() throws Exception {
    TenantPlanService plans = mock(TenantPlanService.class);
    TenantEnvironmentLifecycleService lifecycle = mock(TenantEnvironmentLifecycleService.class);
    OnboardingForceTestModeService forceTestMode = mock(OnboardingForceTestModeService.class);
    when(plans.markProductive("CLIENT-PAID", "ORG-STAR")).thenReturn(true);
    when(lifecycle.markProductive("CLIENT-PAID")).thenReturn(true);
    doThrow(new OBException("override removal failed"))
        .when(forceTestMode).revertTestModeForProductiveTenant("CLIENT-PAID");
    servlet.tenantPlanService = plans;
    servlet.tenantEnvironmentLifecycleService = lifecycle;
    servlet.checkoutRequestStore = mock(CheckoutRequestStore.class);
    servlet.onboardingForceTestModeService = forceTestMode;

    assertThrows(OBException.class,
        () -> servlet.applyPaidUpgradeSideEffects("CLIENT-PAID", "ORG-STAR", "Paid Co",
            "paid@example.test", PAID_TOKEN));
    verify(forceTestMode).revertTestModeForProductiveTenant("CLIENT-PAID");
  }

  // ===================== applyCheckoutPaid — nullable Stripe ids (ETP-5047) ================

  /**
   * A payment-mode checkout reports {@code subscription} (and may report {@code customer}) as
   * JSON {@code null}. Jettison's {@code optString} turns that marker into the text "null", which
   * used to be stored as the Stripe subscription id — and the post-onboarding period read then
   * asked Stripe for {@code /v1/subscriptions/null}. A JSON null must reach the store as blank.
   */
  @Test
  public void aCheckoutPaidWithNullProviderIdsRecordsThemBlankNeverAsTheWordNull()
      throws Exception {
    CheckoutRequestStore store = mock(CheckoutRequestStore.class);
    servlet.checkoutRequestStore = store;
    servlet.billingEventStore = mock(com.etendoerp.go.payment.BillingEventStore.class);
    when(store.recordPaid(anyString(), anyString(), anyString())).thenReturn(true);
    JSONObject event = new JSONObject("{\"type\":\"checkout.session.completed\",\"data\":"
        + "{\"object\":{\"id\":\"cs_1\",\"mode\":\"payment\",\"customer\":null,"
        + "\"subscription\":null,\"metadata\":{\"request_id\":\"purchase-1\","
        + "\"account_email\":\"user@test.com\"}}}}");
    Method applyCheckoutPaid = EtendoGoJwtServlet.class.getDeclaredMethod("applyCheckoutPaid",
        String.class, String.class, JSONObject.class);
    applyCheckoutPaid.setAccessible(true);

    applyCheckoutPaid.invoke(servlet, "evt_1", "checkout.session.completed", event);

    verify(store).recordPaid("purchase-1", "", "");
  }
}
