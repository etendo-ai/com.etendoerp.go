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
 * ETP-4802 — seeds the GO Access rows ({@code ETGO_GO_ACCESS}) from the classic grants the six GO
 * features were proxied through, at the same tier, so that nobody's access changes when the
 * features start resolving through GO Access.
 *
 * <p><b>What it writes.</b> For every active role holding an active classic grant on a feature's
 * legacy record (window access row, or OBUIAPP process access row for "Documentos no
 * contabilizados"), one GO Access row with {@code IsReadWrite} copied from that grant (the highest
 * tier when the table holds several rows of the pair, as {@code NeoAccessHelper} resolves it).
 * Rows are inserted only when the role has none for the feature, active or not
 * ({@code ON CONFLICT DO NOTHING} on the {@code ETGO_GOACC_ROLE_FEATURE_UN} unique index), so an
 * administrator's own row is never overwritten and a re-run adds nothing. Inactive grants and
 * inactive roles are not seeded: they do not resolve today either.</p>
 *
 * <p><b>The four system templates are mirrored, not only seeded.</b> Their classic grants are
 * managed by {@code EnsureSystemRoleTemplatesScript}, which also soft-deletes grants it drops, so
 * their GO Access rows follow it exactly: updated to the grant's tier and deleted when the grant is
 * gone. Personal roles inherit from the templates at runtime, where
 * {@code GoAccessLegacyMirror} keeps their rows in step.</p>
 *
 * <p><b>When it runs.</b> On every {@code update.database} (no execution limits, the statements are
 * idempotent), after DBSM has applied the model so the table exists, and after
 * {@code EnsureSystemRoleTemplatesScript} (alphabetical order of the module's scripts; a missed
 * ordering only delays the template rows to the next run).</p>
 *
 * <p>The feature table below is a literal copy of {@code GoFeature}: module scripts cannot import
 * module classes. {@code SeedGoAccessScriptTest} asserts that it matches the enum.</p>
 */
public class SeedGoAccessScript extends ModuleScript {

  /** Kind of the legacy record: a window. */
  static final String WINDOW = "W";
  /** Kind of the legacy record: an OBUIAPP process. */
  static final String PROCESS = "P";

  /** Feature id, legacy kind, legacy record id. Keep in sync with {@code GoFeature}. */
  static final String[][] FEATURES = {
      { "fiscal-models", WINDOW, "3E8FEA1EA7404D979306C9EE7FD2E7E8" },
      { "fiscal-monitor", WINDOW, "FEF76C3E0F104F06A89AAD15A4A4A35C" },
      { "not-posted-documents", PROCESS, "D6AB95CE52D34E1599590526115E26C6" },
      { "financial-reports", WINDOW, "D647D118F5014D00AF47A636B2CD0DD3" },
      { "smart-scan", WINDOW, "33705E0F52874D91B0BB2FF8BB648B8E" },
      { "inventory-stock-report", WINDOW, "6346B88619F948F9A42224BDB0B239FA" } };

  /** The four system template role ids. Keep in sync with {@code SystemRoleTemplates}. */
  static final String[] TEMPLATE_ROLE_IDS = { "B88A34B5D1874F8685FA6F3C3A609412",
      "15ECC46CFBD74CF3A76D1F4DC8BA9F80", "5E279F5102F9410F9B8CCBA424741F46",
      "73581A7B4F414A2C9059C83CE7BE97BF" };

  private static final String TEMPLATES_SQL = "('" + String.join("','", TEMPLATE_ROLE_IDS) + "')";

  /** Placeholders: feature, legacy id. {t}/{c} are replaced per legacy kind. */
  private static final String SEED_SQL = "INSERT INTO etgo_go_access (etgo_go_access_id,"
      + " ad_client_id, ad_org_id, isactive, created, createdby, updated, updatedby, ad_role_id,"
      + " feature, isreadwrite) SELECT get_uuid(), r.ad_client_id, r.ad_org_id, 'Y', now(), '0',"
      + " now(), '0', r.ad_role_id, ?, MAX(g.isreadwrite) FROM ad_role r JOIN {t} g"
      + " ON g.ad_role_id = r.ad_role_id AND g.isactive = 'Y' AND g.{c} = ?"
      + " WHERE r.isactive = 'Y' GROUP BY r.ad_role_id, r.ad_client_id, r.ad_org_id"
      + " ON CONFLICT ON CONSTRAINT etgo_goacc_role_feature_un DO NOTHING";

  /** Placeholders: legacy id, feature. */
  private static final String MIRROR_UPDATE_SQL = "UPDATE etgo_go_access a SET isactive = 'Y',"
      + " isreadwrite = t.rw, updated = now(), updatedby = '0' FROM (SELECT g.ad_role_id,"
      + " MAX(g.isreadwrite) AS rw FROM {t} g WHERE g.isactive = 'Y' AND g.{c} = ?"
      + " AND g.ad_role_id IN " + TEMPLATES_SQL + " GROUP BY g.ad_role_id) t"
      + " WHERE a.ad_role_id = t.ad_role_id AND a.feature = ?"
      + " AND (a.isreadwrite <> t.rw OR a.isactive <> 'Y')";

  /** Placeholders: feature, legacy id. */
  private static final String MIRROR_DELETE_SQL = "DELETE FROM etgo_go_access a WHERE a.feature = ?"
      + " AND a.ad_role_id IN " + TEMPLATES_SQL + " AND NOT EXISTS (SELECT 1 FROM {t} g"
      + " WHERE g.ad_role_id = a.ad_role_id AND g.isactive = 'Y' AND g.{c} = ?)";

  @Override
  public void execute() {
    try {
      seed(getConnectionProvider());
    } catch (Exception e) {
      handleError(e);
    }
  }

  /**
   * Seeds and mirrors every feature.
   *
   * @param cp the connection provider
   * @return the number of rows inserted, updated or deleted
   * @throws Exception when a statement fails
   */
  int seed(ConnectionProvider cp) throws Exception {
    int changed = 0;
    for (String[] feature : FEATURES) {
      changed += run(cp, SEED_SQL, feature, feature[0], feature[2]);
      changed += run(cp, MIRROR_UPDATE_SQL, feature, feature[2], feature[0]);
      changed += run(cp, MIRROR_DELETE_SQL, feature, feature[0], feature[2]);
    }
    return changed;
  }

  private static int run(ConnectionProvider cp, String sql, String[] feature, String first,
      String second) throws Exception {
    boolean window = WINDOW.equals(feature[1]);
    String resolved = sql.replace("{t}", window ? "ad_window_access" : "obuiapp_process_access")
        .replace("{c}", window ? "ad_window_id" : "obuiapp_process_id");
    try (PreparedStatement ps = cp.getPreparedStatement(resolved)) {
      ps.setString(1, first);
      ps.setString(2, second);
      return ps.executeUpdate();
    }
  }
}
