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
 * All portions are Copyright (C) 2026 FUTIT SERVICES, S.L
 * All Rights Reserved.
 * Contributor(s): Futit Services S.L.
 * *************************************************************************
 */
package com.etendoerp.go.supportaccess;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

import org.codehaus.jettison.json.JSONObject;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.ad.access.Role;
import org.openbravo.model.ad.access.User;

import com.etendoerp.go.schemaforge.data.Account;
import com.etendoerp.go.session.GoSessionRecord;

/**
 * Unit tests for {@link SupportAccessGuard} (ETP-5351): pure checks, no database.
 *
 * @covers com.etendoerp.go.supportaccess.SupportAccessGuard
 */
class SupportAccessGuardTest {

  private static final String CLIENT_A = "4028E6C72959682B01295A070852010D";
  private static final String CLIENT_B = "23C59575B9CF467C9620760EB255B389";

  @Test
  void supportUserIdIsDeterministicUpperHexAndPerClient() {
    String id = SupportAccessGuard.supportUserIdFor(CLIENT_A);
    assertEquals(id, SupportAccessGuard.supportUserIdFor(CLIENT_A));
    assertEquals(32, id.length());
    assertTrue(id.matches("[0-9A-F]{32}"));
    assertNotEquals(id, SupportAccessGuard.supportUserIdFor(CLIENT_B));
  }

  @Test
  void supportUserIdRequiresAClient() {
    assertThrows(IllegalArgumentException.class, () -> SupportAccessGuard.supportUserIdFor(" "));
  }

  @Test
  void supportUserIsRecognisedOnlyInItsOwnClient() {
    String supportUser = SupportAccessGuard.supportUserIdFor(CLIENT_A);
    assertTrue(SupportAccessGuard.isSupportUser(supportUser, CLIENT_A));
    assertFalse(SupportAccessGuard.isSupportUser(supportUser, CLIENT_B));
    assertFalse(SupportAccessGuard.isSupportUser("100", CLIENT_A));
    assertFalse(SupportAccessGuard.isSupportUser(null, CLIENT_A));
    assertFalse(SupportAccessGuard.isSupportUser(supportUser, null));
  }

  @Test
  void supportAccountIsMatchedByIdOnly() {
    Account support = mock(Account.class);
    when(support.getId()).thenReturn(SupportAccessGuard.SUPPORT_ACCOUNT_ID);
    Account sameEmailOtherId = mock(Account.class);
    when(sameEmailOtherId.getId()).thenReturn("ACC1");
    when(sameEmailOtherId.getEmail()).thenReturn(SupportAccessGuard.SUPPORT_ACCOUNT_EMAIL);

    assertTrue(SupportAccessGuard.isSupportAccount(support));
    assertFalse(SupportAccessGuard.isSupportAccount(sameEmailOtherId),
        "matching by email would hand the support role to whoever registered it first");
    assertFalse(SupportAccessGuard.isSupportAccount(null));
  }

  @Test
  void supportSessionIsMarkedOrOwnedByTheTechnicalAccount() {
    GoSessionRecord marked = new GoSessionRecord();
    marked.setAccountId("ACC1");
    marked.setSupportAccessId("ACCESS1");
    GoSessionRecord owned = new GoSessionRecord();
    owned.setAccountId(SupportAccessGuard.SUPPORT_ACCOUNT_ID);
    GoSessionRecord ordinary = new GoSessionRecord();
    ordinary.setAccountId("ACC1");

    assertTrue(SupportAccessGuard.isSupportSession(marked));
    assertTrue(SupportAccessGuard.isSupportSession(owned));
    assertFalse(SupportAccessGuard.isSupportSession(ordinary));
    assertFalse(SupportAccessGuard.isSupportSession(null));
  }

  @Test
  void supportEmailIgnoresCaseAndBlanks() {
    assertTrue(SupportAccessGuard.isSupportEmail(" Soporte@Etendo-Go.INVALID "));
    assertFalse(SupportAccessGuard.isSupportEmail("soporte@etendo-go.com"));
    assertFalse(SupportAccessGuard.isSupportEmail(null));
  }

  @Test
  void accountSurfaceKeepsReadsButNotTheCredentialMintingOnes() {
    assertTrue(SupportAccessGuard.isAllowedOnAccountSurface("GET", "/me"));
    assertTrue(SupportAccessGuard.isAllowedOnAccountSurface("GET", "/environments/"));
    assertFalse(SupportAccessGuard.isAllowedOnAccountSurface("GET", "/login"));
    assertFalse(SupportAccessGuard.isAllowedOnAccountSurface("GET", "/login/"));
  }

  @Test
  void accountSurfaceRefusesEveryWriteButTheAllowlist() {
    assertTrue(SupportAccessGuard.isAllowedOnAccountSurface("POST", "/onboarding/first-steps"));
    for (String route : new String[] { "/change-password", "/auth-methods/remove",
        "/company-invitations", "/company-invitations/accept", "/onboarding", "/onboarding/draft",
        "/billing/purchases", "/checkout/sessions", "/verify-email/resend",
        "/demo-data-transfer/retry" }) {
      assertFalse(SupportAccessGuard.isAllowedOnAccountSurface("POST", route), route);
    }
    assertFalse(SupportAccessGuard.isAllowedOnAccountSurface("DELETE", "/whatever"));
  }

  /**
   * ETP-5351 - edge cases of {@link SupportAccessGuard#changesAccessFields}: null/blank handling,
   * reference-shaped values and the fail-closed paths. No database.
   */
  @Nested
  class ChangesAccessFields {

    private static final String TARGET = "TARGET00000000000000000000000001";

    private static void withUser(String email, String username, String roleId, Runnable body) {
      User stored = mock(User.class);
      when(stored.getEmail()).thenReturn(email);
      when(stored.getUsername()).thenReturn(username);
      if (roleId != null) {
        Role role = mock(Role.class);
        when(role.getId()).thenReturn(roleId);
        when(stored.getDefaultRole()).thenReturn(role);
      }
      OBDal obDal = mock(OBDal.class);
      when(obDal.get(User.class, TARGET)).thenReturn(stored);
      try (MockedStatic<OBContext> ctx = mockStatic(OBContext.class);
          MockedStatic<OBDal> dal = mockStatic(OBDal.class)) {
        dal.when(OBDal::getInstance).thenReturn(obDal);
        body.run();
      }
    }

    @Test
    void nullUserIdWithAnAccessFieldFailsClosed() throws Exception {
      JSONObject body = new JSONObject().put("email", "a@b.c");
      withUser("a@b.c", "u", "R1",
          () -> assertTrue(SupportAccessGuard.changesAccessFields(body, null)));
    }

    @Test
    void emptyBodyIsNotAChange() {
      withUser("a@b.c", "u", "R1",
          () -> assertFalse(SupportAccessGuard.changesAccessFields(new JSONObject(), TARGET)));
    }

    @Test
    void jsonNullEmailOverAStoredEmailIsAChange() throws Exception {
      JSONObject body = new JSONObject().put("email", JSONObject.NULL);
      withUser("a@b.c", "u", "R1",
          () -> assertTrue(SupportAccessGuard.changesAccessFields(body, TARGET)));
    }

    @Test
    void jsonNullEmailOverANullStoredEmailIsNotAChange() throws Exception {
      JSONObject body = new JSONObject().put("email", JSONObject.NULL);
      withUser(null, "u", "R1",
          () -> assertFalse(SupportAccessGuard.changesAccessFields(body, TARGET)));
    }

    @Test
    void defaultRoleAsReferenceObjectWithSameIdIsNotAChange() throws Exception {
      JSONObject body = new JSONObject().put("defaultRole", new JSONObject().put("id", "R1"));
      withUser("a@b.c", "u", "R1",
          () -> assertFalse(SupportAccessGuard.changesAccessFields(body, TARGET)));
    }

    @Test
    void defaultRoleAsReferenceObjectWithOtherIdIsAChange() throws Exception {
      JSONObject body = new JSONObject().put("defaultRole", new JSONObject().put("id", "R2"));
      withUser("a@b.c", "u", "R1",
          () -> assertTrue(SupportAccessGuard.changesAccessFields(body, TARGET)));
    }

    @Test
    void defaultRoleIsCaseSensitive() throws Exception {
      JSONObject body = new JSONObject().put("defaultRole", "r1");
      withUser("a@b.c", "u", "R1",
          () -> assertTrue(SupportAccessGuard.changesAccessFields(body, TARGET)));
    }

    @Test
    void settingADefaultRoleWhenNoneIsStoredIsAChange() throws Exception {
      JSONObject body = new JSONObject().put("defaultRole", "R1");
      withUser("a@b.c", "u", null,
          () -> assertTrue(SupportAccessGuard.changesAccessFields(body, TARGET)));
    }

    @Test
    void blankDefaultRoleWhenNoneIsStoredIsNotAChange() throws Exception {
      JSONObject body = new JSONObject().put("defaultRole", "  ");
      withUser("a@b.c", "u", null,
          () -> assertFalse(SupportAccessGuard.changesAccessFields(body, TARGET)));
    }

    @Test
    void nonBlankPasswordIsAChangeEvenForAnUnknownOrStoredUser() throws Exception {
      JSONObject body = new JSONObject().put("password", "known");
      withUser("a@b.c", "u", "R1",
          () -> assertTrue(SupportAccessGuard.changesAccessFields(body, TARGET)));
    }

    @Test
    void blankPasswordAndPhoneOnlyAreNotAChange() throws Exception {
      JSONObject body = new JSONObject().put("password", "").put("phone", "1");
      withUser("a@b.c", "u", "R1",
          () -> assertFalse(SupportAccessGuard.changesAccessFields(body, TARGET)));
    }

    @Test
    void flagAgainstANullStoredValueDiffersFromTrue() throws Exception {
      JSONObject body = new JSONObject().put("locked", true);
      withUser("a@b.c", "u", "R1",
          () -> assertTrue(SupportAccessGuard.changesAccessFields(body, TARGET)));
    }
  }
}
