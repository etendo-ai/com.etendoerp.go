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

import java.util.List;

import org.openbravo.dal.service.OBDal;
import org.openbravo.model.ad.access.Role;

/**
 * The one answer to "may this role use GO feature X with this method" (ETP-4802), used by the
 * roles matrices, the report catalog, the attachment gate and the handler-level gates.
 *
 * <p>Resolution: no role denies; the System Administrator and client-admin roles always pass (same
 * as {@link NeoAccessHelper#hasWindowAccess(Role, String, String)}); an active GO Access row of the
 * role for the feature decides (read: any row; write: {@code IsReadWrite = 'Y'}); with no row, the
 * legacy classic grant of {@link GoFeature#legacyKind} decides, so no role changes access. A
 * feature with neither is denied: there is no permissive fallback.</p>
 */
public final class GoFeatureAccess {

  private static final String ROW_SQL = "SELECT isreadwrite FROM etgo_go_access"
      + " WHERE ad_role_id = :roleId AND feature = :feature AND isactive = 'Y'";

  private GoFeatureAccess() {
  }

  /**
   * @param feature the feature
   * @param httpMethod {@code GET} for read; {@code POST}/{@code PUT}/{@code PATCH}/{@code DELETE}
   *     for write
   * @return whether the current role may use the feature with that method
   */
  public static boolean hasAccess(GoFeature feature, String httpMethod) {
    Role role = NeoAccessHelper.resolveCurrentRole();
    if (role != null && !NeoAccessHelper.isAdminOrClientAdmin(role)) {
      String granted = grantedTier(role, feature);
      if (granted != null) {
        return !isWrite(httpMethod) || RoleAccessMatrix.FULL.equals(granted);
      }
    }
    // No role, an admin, or no GO Access row: the legacy current-role check decides (it denies
    // a missing role and passes the admin roles itself).
    return feature.legacyKind == GoFeature.LegacyKind.OBUIAPP_PROCESS
        ? NeoAccessHelper.hasObuiappProcessAccess(feature.legacyId)
        : NeoAccessHelper.hasWindowAccess(feature.legacyId, httpMethod);
  }

  /**
   * Read access of the current role, for the report handlers' {@code isAccessibleForCurrentRole}
   * gates; same as {@link #hasAccess(GoFeature, String)} with {@code GET}.
   *
   * @param feature the feature
   * @return whether the current role may read the feature
   */
  public static boolean hasReadAccess(GoFeature feature) {
    Role role = NeoAccessHelper.resolveCurrentRole();
    if (role != null && !NeoAccessHelper.isAdminOrClientAdmin(role)
        && grantedTier(role, feature) != null) {
      return true;
    }
    return feature.legacyKind == GoFeature.LegacyKind.OBUIAPP_PROCESS
        ? NeoAccessHelper.hasObuiappProcessAccess(feature.legacyId)
        : NeoAccessHelper.hasWindowAccess(feature.legacyId);
  }

  /**
   * @param role the role to check; {@code null} denies
   * @param feature the feature
   * @param httpMethod the method, as in {@link #hasAccess(GoFeature, String)}
   * @return whether {@code role} may use the feature with that method
   */
  public static boolean hasAccess(Role role, GoFeature feature, String httpMethod) {
    if (role == null) {
      return false;
    }
    if (NeoAccessHelper.isAdminOrClientAdmin(role)) {
      return true;
    }
    String granted = grantedTier(role, feature);
    if (granted != null) {
      return !isWrite(httpMethod) || RoleAccessMatrix.FULL.equals(granted);
    }
    if (feature.legacyKind == GoFeature.LegacyKind.OBUIAPP_PROCESS) {
      return NeoAccessHelper.hasObuiappProcessAccess(role, feature.legacyId);
    }
    return NeoAccessHelper.hasWindowAccess(role, feature.legacyId, httpMethod);
  }

  /**
   * @param role the role
   * @param feature the feature
   * @return {@link RoleAccessMatrix#FULL} or {@link RoleAccessMatrix#READ_ONLY} from the role's
   *     active GO Access row, or {@code null} when it has none
   */
  public static String grantedTier(Role role, GoFeature feature) {
    List<?> rows = OBDal.getInstance().getSession().createNativeQuery(ROW_SQL)
        .setParameter("roleId", role.getId()).setParameter("feature", feature.id).list();
    if (rows.isEmpty()) {
      return null;
    }
    return "Y".equals(String.valueOf(rows.get(0)).trim()) ? RoleAccessMatrix.FULL
        : RoleAccessMatrix.READ_ONLY;
  }

  /**
   * Replaces a legacy tier by the one of the role's GO Access row when the record is a feature's
   * legacy anchor and the role has such a row.
   *
   * @param role the role
   * @param legacyId id of the window or process the legacy tier was resolved on
   * @param legacyTier the tier resolved from the classic grant
   * @return the GO Access tier when there is one, otherwise {@code legacyTier}
   */
  public static String overlayTier(Role role, String legacyId, String legacyTier) {
    GoFeature feature = GoFeature.forLegacyId(legacyId);
    if (feature == null) {
      return legacyTier;
    }
    String granted = grantedTier(role, feature);
    return granted != null ? granted : legacyTier;
  }

  private static boolean isWrite(String httpMethod) {
    return "POST".equalsIgnoreCase(httpMethod) || "PUT".equalsIgnoreCase(httpMethod)
        || "PATCH".equalsIgnoreCase(httpMethod) || "DELETE".equalsIgnoreCase(httpMethod);
  }
}
