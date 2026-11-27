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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.BufferedReader;
import java.io.StringReader;
import java.util.Date;

import javax.servlet.http.HttpServletRequest;

import org.codehaus.jettison.json.JSONObject;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.openbravo.dal.core.OBContext;
import org.openbravo.model.ad.access.User;

import com.etendoerp.go.common.PublicUrlResolver;
import com.etendoerp.go.schemaforge.data.Account;

/**
 * Unit tests for {@link EtendoGoJwtServlet} on the password endpoints: the validation and
 * database-error branches of {@code POST /change-password}, {@code POST /password-reset/request}
 * and {@code POST /password-reset/confirm}, and the ETP-5115 split between enrolling a first
 * password (identity-provider accounts) and changing or resetting an existing one, including the
 * identical neutral response of every password-reset branch.
 *
 * @covers com.etendoerp.go.rest.EtendoGoJwtServlet
 */
public class EtendoGoJwtServletPasswordTest extends EtendoGoJwtServletTestSupport {

  // ===================== POST /change-password — validation branches =====================

  @Test
  public void changePasswordMissingTokenReturnsUnauthorized() throws Exception {
    ResponseCapture resp = mockResponse();
    HttpServletRequest req = jsonRequest("/change-password",
        "{\"currentPassword\":\"a\",\"newPassword\":\"b\"}");

    servlet.doPost(req, resp.response);

    assertEquals(401, resp.status);
  }

  @Test
  public void changePasswordInvalidJsonReturnsBadRequest() throws Exception {
    ResponseCapture resp = mockResponse();
    HttpServletRequest req = mockRequest("/change-password");
    when(req.getHeader("Authorization")).thenReturn("Bearer valid-token");
    when(req.getContentType()).thenReturn("application/json");
    when(req.getReader()).thenReturn(new BufferedReader(new StringReader("not json")));

    servlet.doPost(req, resp.response);

    assertEquals(400, resp.status);
  }

  @Test
  public void changePasswordMissingFieldsReturnsBadRequest() throws Exception {
    ResponseCapture resp = mockResponse();
    HttpServletRequest req = mockRequest("/change-password");
    when(req.getHeader("Authorization")).thenReturn("Bearer valid-token");
    when(req.getContentType()).thenReturn("application/json");
    when(req.getReader()).thenReturn(new BufferedReader(new StringReader(
        "{\"currentPassword\":\"a\"}")));

    servlet.doPost(req, resp.response);

    assertEquals(400, resp.status);
  }

  @Test
  public void changePasswordEmptyFieldsReturnsBadRequest() throws Exception {
    ResponseCapture resp = mockResponse();
    HttpServletRequest req = jsonRequest("/change-password",
        "{\"currentPassword\":\"\",\"newPassword\":\"\"}");
    when(req.getHeader("Authorization")).thenReturn("Bearer valid-token");

    servlet.doPost(req, resp.response);

    assertEquals(400, resp.status);
  }

  @Test
  public void changePasswordInvalidTokenReturnsUnauthorized() throws Exception {
    ResponseCapture resp = mockResponse();
    HttpServletRequest req = jsonRequest("/change-password",
        "{\"currentPassword\":\"a\",\"newPassword\":\"Str0ng!Pass1\"}");
    when(req.getHeader("Authorization")).thenReturn("Bearer bad-token");

    try (var ctxMock = mockStatic(OBContext.class);
         var dalMock = mockStatic(EtendoGoJwtDalHelper.class)) {
      dalMock.when(() -> EtendoGoJwtDalHelper.findActiveAccountByBearerToken("bad-token"))
          .thenReturn(null);

      servlet.doPost(req, resp.response);
    }

    assertEquals(401, resp.status);
  }

  @Test
  public void changePasswordDatabaseErrorReturnsServerError() throws Exception {
    ResponseCapture resp = mockResponse();
    HttpServletRequest req = jsonRequest("/change-password",
        "{\"currentPassword\":\"a\",\"newPassword\":\"Str0ng!Pass1\"}");
    when(req.getHeader("Authorization")).thenReturn("Bearer valid-token");

    try (var ctxMock = mockStatic(OBContext.class);
         var dalMock = mockStatic(EtendoGoJwtDalHelper.class)) {
      dalMock.when(() -> EtendoGoJwtDalHelper.findActiveAccountByBearerToken("valid-token"))
          .thenThrow(new RuntimeException("db down"));

      servlet.doPost(req, resp.response);
    }

    assertEquals(500, resp.status);
  }

  // ===================== POST /password-reset/request — validation branches ===========

  @Test
  public void passwordResetRequestInvalidJsonReturnsBadRequest() throws Exception {
    ResponseCapture resp = mockResponse();
    HttpServletRequest req = mockRequest("/password-reset/request");
    when(req.getContentType()).thenReturn("application/json");
    when(req.getReader()).thenReturn(new BufferedReader(new StringReader("not json")));

    servlet.doPost(req, resp.response);

    assertEquals(400, resp.status);
  }

  @Test
  public void passwordResetRequestMissingEmailReturnsBadRequest() throws Exception {
    ResponseCapture resp = mockResponse();
    HttpServletRequest req = jsonRequest("/password-reset/request", "{}");

    servlet.doPost(req, resp.response);

    assertEquals(400, resp.status);
  }

  @Test
  public void passwordResetRequestEmptyEmailReturnsBadRequest() throws Exception {
    ResponseCapture resp = mockResponse();
    HttpServletRequest req = jsonRequest("/password-reset/request", "{\"email\":\"\"}");

    servlet.doPost(req, resp.response);

    assertEquals(400, resp.status);
  }

  @Test
  public void passwordResetRequestDatabaseErrorReturnsNeutralSuccess() throws Exception {
    ResponseCapture resp = mockResponse();
    HttpServletRequest req = jsonRequest("/password-reset/request",
        "{\"email\":\"user@test.com\"}");

    try (var ctxMock = mockStatic(OBContext.class);
         var dalMock = mockStatic(EtendoGoJwtDalHelper.class)) {
      dalMock.when(() -> EtendoGoJwtDalHelper.findActiveAccountByEmail("user@test.com"))
          .thenThrow(new RuntimeException("db down"));

      servlet.doPost(req, resp.response);
    }

    assertEquals(200, resp.status);
    assertEquals("success", new JSONObject(resp.body()).getString("status"));
  }

  /**
   * ETP-5115 / AUTH-05. This test used to assert the opposite: that an account with no local
   * password got no token and no email. That was the bug, pinned as if it were the contract — every
   * SSO-created account has no local password by design, so the one flow that exists to recover
   * access was a silent no-op for exactly the people who could not get in. It now issues the same
   * token and mails the same link, through the set-password contract rather than reset-password,
   * because the account is being asked to create a first password and not to restore a forgotten
   * one. Rewritten rather than deleted, so the regression cannot come back unnoticed.
   */
  @Test
  public void passwordResetRequestKnownEmailWithoutLocalPasswordIssuesSetPasswordLink()
      throws Exception {
    ResponseCapture resp = mockResponse();
    HttpServletRequest req = jsonRequest("/password-reset/request",
        "{\"email\":\"sso@test.com\"}");

    Account account = mock(Account.class);
    TransactionalAuthEmailSender emailSender = mock(TransactionalAuthEmailSender.class);
    when(emailSender.sendSetPassword(any(), anyString(), anyString(), any())).thenReturn(true);
    EtendoGoJwtServlet servletWithEmailSender = new EtendoGoJwtServlet(emailSender);

    // The link builder is pinned rather than left to read the ambient app base URL, same reason as
    // registerSuccessCreatesAccount: without it the outcome depends on whether the machine running
    // the suite has etendo.go.app.baseUrl set, and a null link makes the send be skipped entirely.
    // Pinning PublicUrlResolver instead would not work — it would also stub appendPath, which the
    // builder uses, so the link would come back null anyway.
    try (var ctxMock = mockStatic(OBContext.class);
         var dalMock = mockStatic(EtendoGoJwtDalHelper.class);
         var linkMock = mockStatic(EtendoGoAuthLinkBuilder.class)) {
      linkMock.when(() -> EtendoGoAuthLinkBuilder.resetPasswordLink(anyString(), any()))
          .thenReturn("https://go.example.com/reset-password?token=t");
      dalMock.when(() -> EtendoGoJwtDalHelper.findActiveAccountByEmail("sso@test.com"))
          .thenReturn(account);
      dalMock.when(() -> EtendoGoJwtDalHelper.hasLocalPassword(account)).thenReturn(false);

      servletWithEmailSender.doPost(req, resp.response);

      dalMock.verify(() -> EtendoGoJwtDalHelper.storePasswordResetToken(
          any(Account.class), anyString(), any(Date.class)));
      verify(emailSender).sendSetPassword(eq(account), anyString(), anyString(), any());
      verify(emailSender, never()).sendPasswordReset(any(), anyString(), anyString(), any());
    }
    // The response is the same neutral body an unknown address gets. Varying it by account state
    // would tell an anonymous prober both that the address exists and which provider it uses.
    assertEquals(200, resp.status);
    assertEquals("success", new JSONObject(resp.body()).getString("status"));
  }

  // ===================== POST /password-reset/confirm — validation branches ===========

  @Test
  public void passwordResetConfirmInvalidJsonReturnsBadRequest() throws Exception {
    ResponseCapture resp = mockResponse();
    HttpServletRequest req = mockRequest("/password-reset/confirm");
    when(req.getContentType()).thenReturn("application/json");
    when(req.getReader()).thenReturn(new BufferedReader(new StringReader("not json")));

    servlet.doPost(req, resp.response);

    assertEquals(400, resp.status);
  }

  @Test
  public void passwordResetConfirmMissingFieldsReturnsBadRequest() throws Exception {
    ResponseCapture resp = mockResponse();
    HttpServletRequest req = jsonRequest("/password-reset/confirm", "{\"token\":\"t\"}");

    servlet.doPost(req, resp.response);

    assertEquals(400, resp.status);
  }

  @Test
  public void passwordResetConfirmEmptyFieldsReturnsBadRequest() throws Exception {
    ResponseCapture resp = mockResponse();
    HttpServletRequest req = jsonRequest("/password-reset/confirm",
        "{\"token\":\"\",\"password\":\"\"}");

    servlet.doPost(req, resp.response);

    assertEquals(400, resp.status);
  }

  @Test
  public void passwordResetConfirmDatabaseErrorReturnsServerError() throws Exception {
    ResponseCapture resp = mockResponse();
    HttpServletRequest req = jsonRequest("/password-reset/confirm",
        "{\"token\":\"valid-token\",\"password\":\"Str0ng!Pass1\"}");

    try (var ctxMock = mockStatic(OBContext.class);
         var dalMock = mockStatic(EtendoGoJwtDalHelper.class)) {
      dalMock.when(() -> EtendoGoJwtDalHelper.findActiveAccountByResetTokenHash(
          anyString(), any(Date.class))).thenThrow(new RuntimeException("db down"));

      servlet.doPost(req, resp.response);
    }

    assertEquals(500, resp.status);
  }

  // ===================== POST /change-password — ETP-5115 enrolment =====================
  //
  // An account created through an identity provider has passwordHash null by design and could
  // not give itself a local password from inside the app. currentPassword used to be read as a
  // required field while parsing the body, so such a caller — who has nothing to put there — was
  // rejected for missing credentials before anything ever looked at the account, which made the
  // NO_LOCAL_PASSWORD branch unreachable by the very accounts it described. It is now read with
  // optString and whether it is required is decided once the account is known.

  /**
   * ETP-5115. This test previously asserted the opposite — that an account with no local password
   * got 400 NO_LOCAL_PASSWORD. That branch is gone: such an account is enrolling, and the bearer
   * token already proves who is asking, so nothing is verified. Rewritten rather than deleted so
   * the dead end cannot come back unnoticed. Note the old assertion had also stopped proving its
   * own point — its newPassword was "b", which the strength policy rejects before the account is
   * ever looked up, so the 400 it saw no longer came from the branch named in the method.
   */
  @Test
  public void changePasswordWithoutLocalPasswordEnrolsAndMailsPasswordAdded() throws Exception {
    ResponseCapture resp = mockResponse();
    HttpServletRequest req = jsonRequest("/change-password",
        "{\"newPassword\":\"Str0ng!Pass1\"}");
    when(req.getHeader("Authorization")).thenReturn("Bearer valid-token");

    Account account = mock(Account.class);
    when(account.getId()).thenReturn("acct-sso");
    when(account.getEmail()).thenReturn("sso@test.com");
    when(account.getName()).thenReturn("SSO User");
    TransactionalAuthEmailSender emailSender = mock(TransactionalAuthEmailSender.class);
    EtendoGoJwtServlet servletWithEmailSender = new EtendoGoJwtServlet(emailSender);
    ArgumentCaptor<String> sessionToken = ArgumentCaptor.forClass(String.class);

    try (var ctxMock = mockStatic(OBContext.class);
         var dalMock = mockStatic(EtendoGoJwtDalHelper.class)) {
      dalMock.when(() -> EtendoGoJwtDalHelper.findActiveAccountByToken("valid-token"))
          .thenReturn(account);
      // ETP-4575: resolveAuthenticatedAccountContext resolves the legacy bearer through the
      // WIDE lookup, so stubbing only findActiveAccountByToken leaves it null and the handler
      // answers 401 before any of the assertions below can be reached.
      dalMock.when(() -> EtendoGoJwtDalHelper.findActiveAccountByBearerToken("valid-token"))
          .thenReturn(account);
      dalMock.when(() -> EtendoGoJwtDalHelper.hasLocalPassword(account)).thenReturn(false);

      servletWithEmailSender.doPost(req, resp.response);

      dalMock.verify(() -> EtendoGoJwtDalHelper.changePassword(
          eq(account), anyString(), sessionToken.capture(), any(Date.class)));
    }

    assertEquals(200, resp.status);
    JSONObject json = new JSONObject(resp.body());
    assertEquals("success", json.getString("status"));
    // The session token handed to the DAL is freshly generated and is the one returned to the
    // caller — enrolling must rotate the session exactly as changing does.
    assertEquals(json.getString("token"), sessionToken.getValue());
    assertTrue(sessionToken.getValue().matches("[0-9a-f]{32}"));
    // "Your password was changed" is alarming and wrong for somebody who just created a first one.
    verify(emailSender).sendPasswordAdded(account);
    verify(emailSender, never()).sendPasswordChanged(any());
    verify(emailSender, never()).sendPasswordChanged(any(), anyString());
  }

  @Test
  public void changePasswordWithLocalPasswordMailsPasswordChangedAndRotatesSession()
      throws Exception {
    ResponseCapture resp = mockResponse();
    HttpServletRequest req = jsonRequest("/change-password",
        "{\"currentPassword\":\"secret\",\"newPassword\":\"Str0ng!Pass1\"}");
    when(req.getHeader("Authorization")).thenReturn("Bearer valid-token");

    Account account = mock(Account.class);
    when(account.getId()).thenReturn("acct-1");
    when(account.getEmail()).thenReturn("user@test.com");
    when(account.getName()).thenReturn("User Test");
    when(account.getPasswordHash()).thenReturn(testPasswordHash("secret"));
    TransactionalAuthEmailSender emailSender = mock(TransactionalAuthEmailSender.class);
    EtendoGoJwtServlet servletWithEmailSender = new EtendoGoJwtServlet(emailSender);
    ArgumentCaptor<String> sessionToken = ArgumentCaptor.forClass(String.class);

    try (var ctxMock = mockStatic(OBContext.class);
         var dalMock = mockStatic(EtendoGoJwtDalHelper.class)) {
      dalMock.when(() -> EtendoGoJwtDalHelper.findActiveAccountByToken("valid-token"))
          .thenReturn(account);
      // ETP-4575: resolveAuthenticatedAccountContext resolves the legacy bearer through the
      // WIDE lookup, so stubbing only findActiveAccountByToken leaves it null and the handler
      // answers 401 before any of the assertions below can be reached.
      dalMock.when(() -> EtendoGoJwtDalHelper.findActiveAccountByBearerToken("valid-token"))
          .thenReturn(account);
      dalMock.when(() -> EtendoGoJwtDalHelper.hasLocalPassword(account)).thenReturn(true);

      servletWithEmailSender.doPost(req, resp.response);

      dalMock.verify(() -> EtendoGoJwtDalHelper.changePassword(
          eq(account), anyString(), sessionToken.capture(), any(Date.class)));
    }

    assertEquals(200, resp.status);
    JSONObject json = new JSONObject(resp.body());
    assertEquals(json.getString("token"), sessionToken.getValue());
    assertTrue(sessionToken.getValue().matches("[0-9a-f]{32}"));
    verify(emailSender).sendPasswordChanged(account);
    verify(emailSender, never()).sendPasswordAdded(any());
  }

  /**
   * Making currentPassword optional while parsing must not make it optional in fact: an account
   * that has a password still has to supply one, and still gets the same error code it always did.
   */
  @Test
  public void changePasswordWithLocalPasswordAndNoCurrentPasswordReturnsMissingCredentials()
      throws Exception {
    ResponseCapture resp = mockResponse();
    HttpServletRequest req = jsonRequest("/change-password",
        "{\"newPassword\":\"Str0ng!Pass1\"}");
    when(req.getHeader("Authorization")).thenReturn("Bearer valid-token");

    Account account = mock(Account.class);
    TransactionalAuthEmailSender emailSender = mock(TransactionalAuthEmailSender.class);
    EtendoGoJwtServlet servletWithEmailSender = new EtendoGoJwtServlet(emailSender);

    try (var ctxMock = mockStatic(OBContext.class);
         var dalMock = mockStatic(EtendoGoJwtDalHelper.class)) {
      dalMock.when(() -> EtendoGoJwtDalHelper.findActiveAccountByToken("valid-token"))
          .thenReturn(account);
      // ETP-4575: resolveAuthenticatedAccountContext resolves the legacy bearer through the
      // WIDE lookup, so stubbing only findActiveAccountByToken leaves it null and the handler
      // answers 401 before any of the assertions below can be reached.
      dalMock.when(() -> EtendoGoJwtDalHelper.findActiveAccountByBearerToken("valid-token"))
          .thenReturn(account);
      dalMock.when(() -> EtendoGoJwtDalHelper.hasLocalPassword(account)).thenReturn(true);

      servletWithEmailSender.doPost(req, resp.response);

      dalMock.verify(() -> EtendoGoJwtDalHelper.changePassword(
          any(), anyString(), anyString(), any(Date.class)), never());
    }

    assertEquals(400, resp.status);
    assertEquals("CHANGE_PASSWORD_MISSING_CREDENTIALS",
        new JSONObject(resp.body()).getJSONObject("error").getString("code"));
    verify(emailSender, never()).sendPasswordAdded(any());
    verify(emailSender, never()).sendPasswordChanged(any());
  }

  @Test
  public void changePasswordWithLocalPasswordAndWrongCurrentPasswordReturnsUnauthorized()
      throws Exception {
    ResponseCapture resp = mockResponse();
    HttpServletRequest req = jsonRequest("/change-password",
        "{\"currentPassword\":\"wrong\",\"newPassword\":\"Str0ng!Pass1\"}");
    when(req.getHeader("Authorization")).thenReturn("Bearer valid-token");

    Account account = mock(Account.class);
    when(account.getPasswordHash()).thenReturn(testPasswordHash("secret"));
    TransactionalAuthEmailSender emailSender = mock(TransactionalAuthEmailSender.class);
    EtendoGoJwtServlet servletWithEmailSender = new EtendoGoJwtServlet(emailSender);

    try (var ctxMock = mockStatic(OBContext.class);
         var dalMock = mockStatic(EtendoGoJwtDalHelper.class)) {
      dalMock.when(() -> EtendoGoJwtDalHelper.findActiveAccountByToken("valid-token"))
          .thenReturn(account);
      // ETP-4575: resolveAuthenticatedAccountContext resolves the legacy bearer through the
      // WIDE lookup, so stubbing only findActiveAccountByToken leaves it null and the handler
      // answers 401 before any of the assertions below can be reached.
      dalMock.when(() -> EtendoGoJwtDalHelper.findActiveAccountByBearerToken("valid-token"))
          .thenReturn(account);
      dalMock.when(() -> EtendoGoJwtDalHelper.hasLocalPassword(account)).thenReturn(true);

      servletWithEmailSender.doPost(req, resp.response);

      dalMock.verify(() -> EtendoGoJwtDalHelper.changePassword(
          any(), anyString(), anyString(), any(Date.class)), never());
    }

    assertEquals(401, resp.status);
    assertEquals("INVALID_CURRENT_PASSWORD",
        new JSONObject(resp.body()).getJSONObject("error").getString("code"));
    verify(emailSender, never()).sendPasswordAdded(any());
    verify(emailSender, never()).sendPasswordChanged(any());
  }

  /**
   * The strength policy still runs before the account is resolved, so a weak newPassword costs no
   * database lookup regardless of whether the caller is enrolling.
   */
  @Test
  public void changePasswordWeakNewPasswordIsRejectedBeforeAnyAccountLookup() throws Exception {
    ResponseCapture resp = mockResponse();
    HttpServletRequest req = jsonRequest("/change-password", "{\"newPassword\":\"weak\"}");
    when(req.getHeader("Authorization")).thenReturn("Bearer valid-token");

    try (var ctxMock = mockStatic(OBContext.class);
         var dalMock = mockStatic(EtendoGoJwtDalHelper.class)) {
      servlet.doPost(req, resp.response);

      dalMock.verify(() -> EtendoGoJwtDalHelper.findActiveAccountByToken(anyString()), never());
    }

    assertEquals(400, resp.status);
  }

  /**
   * The bearer token is what proves identity in the enrolment branch, so an invalid one must still
   * be refused even now that the request carries no currentPassword to reject it on instead.
   */
  @Test
  public void changePasswordInvalidTokenStillUnauthorizedWithoutCurrentPassword()
      throws Exception {
    ResponseCapture resp = mockResponse();
    HttpServletRequest req = jsonRequest("/change-password",
        "{\"newPassword\":\"Str0ng!Pass1\"}");
    when(req.getHeader("Authorization")).thenReturn("Bearer bad-token");

    try (var ctxMock = mockStatic(OBContext.class);
         var dalMock = mockStatic(EtendoGoJwtDalHelper.class)) {
      dalMock.when(() -> EtendoGoJwtDalHelper.findActiveAccountByToken("bad-token"))
          .thenReturn(null);

      servlet.doPost(req, resp.response);

      dalMock.verify(() -> EtendoGoJwtDalHelper.changePassword(
          any(), anyString(), anyString(), any(Date.class)), never());
    }

    assertEquals(401, resp.status);
  }

  // ===================== POST /password-reset/request — ETP-5115 branch split ==========

  /**
   * The sibling of {@link #passwordResetRequestKnownEmailWithoutLocalPasswordIssuesSetPasswordLink}.
   * Widening the gate to let SSO accounts through must not have swapped the copy for everyone else:
   * an account that does have a password is restoring one it forgot, not creating a first.
   */
  @Test
  public void passwordResetRequestKnownEmailWithLocalPasswordIssuesResetPasswordLink()
      throws Exception {
    ResponseCapture resp = mockResponse();
    HttpServletRequest req = jsonRequest("/password-reset/request",
        "{\"email\":\"user@test.com\"}");

    Account account = mock(Account.class);
    when(account.getEmail()).thenReturn("user@test.com");
    TransactionalAuthEmailSender emailSender = mock(TransactionalAuthEmailSender.class);
    when(emailSender.sendPasswordReset(any(), anyString(), anyString(), any())).thenReturn(true);
    EtendoGoJwtServlet servletWithEmailSender = new EtendoGoJwtServlet(emailSender);

    try (var ctxMock = mockStatic(OBContext.class);
         var dalMock = mockStatic(EtendoGoJwtDalHelper.class);
         var linkMock = mockStatic(EtendoGoAuthLinkBuilder.class)) {
      linkMock.when(() -> EtendoGoAuthLinkBuilder.resetPasswordLink(anyString(), any()))
          .thenReturn("https://go.example.com/reset-password?token=t");
      dalMock.when(() -> EtendoGoJwtDalHelper.findActiveAccountByEmail("user@test.com"))
          .thenReturn(account);
      dalMock.when(() -> EtendoGoJwtDalHelper.hasLocalPassword(account)).thenReturn(true);

      servletWithEmailSender.doPost(req, resp.response);

      dalMock.verify(() -> EtendoGoJwtDalHelper.storePasswordResetToken(
          any(Account.class), anyString(), any(Date.class)));
      verify(emailSender).sendPasswordReset(eq(account), anyString(), anyString(), any());
      verify(emailSender, never()).sendSetPassword(any(), anyString(), anyString(), any());
    }

    assertEquals(200, resp.status);
    assertEquals("success", new JSONObject(resp.body()).getString("status"));
  }

  /**
   * The anti-enumeration guard, asserted explicitly rather than left implied by three separate
   * tests each checking only its own status. Varying the answer by account state would confirm to
   * an anonymous prober both that an address is registered and which identity provider it uses, so
   * the three branches — no account, reset, enrol — must produce a byte-identical body. The
   * disclosure belongs in the email, which only the owner of the mailbox reads.
   */
  @Test
  public void passwordResetRequestNeutralResponseIsIdenticalAcrossAllThreeBranches()
      throws Exception {
    String unknown = passwordResetRequestBody(null);
    String reset = passwordResetRequestBody(Boolean.TRUE);
    String enrol = passwordResetRequestBody(Boolean.FALSE);

    assertEquals(unknown, reset);
    assertEquals(unknown, enrol);
  }

  /**
   * Drives one password-reset request and returns the raw response body.
   *
   * @param hasLocalPassword null for an address with no account at all, otherwise whether the
   *     account found already has a local password
   * @return the exact bytes written back to the caller
   */
  private static String passwordResetRequestBody(Boolean hasLocalPassword) throws Exception {
    ResponseCapture resp = mockResponse();
    HttpServletRequest req = jsonRequest("/password-reset/request",
        "{\"email\":\"probe@test.com\"}");

    TransactionalAuthEmailSender emailSender = mock(TransactionalAuthEmailSender.class);
    when(emailSender.sendPasswordReset(any(), anyString(), anyString(), any())).thenReturn(true);
    when(emailSender.sendSetPassword(any(), anyString(), anyString(), any())).thenReturn(true);
    EtendoGoJwtServlet servletWithEmailSender = new EtendoGoJwtServlet(emailSender);
    // Hoisted out of the when(...) below on purpose: a helper that stubs a mock cannot be called
    // inline inside a stubbing argument without tripping Mockito's UnfinishedStubbingException.
    final Account account = hasLocalPassword == null ? null : mock(Account.class);
    if (account != null) {
      when(account.getEmail()).thenReturn("probe@test.com");
    }

    try (var ctxMock = mockStatic(OBContext.class);
         var dalMock = mockStatic(EtendoGoJwtDalHelper.class);
         var linkMock = mockStatic(EtendoGoAuthLinkBuilder.class)) {
      linkMock.when(() -> EtendoGoAuthLinkBuilder.resetPasswordLink(anyString(), any()))
          .thenReturn("https://go.example.com/reset-password?token=t");
      dalMock.when(() -> EtendoGoJwtDalHelper.findActiveAccountByEmail("probe@test.com"))
          .thenReturn(account);
      if (account != null) {
        dalMock.when(() -> EtendoGoJwtDalHelper.hasLocalPassword(account))
            .thenReturn(hasLocalPassword);
      }

      servletWithEmailSender.doPost(req, resp.response);
    }

    assertEquals(200, resp.status);
    return resp.body();
  }
}
