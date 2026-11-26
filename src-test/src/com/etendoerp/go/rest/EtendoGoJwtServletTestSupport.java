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

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

import java.io.BufferedReader;
import java.io.PrintWriter;
import java.io.Serializable;
import java.io.StringReader;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.Filter;
import org.apache.logging.log4j.core.Layout;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.Configurator;
import org.apache.logging.log4j.core.config.Property;
import org.junit.After;
import org.junit.Before;
import org.mockito.MockedStatic;

import com.etendoerp.go.onboarding.OnboardingForceTestModeService;
import com.etendoerp.go.payment.CheckoutRequestStore;
import com.etendoerp.go.payment.HostedCheckoutService;
import com.etendoerp.go.payment.SubscriptionModelSwitch;
import com.etendoerp.go.payment.SubscriptionService;
import com.etendoerp.go.payment.TenantEnvironmentLifecycleService;
import com.etendoerp.go.payment.TenantPlanService;
import com.etendoerp.go.schemaforge.data.Account;
import com.etendoerp.go.schemaforge.data.CheckoutRequest;
import com.etendoerp.go.schemaforge.data.Plan;
import com.etendoerp.go.schemaforge.data.Subscription;

/**
 * Shared fixture of the {@link EtendoGoJwtServlet} regression suites that extend it: one servlet
 * per test with a mocked internal-alert delivery, the DAL {@code SessionHandler} mocked for the
 * whole test (closed in {@code @After}) and the subscription-model switch ON unless a spec says
 * otherwise; plus the request/response builders, the paid-upgrade and staged paid-onboarding
 * fixtures and the small matchers the suites have in common.
 */
abstract class EtendoGoJwtServletTestSupport {

  /** ETP-5548: the names a classic-path client is created and resumed under. */
  static final String PAID_PROVISIONING_NAME =
      ProvisioningClientName.of("account-1", "purchase-1");
  static final String FREE_PROVISIONING_NAME =
      ProvisioningClientName.of("account-1", "");

  final EtendoGoJwtServlet servlet = new EtendoGoJwtServlet(mock(TransactionalAuthEmailSender.class));

  MockedStatic<org.openbravo.dal.core.SessionHandler> requestSession;
  org.openbravo.dal.core.SessionHandler unitSession;

  @Before
  public void isolateInternalAlertDelivery() {
    unitSession = mock(org.openbravo.dal.core.SessionHandler.class);
    requestSession = mockStatic(org.openbravo.dal.core.SessionHandler.class);
    requestSession.when(org.openbravo.dal.core.SessionHandler::getInstance).thenReturn(unitSession);
    servlet.internalAlertService = mock(com.etendoerp.go.schemaforge.email.InternalAlertService.class);
    // The real switch reads AD_Preference. The paid-upgrade specs below were written for the
    // subscription model, so it is ON unless a spec says otherwise.
    servlet.subscriptionModelSwitch = switchThatIs(true);
  }

  static SubscriptionModelSwitch switchThatIs(boolean on) {
    SubscriptionModelSwitch modelSwitch = mock(SubscriptionModelSwitch.class);
    when(modelSwitch.isOn()).thenReturn(on);
    return modelSwitch;
  }

  @After
  public void closeUnitSession() {
    requestSession.close();
  }

  /** A productive-origin purchase carries no demo source and transfers nothing. */
  static boolean selectsNoDemo(HostedCheckoutService.SessionOptions options) {
    return options != null && options.getDemoClientId() == null
        && !options.isTransferProducts() && !options.isTransferContacts();
  }

  static String testPasswordHash(String password) throws Exception {
    byte[] salt = "0123456789abcdef".getBytes(StandardCharsets.UTF_8);
    MessageDigest digest = MessageDigest.getInstance("SHA-256");
    digest.update(salt);
    byte[] hash = digest.digest(password.getBytes(StandardCharsets.UTF_8));
    return Base64.getEncoder().encodeToString(salt) + ":"
        + Base64.getEncoder().encodeToString(hash);
  }

  static final String PAID_CLIENT_ID = "48F0981053084BC49CCEEFEC296E2A3D";
  static final String PAID_STAR_ORG_ID = "9F5511B92BD0465FA678F75278FA9C3A";
  static final String PAID_TOKEN = "chk_etp5046";

  /**
   * Wires the servlet with mocked collaborators and a checkout request that carries a plan, so the
   * subscription write has everything it needs.
   *
   * @param subscriptionOpens whether {@code openSubscription} succeeds or throws
   * @return the mocked collaborators, for verification
   */
  PaidUpgradeFixture givenPaidUpgrade(boolean subscriptionOpens) {
    PaidUpgradeFixture fixture = new PaidUpgradeFixture();
    CheckoutRequest checkoutRequest = mock(CheckoutRequest.class);
    when(checkoutRequest.getPlan()).thenReturn(mock(Plan.class));
    when(fixture.checkoutRequestStore.find(eq(PAID_TOKEN), anyString())).thenReturn(checkoutRequest);
    if (subscriptionOpens) {
      when(fixture.subscriptionService.openSubscription(anyString(), any(), any(), any(), any(),
          any()))
          .thenReturn(mock(Subscription.class));
    } else {
      when(fixture.subscriptionService.openSubscription(anyString(), any(), any(), any(), any(),
          any()))
          .thenThrow(new IllegalStateException("subscription write failed"));
    }
    servlet.checkoutRequestStore = fixture.checkoutRequestStore;
    servlet.subscriptionService = fixture.subscriptionService;
    servlet.tenantPlanService = fixture.tenantPlanService;
    servlet.onboardingForceTestModeService = fixture.forceTestModeService;
    // The lifecycle projection is a separate concern from the payment record and has its own
    // specs; stubbed to succeed so it contributes no ERROR lines to the assertions below.
    when(fixture.lifecycleService.markProductive(anyString())).thenReturn(true);
    servlet.tenantEnvironmentLifecycleService = fixture.lifecycleService;
    return fixture;
  }

  /** The mocked collaborators of one paid-upgrade spec. */
  static final class PaidUpgradeFixture {
    final CheckoutRequestStore checkoutRequestStore = mock(CheckoutRequestStore.class);
    final SubscriptionService subscriptionService = mock(SubscriptionService.class);
    final TenantPlanService tenantPlanService = mock(TenantPlanService.class);
    final OnboardingForceTestModeService forceTestModeService =
        mock(OnboardingForceTestModeService.class);
    final TenantEnvironmentLifecycleService lifecycleService =
        mock(TenantEnvironmentLifecycleService.class);
  }

  /**
   * Collects the log events of one logger so a spec can assert on them.
   *
   * <p>Log4j2's default configuration is ERROR-only in this build, so the level is pinned before
   * attaching and restored afterwards; without that a WARN would never reach an appender and a
   * spec asserting on one would pass vacuously.
   */
  static final class LogCapture extends AbstractAppender {

    final List<LogEvent> events = new ArrayList<>();
    final String loggerName;
    final Level previousLevel;

    LogCapture(String loggerName, Level previousLevel) {
      super("Etp5046ServletCapture", (Filter) null, (Layout<? extends Serializable>) null, true,
          new Property[0]);
      this.loggerName = loggerName;
      this.previousLevel = previousLevel;
    }

    static LogCapture attachTo(Class<?> type, Level level) {
      String name = type.getName();
      Level previous = LogManager.getLogger(name).getLevel();
      Configurator.setLevel(name, level);
      LogCapture appender = new LogCapture(name, previous);
      appender.start();
      ((org.apache.logging.log4j.core.Logger) LogManager.getLogger(name)).addAppender(appender);
      return appender;
    }

    void detach() {
      ((org.apache.logging.log4j.core.Logger) LogManager.getLogger(loggerName))
          .removeAppender(this);
      stop();
      Configurator.setLevel(loggerName, previousLevel);
    }

    List<String> messagesAt(Level level) {
      List<String> messages = new ArrayList<>();
      for (LogEvent event : events) {
        if (level.equals(event.getLevel())) {
          messages.add(event.getMessage().getFormattedMessage());
        }
      }
      return messages;
    }

    @Override
    public void append(LogEvent event) {
      events.add(event.toImmutable());
    }
  }

  static Account stubAuthenticatedAccount(
      MockedStatic<EtendoGoJwtDalHelper> dalMock) {
    Account account = mock(Account.class);
    when(account.getEmail()).thenReturn("user@test.com");
    // ETP-4575 — both lookups are stubbed because both are genuinely reachable after
    // this merge: the epic's newer handlers still call findActiveAccountByBearerToken
    // directly, while resolveAuthenticatedAccountContext (the unified resolver that
    // serves the cookie session AND the legacy bearer fallback) goes through
    // findActiveAccountByToken. Stubbing only one leaves whichever path the test
    // actually exercises returning null, which surfaces as a misleading 401.
    dalMock.when(() -> EtendoGoJwtDalHelper.findActiveAccountByBearerToken("valid-token"))
        .thenReturn(account);
    dalMock.when(() -> EtendoGoJwtDalHelper.findActiveAccountByToken("valid-token"))
        .thenReturn(account);
    // Platform-only endpoints (/checkout/sessions/*, billing) resolve a legacy bearer through
    // findActiveAccountByPlatformToken (ETP-5421).
    dalMock.when(() -> EtendoGoJwtDalHelper.findActiveAccountByPlatformToken("valid-token"))
        .thenReturn(account);
    return account;
  }

  static HttpServletRequest mockRequest(String pathInfo) {
    HttpServletRequest request = mock(HttpServletRequest.class);
    when(request.getPathInfo()).thenReturn(pathInfo);
    return request;
  }

  static HttpServletRequest jsonRequest(String pathInfo, String json) throws Exception {
    HttpServletRequest request = mockRequest(pathInfo);
    when(request.getContentType()).thenReturn("application/json");
    when(request.getReader()).thenReturn(new BufferedReader(new StringReader(json)));
    return request;
  }

  static ResponseCapture mockResponse() throws Exception {
    HttpServletResponse response = mock(HttpServletResponse.class);
    StringWriter body = new StringWriter();
    PrintWriter writer = new PrintWriter(body);
    ResponseCapture capture = new ResponseCapture(response, body);
    doAnswer(inv -> {
      capture.status = inv.getArgument(0);
      return null;
    }).when(response).setStatus(anyInt());
    doAnswer(inv -> {
      capture.contentType = inv.getArgument(0);
      return null;
    }).when(response).setContentType(anyString());
    doAnswer(inv -> {
      capture.encoding = inv.getArgument(0);
      return null;
    }).when(response).setCharacterEncoding(anyString());
    when(response.getWriter()).thenReturn(writer);
    return capture;
  }

  static final class ResponseCapture {
    final HttpServletResponse response;
    final StringWriter body;
    int status;
    String contentType;
    String encoding;

    ResponseCapture(HttpServletResponse response, StringWriter body) {
      this.response = response;
      this.body = body;
    }

    String body() {
      return body.toString();
    }
  }
}
