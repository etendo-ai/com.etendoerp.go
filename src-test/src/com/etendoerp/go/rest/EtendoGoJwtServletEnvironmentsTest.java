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
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockConstruction;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;

import javax.servlet.http.HttpServletRequest;

import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONObject;
import org.junit.Test;
import org.mockito.ArgumentMatchers;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.ad.access.User;
import org.openbravo.model.ad.system.Client;
import org.openbravo.model.common.enterprise.Organization;

import com.etendoerp.go.payment.EnvironmentPlanCache;
import com.etendoerp.go.payment.SubscriptionModelSwitch;
import com.etendoerp.go.payment.SubscriptionService;
import com.etendoerp.go.payment.TenantPlanService;
import com.etendoerp.go.schemaforge.data.Account;
import com.etendoerp.go.schemaforge.data.Subscription;

/**
 * Unit tests for {@link EtendoGoJwtServlet} on {@code GET /environments}: the data-mapping loop
 * (one quota report and one batched renewal read for every entry, users with and without
 * organizations), its database error, and which unfinished paid environments are hidden from the
 * list (ETP-5548).
 *
 * @covers com.etendoerp.go.rest.EtendoGoJwtServlet
 */
public class EtendoGoJwtServletEnvironmentsTest extends EtendoGoJwtServletTestSupport {

  // ===================== GET /environments — data-mapping loop =====================

  /**
   * ETP-5051 (§4b) — the environment list builds ONE quota report, from the same subscriptions it
   * reads the plans from, and hands that report to every environment entry it writes.
   */
  @Test
  public void environmentsBuildOneQuotaReportAndHandItToEveryEntry() throws Exception {
    ResponseCapture resp = mockResponse();
    HttpServletRequest req = mockRequest("/environments");
    when(req.getHeader("Authorization")).thenReturn("Bearer valid-token");
    Account account = mock(Account.class);
    when(account.getEmail()).thenReturn("user@test.com");
    User first = mock(User.class);
    Client firstClient = mock(Client.class);
    when(firstClient.getId()).thenReturn("client-1");
    when(first.getClient()).thenReturn(firstClient);
    User second = mock(User.class);
    Client secondClient = mock(Client.class);
    when(secondClient.getId()).thenReturn("client-2");
    when(second.getClient()).thenReturn(secondClient);
    java.util.Map<String, com.etendoerp.go.schemaforge.data.Subscription> subscriptions =
        new java.util.HashMap<>();
    subscriptions.put("client-1", mock(com.etendoerp.go.schemaforge.data.Subscription.class));
    SubscriptionService subscriptionService = mock(SubscriptionService.class);
    when(subscriptionService.findLatestForClients(any())).thenReturn(subscriptions);
    servlet.subscriptionService = subscriptionService;
    com.etendoerp.go.usage.quota.EnvironmentQuotaReport report =
        com.etendoerp.go.usage.quota.EnvironmentQuotaReport.empty();

    try (var ctxMock = mockStatic(OBContext.class);
         var dalMock = mockStatic(EtendoGoJwtDalHelper.class)) {
      dalMock.when(() -> EtendoGoJwtDalHelper.findActiveAccountByBearerToken("valid-token"))
          .thenReturn(account);
      dalMock.when(() -> EtendoGoJwtDalHelper.findEnvironmentUsersByAccountEmail("user@test.com"))
          .thenReturn(new ArrayList<>(List.of(first, second)));
      dalMock.when(() -> EtendoGoJwtDalHelper.findNonStarOrganizations(anyString()))
          .thenReturn(Collections.emptyList());
      dalMock.when(() -> EtendoGoJwtDalHelper.quotaReport(subscriptions)).thenReturn(report);
      dalMock.when(() -> EtendoGoJwtDalHelper.buildEnvironmentJson(
          any(Client.class), any(), any(User.class), any(EnvironmentPlanCache.class), any()))
          .thenReturn(new JSONObject());

      servlet.doGet(req, resp.response);

      dalMock.verify(() -> EtendoGoJwtDalHelper.quotaReport(subscriptions),
          org.mockito.Mockito.times(1));
      dalMock.verify(() -> EtendoGoJwtDalHelper.buildEnvironmentJson(eq(firstClient), eq(null),
          eq(first), any(EnvironmentPlanCache.class), org.mockito.ArgumentMatchers.same(report)));
      dalMock.verify(() -> EtendoGoJwtDalHelper.buildEnvironmentJson(eq(secondClient), eq(null),
          eq(second), any(EnvironmentPlanCache.class), org.mockito.ArgumentMatchers.same(report)));
    }

    assertEquals(200, resp.status);
  }

  /**
   * ETP-5051 (O-1b) — "Mis empresas" dates each row by its own environment. End to end through
   * {@code handleEnvironments} and the real {@code buildEnvironmentJson}: two renewing tenants
   * each carry their OWN {@code CURRENT_PERIOD_END} as {@code renewalAt} ({@link
   * java.time.Instant#toString()} form), read from ONE batched {@code findLatestForClients} (no
   * per-environment read, no Stripe), and a tenant productive only through the transitional
   * preference fallback, with no subscription row, has {@code renewalAt} JSON null.
   */
  @Test
  public void environmentsCarryEachTenantsOwnRenewalFromOneBatchedRead() throws Exception {
    ResponseCapture resp = mockResponse();
    HttpServletRequest req = mockRequest("/environments");
    when(req.getHeader("Authorization")).thenReturn("Bearer valid-token");
    Account account = mock(Account.class);
    when(account.getEmail()).thenReturn("user@test.com");
    User spring7 = environmentUser("client-spring7", "SPRING7", "user-7");
    User spring8 = environmentUser("client-spring8", "SPRING8", "user-8");
    User legacy = environmentUser("client-legacy", "LEGACY", "user-l");
    java.util.Map<String, Subscription> subscriptions = new java.util.LinkedHashMap<>();
    subscriptions.put("client-spring7", renewingSubscription("2026-11-23T14:09:48Z"));
    subscriptions.put("client-spring8", renewingSubscription("2026-12-05T08:00:00Z"));
    SubscriptionService subscriptionService = mock(SubscriptionService.class);
    when(subscriptionService.findLatestForClients(any())).thenReturn(subscriptions);
    servlet.subscriptionService = subscriptionService;
    // The retired ETGO_TenantPlan preference that keeps the legacy tenant productive.
    org.openbravo.model.ad.domain.Preference legacyPreference =
        mock(org.openbravo.model.ad.domain.Preference.class);
    Client legacyClient = legacy.getClient();
    when(legacyPreference.getVisibleAtClient()).thenReturn(legacyClient);
    when(legacyPreference.getSearchKey()).thenReturn(TenantPlanService.PLAN_PRODUCTIVE);
    @SuppressWarnings("unchecked")
    org.openbravo.dal.service.OBQuery<org.openbravo.model.ad.domain.Preference> preferences =
        mock(org.openbravo.dal.service.OBQuery.class);
    when(preferences.list()).thenReturn(List.of(legacyPreference));
    OBDal obDal = mock(OBDal.class);
    when(obDal.createQuery(eq(org.openbravo.model.ad.domain.Preference.class), anyString()))
        .thenReturn(preferences);
    org.openbravo.base.session.OBPropertiesProvider properties =
        mock(org.openbravo.base.session.OBPropertiesProvider.class);
    when(properties.getOpenbravoProperties()).thenReturn(new java.util.Properties());

    try (var ctxMock = mockStatic(OBContext.class);
         var dalMock = mockStatic(EtendoGoJwtDalHelper.class);
         var obDalMock = mockStatic(OBDal.class);
         var ownerMock = mockStatic(com.etendoerp.go.schemaforge.util.OwnerSupport.class);
         var flagMock = mockStatic(com.etendoerp.go.payment.EnvironmentAccessEnforcementFlag.class);
         var configMock = mockStatic(com.etendoerp.go.common.ConfigPropertyReader.class);
         var propertiesMock = mockStatic(org.openbravo.base.session.OBPropertiesProvider.class);
         var modelSwitch = mockConstruction(SubscriptionModelSwitch.class)) {
      obDalMock.when(OBDal::getInstance).thenReturn(obDal);
      propertiesMock.when(org.openbravo.base.session.OBPropertiesProvider::getInstance)
          .thenReturn(properties);
      dalMock.when(() -> EtendoGoJwtDalHelper.findActiveAccountByBearerToken("valid-token"))
          .thenReturn(account);
      dalMock.when(() -> EtendoGoJwtDalHelper.findEnvironmentUsersByAccountEmail("user@test.com"))
          .thenReturn(new ArrayList<>(List.of(spring7, spring8, legacy)));
      dalMock.when(() -> EtendoGoJwtDalHelper.findNonStarOrganizations(anyString()))
          .thenReturn(Collections.emptyList());
      dalMock.when(() -> EtendoGoJwtDalHelper.quotaReport(subscriptions))
          .thenReturn(com.etendoerp.go.usage.quota.EnvironmentQuotaReport.empty());
      dalMock.when(() -> EtendoGoJwtDalHelper.buildEnvironmentJson(
          any(Client.class), any(), any(User.class), any(EnvironmentPlanCache.class), any()))
          .thenCallRealMethod();

      servlet.doGet(req, resp.response);
    }

    assertEquals(200, resp.status);
    verify(subscriptionService, times(1)).findLatestForClients(
        new java.util.LinkedHashSet<>(List.of("client-spring7", "client-spring8",
            "client-legacy")));
    verifyNoMoreInteractions(subscriptionService);
    JSONArray environments = new JSONObject(resp.body()).getJSONArray("environments");
    assertEquals(3, environments.length());
    JSONObject spring7Row = environmentRow(environments, "client-spring7");
    JSONObject spring8Row = environmentRow(environments, "client-spring8");
    JSONObject legacyRow = environmentRow(environments, "client-legacy");
    assertEquals("2026-11-23T14:09:48Z", spring7Row.getString("renewalAt"));
    assertEquals("2026-12-05T08:00:00Z", spring8Row.getString("renewalAt"));
    // Productive through the fallback (the badge the SPA branches on), yet nothing renews.
    assertEquals(TenantPlanService.PLAN_PRODUCTIVE, legacyRow.getString("plan"));
    assertTrue(legacyRow.toString(), legacyRow.has("renewalAt"));
    assertTrue(legacyRow.toString(), legacyRow.isNull("renewalAt"));
  }

  private static User environmentUser(String clientId, String clientName, String userId) {
    Client client = mock(Client.class);
    when(client.getId()).thenReturn(clientId);
    when(client.getName()).thenReturn(clientName);
    User user = mock(User.class);
    when(user.getId()).thenReturn(userId);
    when(user.getClient()).thenReturn(client);
    return user;
  }

  private static Subscription renewingSubscription(String periodEnd) {
    Subscription subscription = mock(Subscription.class);
    when(subscription.getSubscriptionStatus()).thenReturn(SubscriptionService.STATUS_ACTIVE);
    when(subscription.getCurrentPeriodEnd())
        .thenReturn(Date.from(java.time.Instant.parse(periodEnd)));
    return subscription;
  }

  private static JSONObject environmentRow(JSONArray environments, String clientId)
      throws Exception {
    for (int i = 0; i < environments.length(); i++) {
      if (clientId.equals(environments.getJSONObject(i).getString("clientId"))) {
        return environments.getJSONObject(i);
      }
    }
    throw new AssertionError("No environment row for " + clientId + ": " + environments);
  }

  @Test
  public void environmentsMapsUsersWithAndWithoutOrganizations() throws Exception {
    ResponseCapture resp = mockResponse();
    HttpServletRequest req = mockRequest("/environments");
    when(req.getHeader("Authorization")).thenReturn("Bearer valid-token");

    Account account = mock(Account.class);
    when(account.getEmail()).thenReturn("user@test.com");

    User userWithOrgs = mock(User.class);
    Client clientWithOrgs = mock(Client.class);
    when(clientWithOrgs.getId()).thenReturn("client-1");
    when(userWithOrgs.getClient()).thenReturn(clientWithOrgs);

    User userWithoutOrgs = mock(User.class);
    Client clientWithoutOrgs = mock(Client.class);
    when(clientWithoutOrgs.getId()).thenReturn("client-2");
    when(userWithoutOrgs.getClient()).thenReturn(clientWithoutOrgs);

    Organization org = mock(Organization.class);

    List<User> users = new ArrayList<>();
    users.add(userWithOrgs);
    users.add(userWithoutOrgs);

    // The environment list resolves every tenant's plan through ONE subscription query built
    // before the sort. Stubbed here so this mapping spec stays a pure unit test.
    SubscriptionService subscriptionService = mock(SubscriptionService.class);
    when(subscriptionService.findOpenForClients(any())).thenReturn(java.util.Map.of());
    servlet.subscriptionService = subscriptionService;

    try (var ctxMock = mockStatic(OBContext.class);
         var dalMock = mockStatic(EtendoGoJwtDalHelper.class)) {
      dalMock.when(() -> EtendoGoJwtDalHelper.findActiveAccountByBearerToken("valid-token"))
          .thenReturn(account);
      dalMock.when(() -> EtendoGoJwtDalHelper.findEnvironmentUsersByAccountEmail("user@test.com"))
          .thenReturn(users);
      dalMock.when(() -> EtendoGoJwtDalHelper.findNonStarOrganizations("client-1"))
          .thenReturn(Collections.singletonList(org));
      dalMock.when(() -> EtendoGoJwtDalHelper.findNonStarOrganizations("client-2"))
          .thenReturn(Collections.emptyList());
      dalMock.when(() -> EtendoGoJwtDalHelper.buildEnvironmentJson(
          any(Client.class), any(), any(User.class), any(EnvironmentPlanCache.class), any()))
          .thenReturn(new JSONObject());

      servlet.doGet(req, resp.response);

      dalMock.verify(() -> EtendoGoJwtDalHelper.buildEnvironmentJson(
          eq(clientWithOrgs), eq(org), eq(userWithOrgs), any(EnvironmentPlanCache.class), any()));
      dalMock.verify(() -> EtendoGoJwtDalHelper.buildEnvironmentJson(
          eq(clientWithoutOrgs), eq(null), eq(userWithoutOrgs),
          any(EnvironmentPlanCache.class), any()));
    }

    assertEquals(200, resp.status);
    JSONObject body = new JSONObject(resp.body());
    assertEquals(2, body.getJSONArray("environments").length());
    // The account email is the backend's flag-targeting key, returned so the web client can target
    // on the same identity without a second call to /me (ETP-4686).
    assertEquals("user@test.com", body.getString("accountEmail"));
  }

  @Test
  public void environmentsDatabaseErrorReturnsServerError() throws Exception {
    ResponseCapture resp = mockResponse();
    HttpServletRequest req = mockRequest("/environments");
    when(req.getHeader("Authorization")).thenReturn("Bearer valid-token");

    Account account = mock(Account.class);
    when(account.getEmail()).thenReturn("user@test.com");

    try (var ctxMock = mockStatic(OBContext.class);
         var dalMock = mockStatic(EtendoGoJwtDalHelper.class)) {
      dalMock.when(() -> EtendoGoJwtDalHelper.findActiveAccountByBearerToken("valid-token"))
          .thenReturn(account);
      dalMock.when(() -> EtendoGoJwtDalHelper.findEnvironmentUsersByAccountEmail("user@test.com"))
          .thenThrow(new RuntimeException("db down"));

      servlet.doGet(req, resp.response);
    }

    assertEquals(500, resp.status);
  }

  /** ETP-5548: only a tenant created by the unfinished purchase is hidden from /environments. */
  @Test
  public void unfinishedPaidEnvironmentIsHiddenOnlyWhenCreatedByThePurchase() {
    Date paidAt = new Date(1_790_000_000_000L);
    java.util.Map<String, Date> unfinished = java.util.Map.of("acme", paidAt);
    Client createdAfterPayment = mock(Client.class);
    when(createdAfterPayment.getName()).thenReturn("ACME");
    when(createdAfterPayment.getCreationDate()).thenReturn(new Date(paidAt.getTime() + 1000L));
    Client demoConvertedFromBefore = mock(Client.class);
    when(demoConvertedFromBefore.getName()).thenReturn("Acme");
    when(demoConvertedFromBefore.getCreationDate()).thenReturn(new Date(paidAt.getTime() - 1000L));
    Client unrelated = mock(Client.class);
    when(unrelated.getName()).thenReturn("Other");
    when(unrelated.getCreationDate()).thenReturn(new Date(paidAt.getTime() + 1000L));

    assertTrue(EtendoGoJwtServlet.isUnfinishedPaidEnvironment(createdAfterPayment, unfinished));
    assertFalse("A tenant older than the payment is the customer's working environment",
        EtendoGoJwtServlet.isUnfinishedPaidEnvironment(demoConvertedFromBefore, unfinished));
    assertFalse(EtendoGoJwtServlet.isUnfinishedPaidEnvironment(unrelated, unfinished));
    assertFalse(EtendoGoJwtServlet.isUnfinishedPaidEnvironment(createdAfterPayment,
        java.util.Map.of()));
  }

  /** ETP-5548: a client still under its provisioning name is hidden whatever the purchases say. */
  @Test
  public void clientUnderItsProvisioningNameIsAlwaysHidden() {
    Client building = mock(Client.class);
    when(building.getName()).thenReturn(PAID_PROVISIONING_NAME);

    assertTrue(EtendoGoJwtServlet.isUnfinishedPaidEnvironment(building, java.util.Map.of()));
  }
}
