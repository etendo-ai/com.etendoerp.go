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
 * All portions are Copyright (C) 2021-2026 FUTIT SERVICES, S.L
 * All Rights Reserved.
 * Contributor(s): Futit Services S.L.
 * *************************************************************************
 */
package com.etendoerp.go.roles.goaccess;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import java.util.List;

import org.hibernate.query.NativeQuery;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.openbravo.base.provider.OBProvider;
import org.openbravo.base.weld.test.WeldBaseTest;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBDal;
import org.openbravo.client.application.Process;
import org.openbravo.client.application.ProcessAccess;
import org.openbravo.model.ad.access.Role;
import org.openbravo.model.ad.access.WindowAccess;
import org.openbravo.model.ad.system.Client;
import org.openbravo.model.ad.ui.Window;
import org.openbravo.model.common.enterprise.Organization;

import com.etendoerp.go.roles.SystemRoleTemplates;

/**
 * Real-database behaviour of the GO Access mirror when classic grants are written through the DAL:
 * a window and a process access row on a feature anchor are saved, downgraded, moved to another
 * role and deleted, the role being created in the same transaction, and the resulting
 * {@code ETGO_GO_ACCESS} rows are checked. Everything is rolled back.
 *
 * @covers com.etendoerp.go.roles.goaccess.GoAccessLegacyMirrorObserver
 */
public class GoAccessLegacyMirrorObserverIntegrationTest extends WeldBaseTest {

  private static final String WINDOW_FEATURE = "fiscal-models";
  private static final String WINDOW_ID = "3E8FEA1EA7404D979306C9EE7FD2E7E8";
  private static final String PROCESS_FEATURE = "not-posted-documents";
  private static final String PROCESS_ID = "D6AB95CE52D34E1599590526115E26C6";

  @Before
  public void adminMode() {
    setTestUserContext();
    OBContext.setAdminMode(true);
  }

  @After
  public void rollbackChanges() {
    while (OBContext.getOBContext() != null
        && OBContext.getOBContext().isInAdministratorMode()) {
      OBContext.restorePreviousMode();
    }
    OBDal.getInstance().rollbackAndClose();
  }

  @Test
  public void aWindowGrantIsMirroredThroughSaveDowngradeMoveAndDelete() {
    Role first = role();
    Role second = role();
    WindowAccess access = windowAccess(first, true);
    OBDal.getInstance().flush();
    assertEquals("Y", goAccess(first, WINDOW_FEATURE));

    access.setEditableField(false);
    OBDal.getInstance().save(access);
    OBDal.getInstance().flush();
    assertEquals("N", goAccess(first, WINDOW_FEATURE));

    access.setRole(second);
    OBDal.getInstance().save(access);
    OBDal.getInstance().flush();
    assertNull("the pair the grant left loses its row", goAccess(first, WINDOW_FEATURE));
    assertEquals("N", goAccess(second, WINDOW_FEATURE));

    OBDal.getInstance().remove(access);
    OBDal.getInstance().flush();
    assertNull(goAccess(second, WINDOW_FEATURE));
  }

  @Test
  public void aProcessGrantIsMirroredThroughSaveAndDelete() {
    Role role = role();
    ProcessAccess access = OBProvider.getInstance().get(ProcessAccess.class);
    access.setNewOBObject(true);
    access.setClient(role.getClient());
    access.setOrganization(role.getOrganization());
    access.setActive(true);
    access.setRole(role);
    access.setObuiappProcess(OBDal.getInstance().get(Process.class, PROCESS_ID));
    access.setEditableField(true);
    OBDal.getInstance().save(access);
    OBDal.getInstance().flush();
    assertEquals("Y", goAccess(role, PROCESS_FEATURE));

    OBDal.getInstance().remove(access);
    OBDal.getInstance().flush();
    assertNull(goAccess(role, PROCESS_FEATURE));
  }

  private Role role() {
    return role(true);
  }

  /**
   * A role and its grant saved in one transaction with a single flush: the role is not in the
   * database yet when the observer runs, so the mirror (which never flushes inside the event)
   * cannot reference it. The classic grant is still written; the GO row is then settled by the
   * next seed, and the legacy fallback of {@code GoFeatureAccess} decides until then.
   */
  @Test
  public void aGrantSavedWithItsNewRoleBeforeAnyFlushStillPersists() {
    Role role = role(false);
    WindowAccess access = windowAccess(role, true);
    OBDal.getInstance().flush();
    OBDal.getInstance().refresh(access);
    assertEquals(role.getId(), access.getRole().getId());
    assertEquals(true, access.isActive());
  }

  private Role role(boolean flush) {
    Role role = OBProvider.getInstance().get(Role.class);
    role.setNewOBObject(true);
    role.setClient(OBDal.getInstance().get(Client.class, OBContext.getOBContext().getCurrentClient().getId()));
    role.setOrganization(OBDal.getInstance().get(Organization.class, "0"));
    role.setActive(true);
    role.setName("GO Access mirror test " + System.nanoTime());
    role.setUserLevel(SystemRoleTemplates.FIXED_ROLE_USER_LEVEL);
    role.setManual(true);
    role.setTemplate(false);
    role.setClientAdmin(false);
    OBDal.getInstance().save(role);
    if (flush) {
      OBDal.getInstance().flush();
    }
    return role;
  }

  private WindowAccess windowAccess(Role role, boolean readWrite) {
    WindowAccess access = OBProvider.getInstance().get(WindowAccess.class);
    access.setNewOBObject(true);
    access.setClient(role.getClient());
    access.setOrganization(role.getOrganization());
    access.setActive(true);
    access.setRole(role);
    access.setWindow(OBDal.getInstance().get(Window.class, WINDOW_ID));
    access.setEditableField(readWrite);
    OBDal.getInstance().save(access);
    return access;
  }

  @SuppressWarnings("rawtypes")
  private String goAccess(Role role, String feature) {
    NativeQuery query = OBDal.getInstance().getSession().createNativeQuery(
        "SELECT isreadwrite FROM etgo_go_access WHERE ad_role_id = :role AND feature = :feature"
            + " AND isactive = 'Y'");
    List<?> rows = query.setParameter("role", role.getId()).setParameter("feature", feature)
        .list();
    return rows.isEmpty() ? null : String.valueOf(rows.get(0)).trim();
  }
}
