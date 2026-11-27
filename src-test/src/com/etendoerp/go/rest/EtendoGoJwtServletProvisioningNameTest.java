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
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import javax.servlet.http.Cookie;
import javax.servlet.http.HttpServletRequest;

import org.apache.logging.log4j.Level;
import org.codehaus.jettison.json.JSONObject;
import org.junit.Test;
import org.mockito.ArgumentMatchers;
import org.mockito.MockedStatic;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.ad.system.Client;
import org.openbravo.model.common.enterprise.Organization;

import com.etendoerp.go.common.PublicUrlResolver;
import com.etendoerp.go.payment.CheckoutRequestStore;
import com.etendoerp.go.payment.DemoDataTransferService;
import com.etendoerp.go.payment.EnvironmentAccessPolicy;
import com.etendoerp.go.payment.HostedCheckoutService;
import com.etendoerp.go.payment.ProvisioningFailureReason;
import com.etendoerp.go.payment.TenantEnvironmentLifecycleService;
import com.etendoerp.go.payment.TenantPlanService;
import com.etendoerp.go.schemaforge.data.Account;
import com.etendoerp.go.session.GoSessionRecord;
import com.etendoerp.go.session.GoSessionSecurity;
import com.etendoerp.go.session.GoSessionService;

/**
 * Unit tests for {@link EtendoGoJwtServlet} on the provisioning name of a purchase (ETP-5548):
 * the requested name replaces the provisioning name only on its own client, a reserved
 * provisioning name or a name already used by one of the account's productive environments is
 * refused, and the scoped system context of {@code isProductiveNameTakenByAccount} and
 * {@code isAssociatedDemo} (ETP-5046): each runs as System and hands the caller back its own
 * OBContext and admin-mode depth, on every path.
 *
 * @covers com.etendoerp.go.rest.EtendoGoJwtServlet
 */
public class EtendoGoJwtServletProvisioningNameTest extends EtendoGoJwtServletTestSupport {

  private static final String OTHER_PROVISIONING_NAME =
      ProvisioningClientName.of("account-1", "purchase-2");

  /**
   * ETP-5548: the classic path renames its client from the provisioning name to the company name
   * at the end, with every derived name; a client that does not carry it is left alone.
   */
  @Test
  public void requestedClientNameReplacesTheProvisioningNameOnlyOnItsOwnClient() {
    OBDal obDal = mock(OBDal.class);
    Client building = mock(Client.class);
    when(building.getName()).thenReturn(PAID_PROVISIONING_NAME);
    Client finished = mock(Client.class);
    when(finished.getName()).thenReturn("Acme");
    when(obDal.get(Client.class, "client-1")).thenReturn(building);
    when(obDal.get(Client.class, "client-2")).thenReturn(finished);
    Organization org = mock(Organization.class);
    when(org.getName()).thenReturn("Acme");
    when(obDal.get(Organization.class, "org-1")).thenReturn(org);
    servlet.pooledTenantClaimService = mock(PooledTenantClaimService.class);

    try (var context = mockStatic(OBContext.class);
         var dalStatic = mockStatic(OBDal.class)) {
      dalStatic.when(OBDal::getInstance).thenReturn(obDal);
      servlet.applyRequestedClientName("client-1", "org-1", PAID_PROVISIONING_NAME, "Acme");
      servlet.applyRequestedClientName("client-2", "org-2", OTHER_PROVISIONING_NAME, "Acme");
    }

    verify(building).setName("Acme");
    verify(building).setSearchKey("Acme");
    verify(org, never()).setName(anyString());
    verify(servlet.pooledTenantClaimService).renamePlaceholderDerivedNames("client-1",
        PAID_PROVISIONING_NAME, "Acme");
    verify(finished, never()).setName(anyString());
    verify(servlet.pooledTenantClaimService, never()).renamePlaceholderDerivedNames(eq("client-2"),
        anyString(), anyString());
  }

  /**
   * ETP-5548: a free retry may resume the attempt under a different company name. The organization
   * created by the first attempt takes the new name; its legal name follows only while it still
   * equals the old name, so a legal name copied from the demo is kept.
   */
  @Test
  public void resumedOrganizationTakesTheRetriedNameButKeepsACustomLegalName() {
    OBDal obDal = mock(OBDal.class);
    Client building = mock(Client.class);
    when(building.getName()).thenReturn(FREE_PROVISIONING_NAME);
    when(obDal.get(Client.class, "client-1")).thenReturn(building);
    when(obDal.get(Client.class, "client-2")).thenReturn(building);
    Organization defaulted = mock(Organization.class);
    when(defaulted.getName()).thenReturn("First Try SL");
    when(defaulted.getSocialName()).thenReturn("First Try SL");
    Organization customized = mock(Organization.class);
    when(customized.getName()).thenReturn("First Try SL");
    when(customized.getSocialName()).thenReturn("Acme Holdings SA");
    when(obDal.get(Organization.class, "org-1")).thenReturn(defaulted);
    when(obDal.get(Organization.class, "org-2")).thenReturn(customized);
    servlet.pooledTenantClaimService = mock(PooledTenantClaimService.class);

    try (var context = mockStatic(OBContext.class);
         var dalStatic = mockStatic(OBDal.class)) {
      dalStatic.when(OBDal::getInstance).thenReturn(obDal);
      servlet.applyRequestedClientName("client-1", "org-1", FREE_PROVISIONING_NAME, "Acme");
      servlet.applyRequestedClientName("client-2", "org-2", FREE_PROVISIONING_NAME, "Acme");
    }

    verify(defaulted).setName("Acme");
    verify(defaulted).setSearchKey("Acme");
    verify(defaulted).setSocialName("Acme");
    verify(customized).setName("Acme");
    verify(customized, never()).setSocialName(anyString());
  }

  /** ETP-5548: one account never gets two productive environments with the same name. */
  @Test
  public void purchaseNamedLikeAnOwnProductiveIsRefusedBeforeCheckout() throws Exception {
    PurchaseAttempt attempt = postPurchaseNamed("Acme", TenantPlanService.PLAN_PRODUCTIVE);

    assertEquals(409, attempt.response.status);
    assertTrue(attempt.response.body().contains(ProvisioningFailureReason.CODE_CLIENT_NAME_IN_USE));
    verifyNoInteractions(attempt.checkout);
  }

  /**
   * ETP-5548: the name of the account's demo, or of another account's environment, is free to
   * reuse — the purchase creates a new productive and never converts the demo.
   */
  @Test
  public void purchaseNamedLikeTheDemoOrAnotherAccountReachesCheckout() throws Exception {
    PurchaseAttempt attempt = postPurchaseNamed("Acme", TenantPlanService.PLAN_FREE);

    assertEquals(201, attempt.response.status);
    verify(attempt.checkout).createSession(eq("account-1"), eq("owner@example.test"), eq("Acme"),
        eq("https://app.example.test"), isNull(),
        argThat(EtendoGoJwtServletTestSupport::selectsNoDemo));
  }

  @Test
  public void purchaseWithAReservedProvisioningNameIsRejected() throws Exception {
    PurchaseAttempt attempt = postPurchaseNamed(
        "PEND-0123456789ABCDEF0123456789ABCDEF", TenantPlanService.PLAN_FREE);

    assertEquals(400, attempt.response.status);
    verifyNoInteractions(attempt.checkout);
  }

  @Test
  public void onboardingWithAReservedProvisioningNameIsRejected() throws Exception {
    ResponseCapture resp = mockResponse();
    HttpServletRequest req = jsonRequest("/onboarding",
        "{\"clientName\":\" pend-0123456789abcdef0123456789abcdef \",\"currency\":\"EUR\"}");
    when(req.getHeader("Authorization")).thenReturn("Bearer valid-token");
    try (var ctxMock = mockStatic(OBContext.class);
         var dalMock = mockStatic(EtendoGoJwtDalHelper.class)) {
      stubAuthenticatedAccount(dalMock);

      servlet.doPost(req, resp.response);
    }

    assertEquals(400, resp.status);
    assertTrue(resp.body().contains("reserved"));
  }

  // --- Scoped system context of the ETP-5548 checks (ETP-5046) -----------------------------------
  //
  // Driven directly, never through the checkout endpoints: there hasOwnedEnvironment installs a
  // system context first, which would hide a leak from these two methods.

  private static final String NAME_CHECK_WARNING =
      "Could not check whether company name is in use before checkout";

  /**
   * The name check runs as System with admin mode on, and the caller gets back the very context
   * it had and its admin-mode depth — not a leftover System context.
   */
  @Test
  public void productiveNameCheckRunsAsSystemAndHandsTheCallerItsContextBack() throws Exception {
    OBContext caller = mock(OBContext.class);
    TenantPlanService tenantPlan = mock(TenantPlanService.class);
    servlet.tenantPlanService = tenantPlan;
    when(tenantPlan.resolvePlan("OWN-PROD")).thenReturn(TenantPlanService.PLAN_PRODUCTIVE);
    AtomicBoolean lookupRanAsSystem = new AtomicBoolean();

    try (ContextThread thread = new ContextThread(caller, 1);
        MockedStatic<EtendoGoJwtSupport> support = mockStatic(EtendoGoJwtSupport.class);
        MockedStatic<EtendoGoJwtDalHelper> dal = mockStatic(EtendoGoJwtDalHelper.class)) {
      support.when(() -> EtendoGoJwtSupport.findClientIdsByName("Acme")).thenAnswer(call -> {
        lookupRanAsSystem.set(thread.isSystemWithAdminMode());
        return List.of("OWN-PROD");
      });
      dal.when(() -> EtendoGoJwtDalHelper.clientBelongsToAccountEmail("OWN-PROD",
          "owner@example.test")).thenReturn(true);

      assertTrue(isProductiveNameTakenByAccount("Acme", "owner@example.test"));

      assertTrue("the lookup must see user/role/client/org 0 with admin mode on",
          lookupRanAsSystem.get());
      assertSame(caller, thread.current.get());
      assertEquals(1, thread.adminDepth.get());
    }
  }

  /** A caller with no context (the webhook's case) gets no context back, not a System one. */
  @Test
  public void productiveNameCheckRestoresNoContextAsNoContext() throws Exception {
    servlet.tenantPlanService = mock(TenantPlanService.class);

    try (ContextThread thread = new ContextThread(null, 0);
        MockedStatic<EtendoGoJwtSupport> support = mockStatic(EtendoGoJwtSupport.class)) {
      support.when(() -> EtendoGoJwtSupport.findClientIdsByName("Acme")).thenReturn(List.of());

      assertFalse(isProductiveNameTakenByAccount("Acme", "owner@example.test"));

      assertNull(thread.current.get());
      assertEquals(0, thread.adminDepth.get());
    }
  }

  /**
   * ETP-5548 semantics are unchanged by the scoping: another account's productive and this
   * account's demo do not count; only this account's productive does.
   */
  @Test
  public void productiveNameCheckCountsOnlyThisAccountsProductive() throws Exception {
    TenantPlanService tenantPlan = mock(TenantPlanService.class);
    servlet.tenantPlanService = tenantPlan;
    when(tenantPlan.resolvePlan("OTHER-ACCOUNT")).thenReturn(TenantPlanService.PLAN_PRODUCTIVE);
    when(tenantPlan.resolvePlan("OWN-DEMO")).thenReturn(TenantPlanService.PLAN_FREE);
    when(tenantPlan.resolvePlan("OWN-PROD")).thenReturn(TenantPlanService.PLAN_PRODUCTIVE);

    try (ContextThread thread = new ContextThread(mock(OBContext.class), 0);
        MockedStatic<EtendoGoJwtSupport> support = mockStatic(EtendoGoJwtSupport.class);
        MockedStatic<EtendoGoJwtDalHelper> dal = mockStatic(EtendoGoJwtDalHelper.class)) {
      dal.when(() -> EtendoGoJwtDalHelper.clientBelongsToAccountEmail("OWN-DEMO",
          "owner@example.test")).thenReturn(true);
      dal.when(() -> EtendoGoJwtDalHelper.clientBelongsToAccountEmail("OWN-PROD",
          "owner@example.test")).thenReturn(true);
      support.when(() -> EtendoGoJwtSupport.findClientIdsByName("Other"))
          .thenReturn(List.of("OTHER-ACCOUNT"));
      support.when(() -> EtendoGoJwtSupport.findClientIdsByName("Demo"))
          .thenReturn(List.of("OWN-DEMO"));
      support.when(() -> EtendoGoJwtSupport.findClientIdsByName("Acme"))
          .thenReturn(List.of("OTHER-ACCOUNT", "OWN-DEMO", "OWN-PROD"));

      assertFalse("another account's productive is free to reuse",
          isProductiveNameTakenByAccount("Other", "owner@example.test"));
      assertFalse("this account's demo is free to reuse",
          isProductiveNameTakenByAccount("Demo", "owner@example.test"));
      assertTrue("this account's productive is taken",
          isProductiveNameTakenByAccount("Acme", "owner@example.test"));
    }
  }

  /**
   * ETP-5742 — a canceled productive environment reads plan "free" (its closed row) but keeps its
   * name: buying a new environment under it would be refused by the company-name constraint after
   * the payment. Its lifecycle type still says productive, and that counts.
   */
  @Test
  public void productiveNameCheckCountsACanceledProductiveWhosePlanReadsFree() throws Exception {
    TenantPlanService tenantPlan = mock(TenantPlanService.class);
    servlet.tenantPlanService = tenantPlan;
    when(tenantPlan.resolvePlan(anyString())).thenReturn(TenantPlanService.PLAN_FREE);
    TenantEnvironmentLifecycleService lifecycle = mock(TenantEnvironmentLifecycleService.class);
    servlet.tenantEnvironmentLifecycleService = lifecycle;
    TenantEnvironmentLifecycleService.EnvironmentSnapshot canceled =
        mock(TenantEnvironmentLifecycleService.EnvironmentSnapshot.class);
    when(canceled.getType()).thenReturn(EnvironmentAccessPolicy.EnvironmentType.PRODUCTIVE);
    when(lifecycle.resolve("OWN-CANCELED")).thenReturn(canceled);

    try (ContextThread thread = new ContextThread(mock(OBContext.class), 0);
        MockedStatic<EtendoGoJwtSupport> support = mockStatic(EtendoGoJwtSupport.class);
        MockedStatic<EtendoGoJwtDalHelper> dal = mockStatic(EtendoGoJwtDalHelper.class)) {
      dal.when(() -> EtendoGoJwtDalHelper.clientBelongsToAccountEmail(anyString(),
          eq("owner@example.test"))).thenReturn(true);
      support.when(() -> EtendoGoJwtSupport.findClientIdsByName("Canceled"))
          .thenReturn(List.of("OWN-CANCELED"));
      support.when(() -> EtendoGoJwtSupport.findClientIdsByName("Unknown"))
          .thenReturn(List.of("OWN-UNREADABLE"));

      assertTrue("a canceled productive keeps its name",
          isProductiveNameTakenByAccount("Canceled", "owner@example.test"));
      // An unreadable lifecycle (resolve answers null) does not take a name: the check fails open.
      assertFalse("an unreadable lifecycle is not a productive environment",
          isProductiveNameTakenByAccount("Unknown", "owner@example.test"));
    }
  }

  /** The name lookup failing fails open, logged, and still hands the caller its context back. */
  @Test
  public void productiveNameCheckFailsOpenWhenTheNameLookupThrows() throws Exception {
    servlet.tenantPlanService = mock(TenantPlanService.class);
    assertNameCheckFailsOpen(support -> support.when(
        () -> EtendoGoJwtSupport.findClientIdsByName("Acme"))
        .thenThrow(new IllegalStateException("lookup failed")));
  }

  /** The plan resolution failing fails open the same way. */
  @Test
  public void productiveNameCheckFailsOpenWhenThePlanResolutionThrows() throws Exception {
    TenantPlanService tenantPlan = mock(TenantPlanService.class);
    servlet.tenantPlanService = tenantPlan;
    when(tenantPlan.resolvePlan("OWN-PROD")).thenThrow(new IllegalStateException("plan failed"));
    assertNameCheckFailsOpen(support -> support.when(
        () -> EtendoGoJwtSupport.findClientIdsByName("Acme")).thenReturn(List.of("OWN-PROD")));
  }

  private void assertNameCheckFailsOpen(
      java.util.function.Consumer<MockedStatic<EtendoGoJwtSupport>> failure) throws Exception {
    OBContext caller = mock(OBContext.class);
    LogCapture warnings = LogCapture.attachTo(EtendoGoJwtServlet.class, Level.WARN);
    try (ContextThread thread = new ContextThread(caller, 0);
        MockedStatic<EtendoGoJwtSupport> support = mockStatic(EtendoGoJwtSupport.class);
        MockedStatic<EtendoGoJwtDalHelper> dal = mockStatic(EtendoGoJwtDalHelper.class)) {
      dal.when(() -> EtendoGoJwtDalHelper.clientBelongsToAccountEmail("OWN-PROD",
          "owner@example.test")).thenReturn(true);
      failure.accept(support);

      assertFalse(isProductiveNameTakenByAccount("Acme", "owner@example.test"));

      assertSame(caller, thread.current.get());
      assertEquals(0, thread.adminDepth.get());
      assertTrue(warnings.messagesAt(Level.WARN).stream()
          .anyMatch(message -> message.startsWith(NAME_CHECK_WARNING)));
    } finally {
      warnings.detach();
    }
  }

  /** The associated-demo check answers the lifecycle service, as System, and restores the caller. */
  @Test
  public void associatedDemoCheckAnswersTheLifecycleAndHandsTheCallerItsContextBack()
      throws Exception {
    OBContext caller = mock(OBContext.class);
    TenantEnvironmentLifecycleService lifecycle = mock(TenantEnvironmentLifecycleService.class);
    servlet.tenantEnvironmentLifecycleService = lifecycle;
    when(lifecycle.isAssociatedWithProductive("DEMO-FREE")).thenReturn(false);

    try (ContextThread thread = new ContextThread(caller, 0)) {
      AtomicBoolean ranAsSystem = new AtomicBoolean();
      when(lifecycle.isAssociatedWithProductive("DEMO-SPENT")).thenAnswer(call -> {
        ranAsSystem.set(thread.isSystemWithAdminMode());
        return true;
      });

      assertTrue(isAssociatedDemo("DEMO-SPENT"));
      assertTrue("the lifecycle read must run as System with admin mode on", ranAsSystem.get());
      assertSame(caller, thread.current.get());
      assertEquals(0, thread.adminDepth.get());

      assertFalse(isAssociatedDemo("DEMO-FREE"));
      assertSame(caller, thread.current.get());
      assertEquals(0, thread.adminDepth.get());
    }
  }

  /**
   * The associated-demo check does not swallow a failure: it propagates, after the caller's
   * context and admin-mode depth have been put back.
   */
  @Test
  public void associatedDemoCheckPropagatesAFailureAfterRestoringTheCaller() throws Exception {
    OBContext caller = mock(OBContext.class);
    TenantEnvironmentLifecycleService lifecycle = mock(TenantEnvironmentLifecycleService.class);
    servlet.tenantEnvironmentLifecycleService = lifecycle;
    IllegalStateException boom = new IllegalStateException("lifecycle unavailable");
    when(lifecycle.isAssociatedWithProductive("DEMO-1")).thenThrow(boom);

    try (ContextThread thread = new ContextThread(caller, 0)) {
      IllegalStateException thrown = assertThrows(IllegalStateException.class,
          () -> isAssociatedDemo("DEMO-1"));

      assertSame(boom, thrown);
      assertSame(caller, thread.current.get());
      assertEquals(0, thread.adminDepth.get());
    }
  }

  private boolean isProductiveNameTakenByAccount(String clientName, String accountEmail)
      throws Exception {
    return invokePrivateCheck("isProductiveNameTakenByAccount",
        new Class<?>[] { String.class, String.class }, clientName, accountEmail);
  }

  private boolean isAssociatedDemo(String clientId) throws Exception {
    return invokePrivateCheck("isAssociatedDemo", new Class<?>[] { String.class }, clientId);
  }

  /** Invokes a private boolean check, rethrowing its own runtime failure unwrapped. */
  private boolean invokePrivateCheck(String name, Class<?>[] types, Object... args)
      throws Exception {
    Method check = EtendoGoJwtServlet.class.getDeclaredMethod(name, types);
    check.setAccessible(true);
    try {
      return (Boolean) check.invoke(servlet, args);
    } catch (InvocationTargetException e) {
      if (e.getCause() instanceof RuntimeException) {
        throw (RuntimeException) e.getCause();
      }
      throw e;
    }
  }

  /**
   * A stand-in for OBContext's per-thread state — the current context and the admin-mode depth —
   * so a spec can see what the code under test leaves behind for its caller.
   */
  private static final class ContextThread implements AutoCloseable {
    final AtomicReference<OBContext> current = new AtomicReference<>();
    final AtomicInteger adminDepth = new AtomicInteger();
    private final AtomicReference<List<String>> systemIds = new AtomicReference<>();
    private final OBContext system = mock(OBContext.class);
    private final MockedStatic<OBContext> statics = mockStatic(OBContext.class);
    private final int callerAdminDepth;

    ContextThread(OBContext caller, int callerAdminDepth) {
      this.callerAdminDepth = callerAdminDepth;
      current.set(caller);
      adminDepth.set(callerAdminDepth);
      statics.when(OBContext::getOBContext).thenAnswer(call -> current.get());
      statics.when(() -> OBContext.setOBContext(anyString(), anyString(), anyString(),
          anyString())).thenAnswer(call -> {
            systemIds.set(List.of(call.getArgument(0), call.getArgument(1), call.getArgument(2),
                call.getArgument(3)));
            current.set(system);
            return null;
          });
      statics.when(() -> OBContext.setOBContext(ArgumentMatchers.<OBContext>any()))
          .thenAnswer(call -> {
            current.set(call.getArgument(0));
            return null;
          });
      statics.when(() -> OBContext.setAdminMode(anyBoolean())).thenAnswer(call -> {
        adminDepth.incrementAndGet();
        return null;
      });
      statics.when(OBContext::restorePreviousMode).thenAnswer(call -> {
        adminDepth.decrementAndGet();
        return null;
      });
    }

    /**
     * Whether the current context is the one installed for user/role/client/org "0" and admin
     * mode was entered on top of the caller's depth.
     */
    boolean isSystemWithAdminMode() {
      return current.get() == system && List.of("0", "0", "0", "0").equals(systemIds.get())
          && adminDepth.get() > callerAdminDepth;
    }

    @Override
    public void close() {
      statics.close();
    }
  }

  private static final class PurchaseAttempt {
    final ResponseCapture response;
    final HostedCheckoutService checkout;

    PurchaseAttempt(ResponseCapture response, HostedCheckoutService checkout) {
      this.response = response;
      this.checkout = checkout;
    }
  }

  /**
   * Posts a purchase named {@code clientName} from a productive session, with two same-named
   * clients in the instance: one of another account, and one of this account on {@code ownPlan}.
   */
  private PurchaseAttempt postPurchaseNamed(String clientName, String ownPlan) throws Exception {
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
    HostedCheckoutService checkout = mock(HostedCheckoutService.class);
    TenantPlanService tenantPlan = mock(TenantPlanService.class);
    purchaseServlet.checkoutRequestStore = mock(CheckoutRequestStore.class);
    purchaseServlet.hostedCheckoutService = checkout;
    purchaseServlet.demoDataTransferService = mock(DemoDataTransferService.class);
    purchaseServlet.tenantPlanService = tenantPlan;
    when(tenantPlan.resolvePlan("PROD-1")).thenReturn(TenantPlanService.PLAN_PRODUCTIVE);
    when(tenantPlan.resolvePlan("OTHER-ACCOUNT")).thenReturn(TenantPlanService.PLAN_PRODUCTIVE);
    when(tenantPlan.resolvePlan("OWN-SAME-NAME")).thenReturn(ownPlan);
    when(checkout.createSession(anyString(), anyString(), anyString(), anyString(), any(),
        any(HostedCheckoutService.SessionOptions.class)))
        .thenReturn(new JSONObject().put("requestId", "purchase-1"));
    HttpServletRequest request = jsonRequest("/billing/purchases",
        "{\"clientName\":\"" + clientName + "\"}");
    when(request.getMethod()).thenReturn("POST");
    when(request.getCookies()).thenReturn(
        new Cookie[] { new Cookie(GoSessionSecurity.COOKIE_NAME, "session-cookie-token") });
    when(request.getHeader(GoSessionSecurity.CSRF_HEADER)).thenReturn("csrf-token-value-123456");
    when(request.getHeader("Origin")).thenReturn("https://app.example.test");
    when(request.getRequestURL()).thenReturn(
        new StringBuffer("https://app.example.test/sws/go/billing/purchases"));
    ResponseCapture response = mockResponse();

    try (MockedStatic<OBContext> context = mockStatic(OBContext.class);
        MockedStatic<EtendoGoJwtDalHelper> dal = mockStatic(EtendoGoJwtDalHelper.class);
        MockedStatic<EtendoGoJwtSupport> support = mockStatic(EtendoGoJwtSupport.class);
        MockedStatic<PublicUrlResolver> urls = mockStatic(PublicUrlResolver.class)) {
      dal.when(() -> EtendoGoJwtDalHelper.findActiveAccountById("account-1")).thenReturn(account);
      dal.when(() -> EtendoGoJwtDalHelper.hasOwnedEnvironmentForAccountEmail("owner@example.test"))
          .thenReturn(true);
      dal.when(() -> EtendoGoJwtDalHelper.clientBelongsToAccountEmail("PROD-1", "owner@example.test"))
          .thenReturn(true);
      dal.when(() -> EtendoGoJwtDalHelper.clientBelongsToAccountEmail("OWN-SAME-NAME",
          "owner@example.test")).thenReturn(true);
      support.when(() -> EtendoGoJwtSupport.findClientIdsByName(clientName))
          .thenReturn(List.of("OTHER-ACCOUNT", "OWN-SAME-NAME"));
      urls.when(PublicUrlResolver::resolveConfiguredAppBaseUrl).thenReturn("https://app.example.test");

      purchaseServlet.doPost(request, response.response);
    }
    return new PurchaseAttempt(response, checkout);
  }
}
