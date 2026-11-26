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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.hibernate.Session;
import org.hibernate.query.NativeQuery;
import org.junit.Test;
import org.mockito.MockedStatic;
import org.openbravo.dal.service.OBDal;

import com.etendoerp.go.schemaforge.util.GoFeature;
import com.etendoerp.go.schemaforge.util.RoleAccessMatrix;

/**
 * The GO Access mirror (ETP-4802): a classic grant change of one of the six legacy records
 * overwrites that role's GO Access row of the feature (inserted, same tier, or deleted), and only
 * for the features whose classic grant changed.
 *
 * @covers com.etendoerp.go.roles.goaccess.GoAccessLegacyMirror
 */
public class GoAccessLegacyMirrorTest {

  private static final String ROLE = "ROLE1";

  @Test
  public void aNewFullGrantCreatesAFullRow() {
    try (Db db = new Db()) {
      GoFeature feature = GoAccessLegacyMirror.onClassicGrantChange(ROLE,
          GoFeature.FISCAL_MODELS.legacyId, true, true, "ACC1");

      assertEquals(GoFeature.FISCAL_MODELS, feature);
      db.assertUpsert(GoFeature.FISCAL_MODELS, "Y");
    }
  }

  @Test
  public void aReadOnlyGrantCreatesAReadOnlyRow() {
    try (Db db = new Db()) {
      GoAccessLegacyMirror.onClassicGrantChange(ROLE, GoFeature.NOT_POSTED_DOCUMENTS.legacyId,
          true, false, "ACC1");

      db.assertUpsert(GoFeature.NOT_POSTED_DOCUMENTS, "N");
    }
  }

  @Test
  public void aRevokedOrDeactivatedGrantDeletesTheRow() {
    try (Db db = new Db()) {
      GoAccessLegacyMirror.onClassicGrantChange(ROLE, GoFeature.FINANCIAL_REPORTS.legacyId,
          false, true, "ACC1");

      db.assertDeleted(GoFeature.FINANCIAL_REPORTS);
    }
  }

  @Test
  public void anotherActiveGrantOfTheSameRecordKeepsTheFeatureAlive() {
    // the table has no unique key: a second active, read-write row survives the revoked one
    try (Db db = new Db()) {
      db.legacyRows.add(new Object[] { GoFeature.SMART_SCAN.legacyId, "Y" });

      GoAccessLegacyMirror.onClassicGrantChange(ROLE, GoFeature.SMART_SCAN.legacyId, false, false,
          "ACC1");

      db.assertUpsert(GoFeature.SMART_SCAN, "Y");
      assertEquals("ACC1", db.excluded.get("excludeId"));
    }
  }

  @Test
  public void aRecordThatIsNotAFeatureAnchorIsIgnored() {
    try (Db db = new Db()) {
      assertNull(GoAccessLegacyMirror.onClassicGrantChange(ROLE, "SOME_OTHER_WINDOW", true, true,
          "ACC1"));

      verify(db.session, never()).createNativeQuery(anyString());
    }
  }

  @Test
  public void syncChangedOnlyTouchesTheFeaturesWhoseClassicGrantChanged() {
    try (Db db = new Db()) {
      Map<String, Map<String, String>> before = new HashMap<>();
      before.put(ROLE, new HashMap<>(Map.of(GoFeature.FISCAL_MODELS.id, RoleAccessMatrix.FULL,
          GoFeature.SMART_SCAN.id, RoleAccessMatrix.FULL,
          GoFeature.FINANCIAL_REPORTS.id, RoleAccessMatrix.FULL)));
      // after the native write: Tax Report downgraded, Smart Scan revoked, Financial Reports
      // unchanged, Inventory Stock newly granted; the role's other GO rows are not looked at
      db.snapshotRows.add(new Object[] { ROLE, GoFeature.FISCAL_MODELS.legacyId, "N" });
      db.snapshotRows.add(new Object[] { ROLE, GoFeature.FINANCIAL_REPORTS.legacyId, "Y" });
      db.snapshotRows.add(new Object[] { ROLE, GoFeature.INVENTORY_STOCK_REPORT.legacyId, "Y" });

      int changed = GoAccessLegacyMirror.syncChanged(before, List.of(ROLE));

      assertEquals(3, changed);
      db.assertUpsert(GoFeature.FISCAL_MODELS, "N");
      db.assertDeleted(GoFeature.SMART_SCAN);
      db.assertUpsert(GoFeature.INVENTORY_STOCK_REPORT, "Y");
      assertTrue(!db.deleted.contains(GoFeature.FINANCIAL_REPORTS.id));
      assertTrue(!db.upserts.containsKey(GoFeature.FINANCIAL_REPORTS.id));
    }
  }

  @Test
  public void snapshotListsEveryRoleEvenWithoutGrants() {
    try (Db db = new Db()) {
      db.snapshotRows.add(new Object[] { "R1", GoFeature.SMART_SCAN.legacyId, "N" });

      Map<String, Map<String, String>> snapshot = GoAccessLegacyMirror
          .snapshot(List.of("R1", "R2"));

      assertEquals(Map.of(GoFeature.SMART_SCAN.id, RoleAccessMatrix.READ_ONLY), snapshot.get("R1"));
      assertTrue(snapshot.get("R2").isEmpty());
      assertTrue(GoAccessLegacyMirror.snapshot(List.of()).isEmpty());
    }
  }

  /** A mocked session that records the GO Access writes and answers the legacy-grant reads. */
  private static final class Db implements AutoCloseable {
    final MockedStatic<OBDal> dal = mockStatic(OBDal.class);
    final Session session = mock(Session.class);
    final List<Object[]> legacyRows = new ArrayList<>();
    final List<Object[]> snapshotRows = new ArrayList<>();
    final Map<String, Object> excluded = new HashMap<>();
    final Map<String, String> upserts = new HashMap<>();
    final List<String> deleted = new ArrayList<>();

    @SuppressWarnings({ "unchecked", "rawtypes" })
    Db() {
      OBDal obDal = mock(OBDal.class);
      dal.when(OBDal::getInstance).thenReturn(obDal);
      when(obDal.getSession()).thenReturn(session);
      when(session.createNativeQuery(anyString())).thenAnswer(invocation -> {
        String sql = invocation.getArgument(0);
        NativeQuery query = mock(NativeQuery.class);
        Map<String, Object> params = new HashMap<>();
        when(query.setParameter(anyString(), any())).thenAnswer(p -> {
          params.put(p.getArgument(0), p.getArgument(1));
          return query;
        });
        when(query.setParameterList(anyString(), anyCollection())).thenReturn(query);
        when(query.list()).thenAnswer(p -> {
          excluded.putAll(params);
          return sql.contains("SELECT x.ad_role_id") ? snapshotRows : legacyRows;
        });
        when(query.executeUpdate()).thenAnswer(p -> {
          if (sql.startsWith("DELETE")) {
            deleted.add((String) params.get("feature"));
          } else {
            upserts.put((String) params.get("feature"), (String) params.get("rw"));
          }
          return 1;
        });
        return query;
      });
    }

    void assertUpsert(GoFeature feature, String readWrite) {
      assertEquals(feature.id + " row", readWrite, upserts.get(feature.id));
    }

    void assertDeleted(GoFeature feature) {
      assertTrue(feature.id + " row deleted", deleted.contains(feature.id));
    }

    @Override
    public void close() {
      dal.close();
    }
  }
}
