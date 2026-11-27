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

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

import org.codehaus.jettison.json.JSONObject;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;
import org.openbravo.base.exception.OBException;
import org.openbravo.dal.core.OBContext;
import org.openbravo.model.ad.system.Client;

import com.etendoerp.go.schemaforge.data.CheckoutRequest;

/**
 * Unit tests for {@link EtendoGoJwtServlet} on a paid re-subscription (ETP-5742, ETP-5048):
 * {@code applyCheckoutPaid} reactivates a canceled tenant (with or without a subscription row)
 * and leaves a new purchase to onboarding, and {@link PaidReactivationService} applies a lost
 * re-subscription through the webhook's own {@code applyReactivationIfPending}.
 *
 * @covers com.etendoerp.go.rest.EtendoGoJwtServlet
 * @covers com.etendoerp.go.rest.PaidReactivationService
 */
public class EtendoGoJwtServletPaidReactivationTest extends EtendoGoJwtServletTestSupport {

  // ===================== applyCheckoutPaid — a paid re-subscription (ETP-5742) ===============
  //
  // A re-subscription names its canceled tenant on the request, so the payment webhook applies
  // the purchase to that tenant itself — the same records a paid upgrade writes — and closes the
  // request, instead of leaving it PAID for an onboarding that would provision a second tenant.

  private static final long REACTIVATION_PAID_EPOCH = 1_790_000_000L;

  private PaidUpgradeFixture givenPaidReactivation() {
    PaidUpgradeFixture fixture = givenPaidUpgrade(true);
    Client tenant = mock(Client.class);
    when(tenant.getId()).thenReturn(PAID_CLIENT_ID);
    CheckoutRequest reactivation = mock(CheckoutRequest.class);
    when(reactivation.getCreatedClient()).thenReturn(tenant);
    when(reactivation.getRequest()).thenReturn(PAID_TOKEN);
    when(reactivation.getClientName()).thenReturn("Acme S.L.");
    when(reactivation.getAccountEmail()).thenReturn("user@test.com");
    when(fixture.checkoutRequestStore.recordPaid(anyString(), anyString(), anyString()))
        .thenReturn(true);
    when(fixture.checkoutRequestStore.findPaidReactivation(PAID_TOKEN)).thenReturn(reactivation);
    servlet.billingEventStore = mock(com.etendoerp.go.payment.BillingEventStore.class);
    return fixture;
  }

  private void payReactivation() throws Exception {
    JSONObject event = new JSONObject("{\"type\":\"checkout.session.completed\",\"created\":"
        + REACTIVATION_PAID_EPOCH + ",\"data\":{\"object\":{\"id\":\"cs_1\","
        + "\"customer\":\"cus_1\",\"subscription\":\"sub_new\",\"metadata\":{\"request_id\":\""
        + PAID_TOKEN + "\",\"account_email\":\"user@test.com\"}}}}");
    Method applyCheckoutPaid = EtendoGoJwtServlet.class.getDeclaredMethod("applyCheckoutPaid",
        String.class, String.class, JSONObject.class);
    applyCheckoutPaid.setAccessible(true);
    try (MockedStatic<OBContext> context = mockStatic(OBContext.class);
        MockedStatic<EtendoGoJwtSupport> support = mockStatic(EtendoGoJwtSupport.class);
        MockedStatic<EtendoGoJwtDalHelper> dal = mockStatic(EtendoGoJwtDalHelper.class)) {
      support.when(() -> EtendoGoJwtSupport.findStarOrgId(PAID_CLIENT_ID))
          .thenReturn(PAID_STAR_ORG_ID);
      applyCheckoutPaid.invoke(servlet, "evt_1", "checkout.session.completed", event);
    }
  }

  @Test
  public void aPaidReactivationOfARowTenantOpensANewRowAndClosesTheRequest() throws Exception {
    PaidUpgradeFixture fixture = givenPaidReactivation();
    when(fixture.subscriptionService.hasAnySubscription(PAID_CLIENT_ID)).thenReturn(true);

    payReactivation();

    org.mockito.InOrder order = org.mockito.Mockito.inOrder(fixture.subscriptionService,
        fixture.checkoutRequestStore, servlet.billingEventStore);
    order.verify(fixture.subscriptionService)
        .openSubscription(eq(PAID_CLIENT_ID), any(), any(), any(), any(), any());
    // Closing the request commits the payment record with it, so it comes after.
    order.verify(fixture.checkoutRequestStore).recordProvisioned(PAID_TOKEN, PAID_CLIENT_ID);
    order.verify(servlet.billingEventStore).markApplied("evt_1");
    // A row tenant keeps its watermark on the row: the preference one is never written.
    verify(fixture.lifecycleService, never()).recordSubscriptionEventAt(anyString(), any());
    // The new row's period is read from the provider, as after an onboarding.
    verify(fixture.subscriptionService).fillPeriodFromProviderAfterCommit(PAID_CLIENT_ID);
  }

  @Test
  public void aPaidReactivationOfARowlessTenantWritesThePreferenceProjection() throws Exception {
    PaidUpgradeFixture fixture = givenPaidReactivation();
    servlet.subscriptionModelSwitch = switchThatIs(false);
    when(fixture.subscriptionService.hasAnySubscription(PAID_CLIENT_ID)).thenReturn(false);
    when(fixture.tenantPlanService.markProductive(PAID_CLIENT_ID, PAID_STAR_ORG_ID))
        .thenReturn(true);

    payReactivation();

    verify(fixture.subscriptionService, never())
        .openSubscription(anyString(), any(), any(), any(), any(), any());
    verify(fixture.tenantPlanService).markProductive(PAID_CLIENT_ID, PAID_STAR_ORG_ID);
    verify(fixture.lifecycleService).markProductive(PAID_CLIENT_ID);
    // The watermark moves to this payment, so a late event of the canceled subscription is stale.
    verify(fixture.lifecycleService).recordSubscriptionEventAt(PAID_CLIENT_ID,
        java.time.Instant.ofEpochSecond(REACTIVATION_PAID_EPOCH));
    verify(fixture.checkoutRequestStore).recordProvisioned(PAID_TOKEN, PAID_CLIENT_ID);
    verify(servlet.billingEventStore).markApplied("evt_1");
  }

  @Test
  public void aReactivationThatCannotBeRecordedLeavesTheRequestPaidForTheRetry() throws Exception {
    PaidUpgradeFixture fixture = givenPaidReactivation();
    when(fixture.lifecycleService.markProductive(PAID_CLIENT_ID)).thenReturn(false);

    InvocationTargetException thrown = assertThrows(InvocationTargetException.class,
        this::payReactivation);

    // The webhook handler turns this into a FAILED event and a 500, so the provider redelivers.
    assertTrue(thrown.getCause() instanceof OBException);
    verify(fixture.checkoutRequestStore, never()).recordProvisioned(anyString(), anyString());
    verify(servlet.billingEventStore, never()).markApplied(anyString());
  }

  @Test
  public void aPaidNewPurchaseIsLeftToOnboarding() throws Exception {
    PaidUpgradeFixture fixture = givenPaidReactivation();
    when(fixture.checkoutRequestStore.findPaidReactivation(PAID_TOKEN)).thenReturn(null);

    payReactivation();

    verify(fixture.subscriptionService, never())
        .openSubscription(anyString(), any(), any(), any(), any(), any());
    verify(fixture.checkoutRequestStore, never()).recordProvisioned(anyString(), anyString());
    verify(servlet.billingEventStore).markApplied("evt_1");
  }

  // ===================== PaidReactivationService over the webhook path (ETP-5048) =============
  //
  // The billing reconciliation applies a re-subscription whose payment webhook was lost through
  // applyReactivationIfPending, the webhook's own method, with the provider instant it read.

  private boolean applyThroughTheReconciliation(java.time.Instant paidAt, OBContext caller) {
    try (MockedStatic<OBContext> context = mockStatic(OBContext.class);
        MockedStatic<EtendoGoJwtSupport> support = mockStatic(EtendoGoJwtSupport.class);
        MockedStatic<EtendoGoJwtDalHelper> dal = mockStatic(EtendoGoJwtDalHelper.class)) {
      context.when(OBContext::getOBContext).thenReturn(caller);
      support.when(() -> EtendoGoJwtSupport.findStarOrgId(PAID_CLIENT_ID))
          .thenReturn(PAID_STAR_ORG_ID);
      boolean applied = new PaidReactivationService(() -> servlet).applyIfPending(PAID_TOKEN,
          paidAt);
      if (applied) {
        // Every context switch on the way was undone: the last context installed is the caller's.
        ArgumentCaptor<OBContext> restored = ArgumentCaptor.forClass(OBContext.class);
        context.verify(() -> OBContext.setOBContext(restored.capture()),
            org.mockito.Mockito.atLeastOnce());
        assertSame(caller, restored.getValue());
      }
      return applied;
    }
  }

  @Test
  public void theReconciliationAppliesALostReactivationThroughTheWebhookPath() {
    PaidUpgradeFixture fixture = givenPaidReactivation();
    servlet.subscriptionModelSwitch = switchThatIs(false);
    when(fixture.subscriptionService.hasAnySubscription(PAID_CLIENT_ID)).thenReturn(false);
    when(fixture.tenantPlanService.markProductive(PAID_CLIENT_ID, PAID_STAR_ORG_ID))
        .thenReturn(true);
    java.time.Instant paidAt = java.time.Instant.ofEpochSecond(REACTIVATION_PAID_EPOCH + 60);

    assertTrue(applyThroughTheReconciliation(paidAt, mock(OBContext.class)));

    verify(fixture.lifecycleService).markProductive(PAID_CLIENT_ID);
    // The preference watermark moves to the provider instant the reconciliation read.
    verify(fixture.lifecycleService).recordSubscriptionEventAt(PAID_CLIENT_ID, paidAt);
    verify(fixture.checkoutRequestStore).recordProvisioned(PAID_TOKEN, PAID_CLIENT_ID);
    verify(fixture.subscriptionService).fillPeriodFromProviderAfterCommit(PAID_CLIENT_ID);
  }

  @Test
  public void theReconciliationFindsNothingToApplyOnceTheWebhookApplied() {
    PaidUpgradeFixture fixture = givenPaidReactivation();
    when(fixture.checkoutRequestStore.findPaidReactivation(PAID_TOKEN)).thenReturn(null);

    assertFalse(applyThroughTheReconciliation(null, mock(OBContext.class)));

    verify(fixture.checkoutRequestStore, never()).recordProvisioned(anyString(), anyString());
    verify(fixture.lifecycleService, never()).markProductive(anyString());
  }

  @Test
  public void aReactivationTheReconciliationCannotRecordFailsItsCaller() {
    PaidUpgradeFixture fixture = givenPaidReactivation();
    when(fixture.lifecycleService.markProductive(PAID_CLIENT_ID)).thenReturn(false);
    OBContext caller = mock(OBContext.class);

    try (MockedStatic<OBContext> context = mockStatic(OBContext.class);
        MockedStatic<EtendoGoJwtSupport> support = mockStatic(EtendoGoJwtSupport.class);
        MockedStatic<EtendoGoJwtDalHelper> dal = mockStatic(EtendoGoJwtDalHelper.class)) {
      context.when(OBContext::getOBContext).thenReturn(caller);
      support.when(() -> EtendoGoJwtSupport.findStarOrgId(PAID_CLIENT_ID))
          .thenReturn(PAID_STAR_ORG_ID);
      assertThrows(OBException.class, () -> new PaidReactivationService(() -> servlet)
          .applyIfPending(PAID_TOKEN, null));
      ArgumentCaptor<OBContext> restored = ArgumentCaptor.forClass(OBContext.class);
      context.verify(() -> OBContext.setOBContext(restored.capture()),
          org.mockito.Mockito.atLeastOnce());
      assertSame("A failure still hands the caller its context back", caller,
          restored.getValue());
    }
    verify(fixture.checkoutRequestStore, never()).recordProvisioned(anyString(), anyString());
  }
}
