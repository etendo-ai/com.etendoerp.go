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

package com.etendoerp.go.modulescript;

import java.sql.PreparedStatement;

import org.openbravo.database.ConnectionProvider;
import org.openbravo.modulescript.ModuleScript;

/**
 * ETP-4802 — seeds the GO Access subtab ({@code ETGO_Go_Access}) from the classic grants the GO
 * features were proxied through, at the same tier, so a role keeps exactly the access it has
 * today while the feature check starts reading GO Access first.
 *
 * <p>For every role (system templates and tenant roles alike) holding an active classic grant on a
 * feature's legacy window ({@code AD_Window_Access}) or OBUIAPP process
 * ({@code OBUIAPP_Process_Access}), one GO Access row is inserted with the same
 * {@code IsReadWrite}. Inactive legacy rows are not seeded. It is idempotent: an existing row for
 * the role and feature (active or not) is never touched, which the unique index
 * {@code ETGO_GOACC_ROLE_FEATURE_UN} also enforces, so re-running adds nothing and never overwrites
 * an operator's decision. Nothing is seeded for a feature no role holds (Smart Scan, ETP-5830).
 *
 * <p>The feature ids and legacy ids below are an inlined copy of {@code GoFeature} (a module script
 * cannot import module classes); {@code SeedGoAccessScriptTest} asserts they match.
 */
public class SeedGoAccessScript extends ModuleScript {

  /** {feature id, legacy window id}. */
  static final String[][] WINDOW_FEATURES = {
      { "fiscal-models", "3E8FEA1EA7404D979306C9EE7FD2E7E8" },
      { "fiscal-monitor", "FEF76C3E0F104F06A89AAD15A4A4A35C" },
      { "financial-reports", "D647D118F5014D00AF47A636B2CD0DD3" },
      { "smart-scan", "33705E0F52874D91B0BB2FF8BB648B8E" },
      { "inventory-stock-report", "6346B88619F948F9A42224BDB0B239FA" } };

  /** {feature id, legacy OBUIAPP process id}. */
  static final String[][] PROCESS_FEATURES = {
      { "not-posted-documents", "D6AB95CE52D34E1599590526115E26C6" } };

  static final String WINDOW_SEED_SQL = "INSERT INTO etgo_go_access (etgo_go_access_id,"
      + " ad_client_id, ad_org_id, isactive, created, createdby, updated, updatedby, ad_role_id,"
      + " feature, isreadwrite)"
      + " SELECT get_uuid(), s.ad_client_id, s.ad_org_id, 'Y', now(), '0', now(), '0', s.ad_role_id,"
      + " ?, s.isreadwrite FROM ("
      + "   SELECT DISTINCT ON (wa.ad_role_id) wa.ad_client_id, wa.ad_org_id, wa.ad_role_id,"
      + "     wa.isreadwrite FROM ad_window_access wa"
      + "   WHERE wa.ad_window_id = ? AND wa.isactive = 'Y'"
      + "   ORDER BY wa.ad_role_id, wa.isreadwrite DESC) s"
      + " WHERE NOT EXISTS (SELECT 1 FROM etgo_go_access g"
      + "   WHERE g.ad_role_id = s.ad_role_id AND g.feature = ?)";

  static final String PROCESS_SEED_SQL = "INSERT INTO etgo_go_access (etgo_go_access_id,"
      + " ad_client_id, ad_org_id, isactive, created, createdby, updated, updatedby, ad_role_id,"
      + " feature, isreadwrite)"
      + " SELECT get_uuid(), s.ad_client_id, s.ad_org_id, 'Y', now(), '0', now(), '0', s.ad_role_id,"
      + " ?, s.isreadwrite FROM ("
      + "   SELECT DISTINCT ON (pa.ad_role_id) pa.ad_client_id, pa.ad_org_id, pa.ad_role_id,"
      + "     pa.isreadwrite FROM obuiapp_process_access pa"
      + "   WHERE pa.obuiapp_process_id = ? AND pa.isactive = 'Y'"
      + "   ORDER BY pa.ad_role_id, pa.isreadwrite DESC) s"
      + " WHERE NOT EXISTS (SELECT 1 FROM etgo_go_access g"
      + "   WHERE g.ad_role_id = s.ad_role_id AND g.feature = ?)";

  @Override
  public void execute() {
    try {
      seed(getConnectionProvider());
    } catch (Exception e) {
      handleError(e);
    }
  }

  /**
   * Seeds one GO Access row per role and feature that has an active legacy grant and no row yet.
   *
   * @return the number of rows inserted
   */
  int seed(ConnectionProvider cp) throws Exception {
    int inserted = 0;
    for (String[] feature : WINDOW_FEATURES) {
      inserted += seedFeature(cp, WINDOW_SEED_SQL, feature);
    }
    for (String[] feature : PROCESS_FEATURES) {
      inserted += seedFeature(cp, PROCESS_SEED_SQL, feature);
    }
    log4j.info("Seeded " + inserted + " GO Access row(s) from the legacy proxy grants");
    return inserted;
  }

  private static int seedFeature(ConnectionProvider cp, String sql, String[] feature)
      throws Exception {
    try (PreparedStatement ps = cp.getPreparedStatement(sql)) {
      ps.setString(1, feature[0]);
      ps.setString(2, feature[1]);
      ps.setString(3, feature[0]);
      return ps.executeUpdate();
    }
  }
}
