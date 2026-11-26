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

package com.etendoerp.go.roles;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.hibernate.Session;
import org.hibernate.query.NativeQuery;
import org.junit.Test;
import org.mockito.MockedStatic;
import org.openbravo.dal.service.OBDal;

import com.etendoerp.go.roles.goaccess.GoAccessLegacyMirror;

/**
 * The native sweep rewrites the classic grants with SQL the DAL observer never sees (ETP-4802), so
 * it must take a snapshot of the GO features' grants before the first statement and mirror the
 * difference after the last one; a sweep that skips either leaves a stale GO Access row.
 *
 * @covers com.etendoerp.go.roles.TemplateAccessPropagationService
 */
public class TemplateAccessPropagationServiceTest {

  @SuppressWarnings({ "unchecked", "rawtypes" })
  @Test
  public void aSweepSnapshotsTheGrantsBeforeAndMirrorsTheChangesAfterTheNativeStatements() {
    List<String> events = new ArrayList<>();
    Session session = mock(Session.class);
    NativeQuery query = mock(NativeQuery.class);
    when(session.createNativeQuery(anyString())).thenAnswer(invocation -> {
      events.add("sql");
      return query;
    });
    when(query.executeUpdate()).thenReturn(0);
    when(query.list()).thenReturn(List.of());
    OBDal dal = mock(OBDal.class);
    when(dal.getSession()).thenReturn(session);
    Map<String, Map<String, String>> before = Map.of("ROLE1", Map.of("fiscal-models", "Y"));

    try (MockedStatic<OBDal> obDal = mockStatic(OBDal.class);
        MockedStatic<GoAccessLegacyMirror> mirror = mockStatic(GoAccessLegacyMirror.class)) {
      obDal.when(OBDal::getInstance).thenReturn(dal);
      mirror.when(() -> GoAccessLegacyMirror.snapshot(anyCollection())).thenAnswer(invocation -> {
        events.add("snapshot");
        return before;
      });
      mirror.when(() -> GoAccessLegacyMirror.syncChanged(any(), anyCollection()))
          .thenAnswer(invocation -> {
            events.add("sync");
            return 1;
          });

      new TemplateAccessPropagationService().sweepRoles(List.of("ROLE1"));

      mirror.verify(() -> GoAccessLegacyMirror.snapshot(List.of("ROLE1")));
      mirror.verify(() -> GoAccessLegacyMirror.syncChanged(before, List.of("ROLE1")));
      assertEquals("snapshot", events.get(0));
      assertEquals("sync", events.get(events.size() - 1));
      assertTrue("the native statements run between the two", events.contains("sql"));
    }
  }
}
