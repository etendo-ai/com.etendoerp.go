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

import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.openbravo.dal.service.OBDal;
import org.openbravo.model.ad.access.Role;

/**
 * The one answer to "may this role use GO feature X with this method" (ETP-4802), used by the
 * roles matrices, the report catalog, the SPA access endpoints, the attachment gate and the
 * handler-level gates.
 *
 * <p>Resolution: no role denies; the System Administrator and client-admin roles always pass (same
 * shortcut as {@link NeoAccessHelper#hasWindowAccess(Role, String, String)} and
 * {@link NeoAccessHelper#hasObuiappProcessAccess(Role, String)}, so it grants nothing they do not);
 * an active GO Access row of the role for the feature decides (read: any row; write:
 * {@code IsReadWrite = 'Y'}, the {@link NeoAccessHelper#isWriteMethod} mapping); with no row, the
 * legacy classic grant of {@link GoFeature#legacyKind} decides, so no role changes access. A
 * feature with neither is denied: there is no permissive fallback. Only active rows count, see
 * {@link GoFeature}.</p>
 */
public final class GoFeatureAccess {

  private static final String ROWS_SQL = "SELECT feature, isreadwrite FROM etgo_go_access"
      + " WHERE ad_role_id = :roleId AND isactive = 'Y'";

  private GoFeatureAccess() {
  }

  /**
   * Checks whether the current role may use a feature with an HTTP method.
   *
   * @param feature the feature
   * @param httpMethod {@code GET} for read; {@code POST}/{@code PUT}/{@code PATCH}/{@code DELETE}
   *     for write
   * @return whether the current role may use the feature with that method
   */
  public static boolean hasAccess(GoFeature feature, String httpMethod) {
    Optional<Boolean> decided = decideFromRole(NeoAccessHelper.resolveCurrentRole(), feature, httpMethod);
    if (decided.isPresent()) {
      return decided.get();
    }
    if (feature.legacyKind == GoFeature.LegacyKind.OBUIAPP_PROCESS) {
      return NeoAccessHelper.hasObuiappProcessAccess(feature.legacyId);
    }
    return NeoAccessHelper.hasWindowAccess(feature.legacyId, httpMethod);
  }

  /**
   * Checks whether an explicit role may use a feature with an HTTP method.
   *
   * @param role the role to check; {@code null} denies
   * @param feature the feature
   * @param httpMethod the method, as in {@link #hasAccess(GoFeature, String)}
   * @return whether {@code role} may use the feature with that method
   */
  public static boolean hasAccess(Role role, GoFeature feature, String httpMethod) {
    if (role == null) {
      return false;
    }
    Optional<Boolean> decided = decideFromRole(role, feature, httpMethod);
    if (decided.isPresent()) {
      return decided.get();
    }
    return feature.legacyKind == GoFeature.LegacyKind.OBUIAPP_PROCESS
        ? NeoAccessHelper.hasObuiappProcessAccess(role, feature.legacyId)
        : NeoAccessHelper.hasWindowAccess(role, feature.legacyId, httpMethod);
  }

  /**
   * Read access of the current role, for the report handlers' {@code isAccessibleForCurrentRole}
   * gates; same as {@link #hasAccess(GoFeature, String)} with {@code GET}.
   *
   * @param feature the feature
   * @return whether the current role may read the feature
   */
  public static boolean hasReadAccess(GoFeature feature) {
    Optional<Boolean> decided = decideFromRole(NeoAccessHelper.resolveCurrentRole(), feature, "GET");
    if (decided.isPresent()) {
      return decided.get();
    }
    return feature.legacyKind == GoFeature.LegacyKind.OBUIAPP_PROCESS
        ? NeoAccessHelper.hasObuiappProcessAccess(feature.legacyId)
        : NeoAccessHelper.hasWindowAccess(feature.legacyId);
  }

  /**
   * The part of the decision that does not depend on the legacy grant.
   *
   * @return {@code true} for an admin role or when the role's GO Access row allows the method,
   *     {@code false} when the row denies it, empty when the legacy grant must decide
   *     (no role, or no row)
   */
  private static Optional<Boolean> decideFromRole(Role role, GoFeature feature, String httpMethod) {
    if (role == null) {
      return Optional.empty();
    }
    if (NeoAccessHelper.isAdminOrClientAdmin(role)) {
      return Optional.of(Boolean.TRUE);
    }
    String granted = grantedTier(role, feature);
    if (granted == null) {
      return Optional.empty();
    }
    return Optional.of(
        !NeoAccessHelper.isWriteMethod(httpMethod) || RoleAccessMatrix.FULL.equals(granted));
  }

  /**
   * The tier a role has for every feature it can use, resolved with one GO Access query plus one
   * query per legacy record kind: the GO Access row when there is one, the classic grant of the
   * feature otherwise; admin roles get {@link RoleAccessMatrix#FULL} everywhere. Features the role
   * cannot use are absent. This is what the SPA reads, so it answers like the gates.
   *
   * @param role the role; {@code null} yields an empty map
   * @return feature id to {@link RoleAccessMatrix#FULL} or {@link RoleAccessMatrix#READ_ONLY}
   */
  public static Map<String, String> effectiveTiers(Role role) {
    Map<String, String> tiers = new LinkedHashMap<>();
    if (role == null) {
      return tiers;
    }
    boolean admin = NeoAccessHelper.isAdminOrClientAdmin(role);
    Map<String, String> granted = admin ? Collections.emptyMap() : grantedTiers(role);
    Map<String, String> legacy = new HashMap<>();
    if (!admin) {
      Set<String> windowIds = new HashSet<>();
      for (GoFeature feature : GoFeature.withLegacyKind(GoFeature.LegacyKind.WINDOW)) {
        windowIds.add(feature.legacyId);
      }
      legacy.putAll(RoleAccessMatrix.resolveWindowTierMap(role, windowIds));
      for (GoFeature feature : GoFeature.withLegacyKind(GoFeature.LegacyKind.OBUIAPP_PROCESS)) {
        legacy.putAll(RoleAccessMatrix.resolveProcessTierMap(role, feature.legacyId));
      }
    }
    for (GoFeature feature : GoFeature.values()) {
      String tier = admin ? RoleAccessMatrix.FULL : granted.get(feature.id);
      if (tier == null) {
        tier = legacy.get(feature.legacyId);
      }
      if (tier != null) {
        tiers.put(feature.id, tier);
      }
    }
    return tiers;
  }

  /**
   * All of a role's active GO Access rows, in one query.
   *
   * @param role the role
   * @return feature id to {@link RoleAccessMatrix#FULL} or {@link RoleAccessMatrix#READ_ONLY};
   *     empty when the role has no rows
   */
  public static Map<String, String> grantedTiers(Role role) {
    List<?> rows = OBDal.getInstance().getSession().createNativeQuery(ROWS_SQL)
        .setParameter("roleId", role.getId()).list();
    if (rows.isEmpty()) {
      return Collections.emptyMap();
    }
    Map<String, String> tiers = new HashMap<>();
    for (Object row : rows) {
      Object[] cols = (Object[]) row;
      tiers.put(String.valueOf(cols[0]).trim(),
          "Y".equals(String.valueOf(cols[1]).trim()) ? RoleAccessMatrix.FULL
              : RoleAccessMatrix.READ_ONLY);
    }
    return tiers;
  }

  /**
   * Returns the tier of one GO Access row of a role.
   *
   * @param role the role
   * @param feature the feature
   * @return the tier of the role's active GO Access row, or {@code null} when it has none
   */
  public static String grantedTier(Role role, GoFeature feature) {
    return grantedTiers(role).get(feature.id);
  }

  /**
   * Replaces a legacy tier by the one of the GO Access rows when the record is a feature's legacy
   * anchor, the feature is overlaid on the SPA surfaces and the role has such a row.
   *
   * @param granted the role's rows, from {@link #grantedTiers(Role)}
   * @param legacyId id of the window or process the legacy tier was resolved on
   * @param legacyTier the tier resolved from the classic grant
   * @return the GO Access tier when there is one, otherwise {@code legacyTier}
   */
  public static String overlayTier(Map<String, String> granted, String legacyId,
      String legacyTier) {
    GoFeature feature = GoFeature.forLegacyId(legacyId);
    if (feature == null || feature.sharesRealWindow) {
      return legacyTier;
    }
    String tier = granted.get(feature.id);
    return tier != null ? tier : legacyTier;
  }

  /**
   * Overlays, in place, the role's GO Access tiers onto a map keyed by legacy anchor id, for the
   * given features; features whose anchor is also a real window ({@link GoFeature#sharesRealWindow})
   * are skipped. A role with a row but no legacy grant gains the entry.
   *
   * @param role the role
   * @param tiers legacy anchor id to tier, updated in place
   * @param features the features the map has a row for
   */
  public static void overlayTiers(Role role, Map<String, String> tiers,
      Iterable<GoFeature> features) {
    Map<String, String> granted = grantedTiers(role);
    for (GoFeature feature : features) {
      String tier = granted.get(feature.id);
      if (tier != null && !feature.sharesRealWindow) {
        tiers.put(feature.legacyId, tier);
      }
    }
  }
}
