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

package com.etendoerp.go.roles.goaccess;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.hibernate.Session;
import org.openbravo.dal.service.OBDal;

import com.etendoerp.go.schemaforge.util.GoFeature;
import com.etendoerp.go.schemaforge.util.RoleAccessMatrix;

/**
 * Keeps a role's GO Access row of each {@link GoFeature} in step with the classic grant the feature
 * was proxied through (ETP-4802).
 *
 * <p>A GO Access row is authoritative over the legacy grant (see {@link GoFeature}), so a row that
 * outlives a revoked or downgraded classic grant would keep the feature open. Every write path of
 * the classic grants therefore calls this class: the DAL observer
 * ({@link GoAccessLegacyMirrorObserver}) for window/process access rows saved through the DAL, and
 * {@link #snapshot(Collection)} + {@link #syncChanged(Map, Collection)} around native bulk writers
 * (template propagation). The rule is "last write wins" per role and feature: a classic grant
 * change of a feature for a role overwrites that role's GO row of the feature (inserted, updated to
 * the same tier, or deleted when no active grant is left). Features whose classic grant did not
 * change are never touched, so an administrator's own GO Access rows survive unrelated edits.</p>
 */
public final class GoAccessLegacyMirror {

  /** Tier of a legacy window/process grant, from every active row of the role and record. */
  private static final String LEGACY_TIERS_SQL = "SELECT x.legacy_id, MAX(x.rw) FROM ("
      + " SELECT wa.ad_window_id AS legacy_id, wa.isreadwrite AS rw FROM ad_window_access wa"
      + "  WHERE wa.ad_role_id IN (:roles) AND wa.isactive = 'Y' AND wa.ad_window_id IN (:windows)"
      + "  AND wa.ad_window_access_id <> :excludeId"
      + " UNION ALL"
      + " SELECT pa.obuiapp_process_id, pa.isreadwrite FROM obuiapp_process_access pa"
      + "  WHERE pa.ad_role_id IN (:roles) AND pa.isactive = 'Y'"
      + "  AND pa.obuiapp_process_id IN (:processes)"
      + "  AND pa.obuiapp_process_access_id <> :excludeId"
      + ") x GROUP BY x.legacy_id";

  private static final String SNAPSHOT_SQL = "SELECT x.ad_role_id, x.legacy_id, MAX(x.rw) FROM ("
      + " SELECT wa.ad_role_id, wa.ad_window_id AS legacy_id, wa.isreadwrite AS rw"
      + "  FROM ad_window_access wa WHERE wa.ad_role_id IN (:roles) AND wa.isactive = 'Y'"
      + "  AND wa.ad_window_id IN (:windows)"
      + " UNION ALL"
      + " SELECT pa.ad_role_id, pa.obuiapp_process_id, pa.isreadwrite FROM obuiapp_process_access pa"
      + "  WHERE pa.ad_role_id IN (:roles) AND pa.isactive = 'Y'"
      + "  AND pa.obuiapp_process_id IN (:processes)"
      + ") x GROUP BY x.ad_role_id, x.legacy_id";

  private static final String UPSERT_SQL = "INSERT INTO etgo_go_access (etgo_go_access_id,"
      + " ad_client_id, ad_org_id, isactive, created, createdby, updated, updatedby, ad_role_id,"
      + " feature, isreadwrite) SELECT get_uuid(), r.ad_client_id, r.ad_org_id, 'Y', now(),"
      + " :user, now(), :user, r.ad_role_id, :feature, :rw FROM ad_role r"
      + " WHERE r.ad_role_id = :role"
      + " ON CONFLICT ON CONSTRAINT etgo_goacc_role_feature_un DO UPDATE SET isactive = 'Y',"
      + " isreadwrite = EXCLUDED.isreadwrite, updated = now(), updatedby = EXCLUDED.updatedby";

  private static final String DELETE_SQL =
      "DELETE FROM etgo_go_access WHERE ad_role_id = :role AND feature = :feature";

  /** System user id, used for rows written outside a request. */
  static final String SYSTEM_USER_ID = "100";

  private GoAccessLegacyMirror() {
  }

  /**
   * Returns the tier the classic grants give a role for each feature, ignoring one access row.
   *
   * @param roleId the role
   * @param excludeAccessId primary key of a window/process access row to ignore (the row being
   *     deleted or deactivated, whose change is not flushed yet), or {@code null}
   * @return feature id to {@link RoleAccessMatrix#FULL} or {@link RoleAccessMatrix#READ_ONLY};
   *     features with no active classic grant are absent
   */
  public static Map<String, String> legacyTiers(String roleId, String excludeAccessId) {
    List<?> rows = session().createNativeQuery(LEGACY_TIERS_SQL)
        .setParameterList("roles", List.of(roleId))
        .setParameterList("windows", legacyIds(GoFeature.LegacyKind.WINDOW))
        .setParameterList("processes", legacyIds(GoFeature.LegacyKind.OBUIAPP_PROCESS))
        .setParameter("excludeId", excludeAccessId == null ? "" : excludeAccessId).list();
    Map<String, String> tiers = new HashMap<>();
    for (Object row : rows) {
      Object[] cols = (Object[]) row;
      GoFeature feature = GoFeature.forLegacyId(String.valueOf(cols[0]).trim());
      if (feature != null) {
        tiers.put(feature.id, tier(cols[1]));
      }
    }
    return tiers;
  }

  /**
   * Records the classic grant tiers of roles, to compare after a native bulk write.
   *
   * @param roleIds the roles the writer is about to touch
   * @return role id to (feature id to tier); every role is a key
   */
  public static Map<String, Map<String, String>> snapshot(Collection<String> roleIds) {
    Map<String, Map<String, String>> result = new HashMap<>();
    if (roleIds.isEmpty()) {
      return result;
    }
    for (String roleId : roleIds) {
      result.put(roleId, new HashMap<>());
    }
    List<?> rows = session().createNativeQuery(SNAPSHOT_SQL)
        .setParameterList("roles", roleIds)
        .setParameterList("windows", legacyIds(GoFeature.LegacyKind.WINDOW))
        .setParameterList("processes", legacyIds(GoFeature.LegacyKind.OBUIAPP_PROCESS)).list();
    for (Object row : rows) {
      Object[] cols = (Object[]) row;
      GoFeature feature = GoFeature.forLegacyId(String.valueOf(cols[1]).trim());
      Map<String, String> tiers = result.get(String.valueOf(cols[0]).trim());
      if (feature != null && tiers != null) {
        tiers.put(feature.id, tier(cols[2]));
      }
    }
    return result;
  }

  /**
   * Applies, for each role and feature whose classic grant tier differs from {@code before}, the
   * "last write wins" rule of this class.
   *
   * @param before the snapshot taken before the native write
   * @param roleIds the roles to compare
   * @return the number of role/feature pairs synchronised
   */
  public static int syncChanged(Map<String, Map<String, String>> before,
      Collection<String> roleIds) {
    Map<String, Map<String, String>> after = snapshot(roleIds);
    int changed = 0;
    for (String roleId : roleIds) {
      Map<String, String> was = before.getOrDefault(roleId, Collections.emptyMap());
      Map<String, String> now = after.getOrDefault(roleId, Collections.emptyMap());
      for (GoFeature feature : GoFeature.values()) {
        String oldTier = was.get(feature.id);
        String newTier = now.get(feature.id);
        if (oldTier == null ? newTier != null : !oldTier.equals(newTier)) {
          apply(roleId, feature, newTier);
          changed++;
        }
      }
    }
    return changed;
  }

  /**
   * Mirrors one classic grant change onto the role's GO Access row. A no-op when the record is not
   * the legacy record of a feature.
   *
   * @param roleId the role of the access row
   * @param legacyId the window or OBUIAPP process id of the access row
   * @param active the row's active flag after the change ({@code false} for a delete)
   * @param readWrite the row's {@code IsReadWrite} after the change
   * @param accessId primary key of the access row, ignored when looking for other grants of the
   *     same pair (the table has no unique key, another active row keeps the grant alive)
   * @return the feature that was synchronised, or {@code null}
   */
  public static GoFeature onClassicGrantChange(String roleId, String legacyId, boolean active,
      boolean readWrite, String accessId) {
    GoFeature feature = legacyId == null ? null : GoFeature.forLegacyId(legacyId);
    if (feature == null || roleId == null) {
      return null;
    }
    String tier = null;
    if (active) {
      tier = readWrite ? RoleAccessMatrix.FULL : RoleAccessMatrix.READ_ONLY;
    }
    String other = legacyTiers(roleId, accessId).get(feature.id);
    if (other != null && (tier == null || RoleAccessMatrix.FULL.equals(other))) {
      tier = other;
    }
    apply(roleId, feature, tier);
    return feature;
  }

  /**
   * Sets a role's GO Access row of a feature to a tier, or removes it.
   *
   * @param roleId the role
   * @param feature the feature
   * @param tier {@link RoleAccessMatrix#FULL}, {@link RoleAccessMatrix#READ_ONLY} or {@code null}
   *     to delete the row
   */
  public static void apply(String roleId, GoFeature feature, String tier) {
    if (tier == null) {
      session().createNativeQuery(DELETE_SQL).setParameter("role", roleId)
          .setParameter("feature", feature.id).executeUpdate();
      return;
    }
    session().createNativeQuery(UPSERT_SQL).setParameter("role", roleId)
        .setParameter("feature", feature.id)
        .setParameter("rw", RoleAccessMatrix.FULL.equals(tier) ? "Y" : "N")
        .setParameter("user", SYSTEM_USER_ID).executeUpdate();
  }

  private static List<String> legacyIds(GoFeature.LegacyKind kind) {
    List<String> ids = new ArrayList<>();
    for (GoFeature feature : GoFeature.withLegacyKind(kind)) {
      ids.add(feature.legacyId);
    }
    // an empty IN list is invalid SQL; this id matches no record
    return ids.isEmpty() ? List.of("-") : ids;
  }

  private static String tier(Object isReadWrite) {
    return "Y".equals(String.valueOf(isReadWrite).trim()) ? RoleAccessMatrix.FULL
        : RoleAccessMatrix.READ_ONLY;
  }

  private static Session session() {
    return OBDal.getInstance().getSession();
  }
}
