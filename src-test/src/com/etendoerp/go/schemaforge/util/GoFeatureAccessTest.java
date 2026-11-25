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

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.hibernate.Session;
import org.hibernate.query.NativeQuery;
import org.junit.Assume;
import org.junit.Test;
import org.mockito.MockedStatic;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.ad.access.Role;

/**
 * The shared GO feature access check (ETP-4802): GO Access row first, legacy classic grant when the
 * role has none, no permissive fallback, and the same decision before and with a row at the legacy tier.
 *
 * @covers com.etendoerp.go.schemaforge.util.GoFeatureAccess
 * @covers com.etendoerp.go.schemaforge.util.GoFeature
 */
public class GoFeatureAccessTest {

  private static final String ROLE_ID = "ROLE1";
  private static final String NONE = "none";

  @Test
  public void nullRoleIsDenied() {
    assertFalse(GoFeatureAccess.hasAccess(null, GoFeature.FISCAL_MODELS, "GET"));
  }

  @Test
  public void adminPassesWithoutAnyGrant() {
    try (Env env = new Env(Map.of(), NONE)) {
      env.helper.when(() -> NeoAccessHelper.isAdminOrClientAdmin(env.role)).thenReturn(true);

      assertTrue(GoFeatureAccess.hasAccess(env.role, GoFeature.SMART_SCAN, "POST"));
    }
  }

  /** Per feature: row absent or present x legacy grant none/read-only/read-write x read/write. */
  @Test
  public void everyFeatureDecidesFromTheRowThenTheLegacyGrant() {
    for (GoFeature feature : GoFeature.values()) {
      // no row: today's legacy tier decides
      assertDecision(feature, Map.of(), NONE, false, false);
      assertDecision(feature, Map.of(), RoleAccessMatrix.READ_ONLY, true, false);
      assertDecision(feature, Map.of(), RoleAccessMatrix.FULL, true, true);
      // read-only row: read allowed, write denied, whatever the legacy grant is
      for (String legacy : List.of(NONE, RoleAccessMatrix.READ_ONLY, RoleAccessMatrix.FULL)) {
        assertDecision(feature, Map.of(feature.id, RoleAccessMatrix.READ_ONLY), legacy, true,
            false);
        assertDecision(feature, Map.of(feature.id, RoleAccessMatrix.FULL), legacy, true, true);
      }
      // a row of another feature does not grant this one
      GoFeature other = feature == GoFeature.SMART_SCAN ? GoFeature.FISCAL_MODELS
          : GoFeature.SMART_SCAN;
      assertDecision(feature, Map.of(other.id, RoleAccessMatrix.FULL), NONE, false, false);
    }
  }

  /** A row at the legacy tier decides like the legacy grant, with or without that grant. */
  @Test
  public void aRowAtTheLegacyTierKeepsEveryDecision() {
    for (GoFeature feature : GoFeature.values()) {
      for (String legacy : List.of(NONE, RoleAccessMatrix.READ_ONLY, RoleAccessMatrix.FULL)) {
        boolean[] before = decisions(feature, Map.of(), legacy);
        Map<String, String> seeded = NONE.equals(legacy) ? Map.of() : Map.of(feature.id, legacy);
        // A classic process grant has no write tier, and the one process feature is only ever
        // read (hasReadAccess): its seeded tier is compared on read only.
        int compared = feature.legacyKind == GoFeature.LegacyKind.OBUIAPP_PROCESS ? 1 : 2;
        assertArrayEquals(feature.id + "/" + legacy + " with a row at the legacy tier",
            Arrays.copyOf(before, compared),
            Arrays.copyOf(decisions(feature, seeded, legacy), compared));
        // the legacy fallback can later be dropped without changing the decision
        assertArrayEquals(feature.id + "/" + legacy + " without legacy",
            Arrays.copyOf(before, compared),
            Arrays.copyOf(decisions(feature, seeded, NONE), compared));
      }
    }
  }

  @Test
  public void currentRoleChecksUseTheRowThenTheLegacyGrant() {
    try (Env env = new Env(Map.of(GoFeature.FISCAL_MODELS.id, RoleAccessMatrix.READ_ONLY), NONE)) {
      env.helper.when(NeoAccessHelper::resolveCurrentRole).thenReturn(env.role);

      assertTrue(GoFeatureAccess.hasAccess(GoFeature.FISCAL_MODELS, "GET"));
      assertFalse(GoFeatureAccess.hasAccess(GoFeature.FISCAL_MODELS, "POST"));
      assertFalse(GoFeatureAccess.hasReadAccess(GoFeature.FINANCIAL_REPORTS));
    }
    try (Env env = new Env(Map.of(), NONE)) {
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
  public void grantedTiersLoadsEveryRowOfTheRoleInOneQuery() {
    Map<String, String> rows = Map.of(GoFeature.SMART_SCAN.id, RoleAccessMatrix.FULL,
        GoFeature.FISCAL_MODELS.id, RoleAccessMatrix.READ_ONLY);
    try (Env env = new Env(rows, NONE)) {
      assertEquals(rows, GoFeatureAccess.grantedTiers(env.role));
      assertEquals(RoleAccessMatrix.READ_ONLY,
          GoFeatureAccess.grantedTier(env.role, GoFeature.FISCAL_MODELS));
      assertNull(GoFeatureAccess.grantedTier(env.role, GoFeature.FISCAL_MONITOR));
      // one query per lookup: grantedTiers once, grantedTier twice
      verify(env.session, times(3)).createNativeQuery(anyString());
    }
    try (Env env = new Env(Map.of(), NONE)) {
      assertTrue(GoFeatureAccess.grantedTiers(env.role).isEmpty());
    }
  }

  @Test
  public void overlayReplacesTheLegacyTierButNeverTheSharedRealWindow() {
    Map<String, String> granted = Map.of(GoFeature.FISCAL_MODELS.id, RoleAccessMatrix.READ_ONLY,
        GoFeature.FISCAL_MONITOR.id, RoleAccessMatrix.FULL);

    assertEquals(RoleAccessMatrix.READ_ONLY, GoFeatureAccess.overlayTier(granted,
        GoFeature.FISCAL_MODELS.legacyId, RoleAccessMatrix.FULL));
    assertEquals(RoleAccessMatrix.FULL, GoFeatureAccess.overlayTier(granted, "SOME_REAL_WINDOW",
        RoleAccessMatrix.FULL));
    assertEquals(NONE, GoFeatureAccess.overlayTier(granted, GoFeature.FISCAL_MONITOR.legacyId,
        NONE));
    assertEquals(NONE, GoFeatureAccess.overlayTier(Map.of(), GoFeature.FINANCIAL_REPORTS.legacyId,
        NONE));
    assertEquals(RoleAccessMatrix.FULL, GoFeatureAccess.overlayTier(
        Map.of(GoFeature.FINANCIAL_REPORTS.id, RoleAccessMatrix.FULL),
        GoFeature.FINANCIAL_REPORTS.legacyId, NONE));
  }

  @Test
  public void overlayTiersWritesRowsOntoAnchorsIncludingRolesWithoutALegacyGrant() {
    Map<String, String> rows = Map.of(GoFeature.FISCAL_MODELS.id, RoleAccessMatrix.READ_ONLY,
        GoFeature.NOT_POSTED_DOCUMENTS.id, RoleAccessMatrix.FULL,
        GoFeature.FISCAL_MONITOR.id, RoleAccessMatrix.FULL);
    try (Env env = new Env(rows, NONE)) {
      Map<String, String> tiers = new LinkedHashMap<>();
      tiers.put(GoFeature.FISCAL_MODELS.legacyId, RoleAccessMatrix.FULL);

      GoFeatureAccess.overlayTiers(env.role, tiers, List.of(GoFeature.values()));

      assertEquals(RoleAccessMatrix.READ_ONLY, tiers.get(GoFeature.FISCAL_MODELS.legacyId));
      assertEquals(RoleAccessMatrix.FULL, tiers.get(GoFeature.NOT_POSTED_DOCUMENTS.legacyId));
      assertFalse(tiers.containsKey(GoFeature.FISCAL_MONITOR.legacyId));
    }
  }

  @Test
  public void featureLookupsResolveDeclaredIdsAnchorsAndSupportTables() {
    assertEquals(GoFeature.SMART_SCAN, GoFeature.forId("smart-scan"));
    assertEquals(GoFeature.FISCAL_MONITOR, GoFeature.forLegacyId("FEF76C3E0F104F06A89AAD15A4A4A35C"));
    assertEquals(GoFeature.FISCAL_MODELS, GoFeature.forSupportTable("F8B5925363A64980B7522FFD98A1628B"));
    assertNull(GoFeature.forId("tax-report"));
    assertNull(GoFeature.forSupportTable("NO_TABLE"));
    assertEquals(GoFeature.values().length, GoFeature.ids().size());
    assertEquals(List.of(GoFeature.NOT_POSTED_DOCUMENTS),
        GoFeature.withLegacyKind(GoFeature.LegacyKind.OBUIAPP_PROCESS));
  }

  /** The {@code ETGO_GoFeature} list reference (AD_REF_LIST) lists exactly {@link GoFeature#ids()}. */
  @Test
  public void theFeatureListReferenceMatchesTheDeclaredFeatures() throws Exception {
    Path root = Stream.of(Paths.get(""), Paths.get("modules", "com.etendoerp.go"))
        .filter(p -> Files.isRegularFile(p.resolve(GO_ACCESS_MODEL))).findFirst().orElse(null);
    Assume.assumeNotNull(root);
    String xml = new String(Files.readAllBytes(root.resolve(AD_REF_LIST)), StandardCharsets.UTF_8);
    String reference = new String(Files.readAllBytes(root.resolve(AD_REFERENCE)),
        StandardCharsets.UTF_8).replaceAll("<!--\\w+-->", "");
    Matcher ref = Pattern.compile("<AD_REFERENCE_ID><!\\[CDATA\\[(\\w+)\\]\\]></AD_REFERENCE_ID>"
        + "(?:(?!</AD_REFERENCE>).)*?<NAME><!\\[CDATA\\[ETGO_GoFeature\\]\\]></NAME>",
        Pattern.DOTALL).matcher(reference);
    assertTrue("ETGO_GoFeature reference exists", ref.find());
    Set<String> values = new HashSet<>();
    Matcher list = Pattern.compile("<VALUE><!\\[CDATA\\[([^\\]]+)\\]\\]></VALUE>\\s*(?:<!--\\w+-->)?\\s*"
        + "<NAME><!\\[CDATA\\[[^\\]]*\\]\\]></NAME>\\s*(?:<!--\\w+-->)?\\s*"
        + "<AD_REFERENCE_ID><!\\[CDATA\\[" + ref.group(1) + "\\]\\]>").matcher(
            xml.replaceAll("<!--\\w+-->", ""));
    while (list.find()) {
      values.add(list.group(1));
    }
    assertEquals(GoFeature.ids(), values);
  }

  /** Only this module has it: the core sourcedata has its own AD_REF_LIST.xml. */
  private static final Path GO_ACCESS_MODEL = Paths.get("src-db", "database", "model", "tables",
      "ETGO_GO_ACCESS.xml");
  private static final Path AD_REF_LIST = Paths.get("src-db", "database", "sourcedata",
      "AD_REF_LIST.xml");
  private static final Path AD_REFERENCE = Paths.get("src-db", "database", "sourcedata",
      "AD_REFERENCE.xml");

    /** {read, write} decision of a role with the given rows and legacy tier. */
  private static boolean[] decisions(GoFeature feature, Map<String, String> rows, String legacy) {
    try (Env env = new Env(rows, legacy)) {
      return new boolean[] { GoFeatureAccess.hasAccess(env.role, feature, "GET"),
          GoFeatureAccess.hasAccess(env.role, feature, "POST") };
    }
  }

  private static void assertDecision(GoFeature feature, Map<String, String> rows, String legacy,
      boolean read, boolean write) {
    boolean[] actual = decisions(feature, rows, legacy);
    boolean legacyWriteIgnored = rows.isEmpty()
        && feature.legacyKind == GoFeature.LegacyKind.OBUIAPP_PROCESS
        && !NONE.equals(legacy);
    assertEquals(feature.id + " read " + rows + "/" + legacy, read, actual[0]);
    // A classic process grant has no read/write tier: it allows every method (legacy behaviour).
    assertEquals(feature.id + " write " + rows + "/" + legacy, legacyWriteIgnored || write,
        actual[1]);
  }

  /** A role with the given GO Access rows and a classic grant of the given tier on the anchor. */
  private static final class Env implements AutoCloseable {
    final Role role = mock(Role.class);
    final MockedStatic<OBDal> dal = mockStatic(OBDal.class);
    final MockedStatic<NeoAccessHelper> helper = mockStatic(NeoAccessHelper.class);
    final Session session = mock(Session.class);

    @SuppressWarnings({ "unchecked", "rawtypes" })
    Env(Map<String, String> rows, String legacyTier) {
      when(role.getId()).thenReturn(ROLE_ID);
      OBDal obDal = mock(OBDal.class);
      NativeQuery query = mock(NativeQuery.class);
      dal.when(OBDal::getInstance).thenReturn(obDal);
      when(obDal.getSession()).thenReturn(session);
      when(session.createNativeQuery(anyString())).thenReturn(query);
      when(query.setParameter(anyString(), any())).thenReturn(query);
      List<Object[]> result = new ArrayList<>();
      rows.forEach((feature, tier) ->
          result.add(new Object[] { feature, RoleAccessMatrix.FULL.equals(tier) ? "Y" : "N" }));
      when(query.list()).thenReturn(result);

      helper.when(() -> NeoAccessHelper.isWriteMethod(anyString())).thenCallRealMethod();
      helper.when(() -> NeoAccessHelper.hasWindowAccess(any(Role.class), anyString(), anyString()))
          .thenAnswer(invocation -> !NONE.equals(legacyTier)
              && (RoleAccessMatrix.FULL.equals(legacyTier)
                  || !NeoAccessHelper.isWriteMethod(invocation.getArgument(2))));
      helper.when(() -> NeoAccessHelper.hasObuiappProcessAccess(any(Role.class), anyString()))
          .thenReturn(!NONE.equals(legacyTier));
    }

    @Override
    public void close() {
      helper.close();
      dal.close();
    }
  }
}
