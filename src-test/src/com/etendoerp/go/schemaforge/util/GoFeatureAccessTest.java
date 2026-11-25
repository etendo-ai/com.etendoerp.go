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
 * All portions are Copyright © 2021–2026 FUTIT SERVICES, S.L
 * All Rights Reserved.
 * Contributor(s): Futit Services S.L.
 * *************************************************************************
 */

package com.etendoerp.go.schemaforge.util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

import java.util.List;

import org.hibernate.Session;
import org.hibernate.query.NativeQuery;
import org.junit.Test;
import org.mockito.MockedStatic;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.ad.access.Role;

/**
 * The shared GO feature access check (ETP-4802): GO Access row first, legacy classic grant when the
 * role has none, no permissive fallback.
 *
 * @covers com.etendoerp.go.schemaforge.util.GoFeatureAccess
 * @covers com.etendoerp.go.schemaforge.util.GoFeature
 */
public class GoFeatureAccessTest {

  private static final String ROLE_ID = "ROLE1";

  @Test
  public void nullRoleIsDenied() {
    assertFalse(GoFeatureAccess.hasAccess(null, GoFeature.FISCAL_MODELS, "GET"));
  }

  @Test
  public void adminPassesWithoutAnyGrant() {
    try (Env env = new Env(null)) {
      env.helper.when(() -> NeoAccessHelper.isAdminOrClientAdmin(env.role)).thenReturn(true);

      assertTrue(GoFeatureAccess.hasAccess(env.role, GoFeature.SMART_SCAN, "POST"));
    }
  }

  @Test
  public void fullRowAllowsReadAndWriteWithoutLegacyGrant() {
    try (Env env = new Env("Y")) {
      assertTrue(GoFeatureAccess.hasAccess(env.role, GoFeature.FISCAL_MODELS, "GET"));
      assertTrue(GoFeatureAccess.hasAccess(env.role, GoFeature.FISCAL_MODELS, "POST"));
      assertEquals(RoleAccessMatrix.FULL, GoFeatureAccess.grantedTier(env.role, GoFeature.FISCAL_MODELS));
    }
  }

  @Test
  public void readOnlyRowAllowsReadAndDeniesWriteEvenWhenLegacyGrantIsFull() {
    try (Env env = new Env("N")) {
      env.helper.when(() -> NeoAccessHelper.hasWindowAccess(any(Role.class), anyString(), anyString()))
          .thenReturn(true);

      assertTrue(GoFeatureAccess.hasAccess(env.role, GoFeature.FISCAL_MODELS, "GET"));
      assertFalse(GoFeatureAccess.hasAccess(env.role, GoFeature.FISCAL_MODELS, "DELETE"));
      assertEquals(RoleAccessMatrix.READ_ONLY,
          GoFeatureAccess.grantedTier(env.role, GoFeature.FISCAL_MODELS));
    }
  }

  @Test
  public void withoutRowTheLegacyWindowGrantDecides() {
    try (Env env = new Env(null)) {
      env.helper.when(() -> NeoAccessHelper.hasWindowAccess(env.role,
          GoFeature.FISCAL_MODELS.legacyId, "POST")).thenReturn(true);

      assertTrue(GoFeatureAccess.hasAccess(env.role, GoFeature.FISCAL_MODELS, "POST"));
      assertFalse(GoFeatureAccess.hasAccess(env.role, GoFeature.FISCAL_MONITOR, "POST"));
    }
  }

  @Test
  public void withoutRowTheLegacyProcessGrantDecides() {
    try (Env env = new Env(null)) {
      env.helper.when(() -> NeoAccessHelper.hasObuiappProcessAccess(env.role,
          GoFeature.NOT_POSTED_DOCUMENTS.legacyId)).thenReturn(true);

      assertTrue(GoFeatureAccess.hasAccess(env.role, GoFeature.NOT_POSTED_DOCUMENTS, "GET"));
    }
  }

  @Test
  public void withoutRowAndLegacyGrantAccessIsDenied() {
    try (Env env = new Env(null)) {
      for (GoFeature feature : GoFeature.values()) {
        assertFalse(feature.id, GoFeatureAccess.hasAccess(env.role, feature, "GET"));
      }
      assertNull(GoFeatureAccess.grantedTier(env.role, GoFeature.SMART_SCAN));
    }
  }

  @Test
  public void overlayTierPrefersTheRowAndKeepsTheLegacyTierOtherwise() {
    try (Env env = new Env("N")) {
      assertEquals(RoleAccessMatrix.READ_ONLY, GoFeatureAccess.overlayTier(env.role,
          GoFeature.FISCAL_MODELS.legacyId, RoleAccessMatrix.FULL));
      assertEquals(RoleAccessMatrix.FULL,
          GoFeatureAccess.overlayTier(env.role, "SOME_REAL_WINDOW", RoleAccessMatrix.FULL));
    }
  }

  @Test
  public void currentRoleChecksUseTheRowThenTheLegacyGrant() {
    try (Env env = new Env("N")) {
      env.helper.when(NeoAccessHelper::resolveCurrentRole).thenReturn(env.role);

      assertTrue(GoFeatureAccess.hasAccess(GoFeature.FISCAL_MODELS, "GET"));
      assertFalse(GoFeatureAccess.hasAccess(GoFeature.FISCAL_MODELS, "POST"));
      assertTrue(GoFeatureAccess.hasReadAccess(GoFeature.FINANCIAL_REPORTS));
    }
    try (Env env = new Env(null)) {
      env.helper.when(NeoAccessHelper::resolveCurrentRole).thenReturn(env.role);
      env.helper.when(() -> NeoAccessHelper.hasWindowAccess(GoFeature.SMART_SCAN.legacyId))
          .thenReturn(true);
      env.helper.when(() -> NeoAccessHelper.hasObuiappProcessAccess(
          GoFeature.NOT_POSTED_DOCUMENTS.legacyId)).thenReturn(true);

      assertTrue(GoFeatureAccess.hasReadAccess(GoFeature.SMART_SCAN));
      assertTrue(GoFeatureAccess.hasReadAccess(GoFeature.NOT_POSTED_DOCUMENTS));
      assertFalse(GoFeatureAccess.hasReadAccess(GoFeature.FISCAL_MONITOR));
      assertFalse(GoFeatureAccess.hasAccess(GoFeature.FISCAL_MONITOR, "POST"));
    }
  }

  @Test
  public void featureLookupsResolveDeclaredIdsAndAnchors() {
    assertEquals(GoFeature.SMART_SCAN, GoFeature.forId("smart-scan"));
    assertEquals(GoFeature.FISCAL_MONITOR, GoFeature.forLegacyId("FEF76C3E0F104F06A89AAD15A4A4A35C"));
    assertNull(GoFeature.forId("tax-report"));
    assertNull(GoFeature.forSupportTable("NO_TABLE"));
    assertEquals(GoFeature.values().length, GoFeature.ids().size());
  }

  /** A role with an optional GO Access row ({@code "Y"}/{@code "N"}); {@code null} for none. */
  private static final class Env implements AutoCloseable {
    final Role role = mock(Role.class);
    final MockedStatic<OBDal> dal = mockStatic(OBDal.class);
    final MockedStatic<NeoAccessHelper> helper = mockStatic(NeoAccessHelper.class);

    @SuppressWarnings({ "unchecked", "rawtypes" })
    Env(String readWrite) {
      when(role.getId()).thenReturn(ROLE_ID);
      OBDal obDal = mock(OBDal.class);
      Session session = mock(Session.class);
      NativeQuery query = mock(NativeQuery.class);
      dal.when(OBDal::getInstance).thenReturn(obDal);
      when(obDal.getSession()).thenReturn(session);
      when(session.createNativeQuery(anyString())).thenReturn(query);
      when(query.setParameter(anyString(), any())).thenReturn(query);
      when(query.list()).thenReturn(readWrite == null ? List.of() : List.of(readWrite));
    }

    @Override
    public void close() {
      helper.close();
      dal.close();
    }
  }
}
