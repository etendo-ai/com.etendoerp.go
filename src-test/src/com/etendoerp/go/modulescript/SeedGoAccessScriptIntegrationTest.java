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
package com.etendoerp.go.modulescript;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.UUID;

import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;

import org.hibernate.query.NativeQuery;
import org.junit.After;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import org.openbravo.service.db.DalConnectionProvider;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBDal;
import org.openbravo.test.base.OBBaseTest;

import com.etendoerp.go.roles.SystemRoleTemplates;

/**
 * ETP-4802 — real-database behaviour of {@code SeedGoAccessScript}: the GO Access row a role ends
 * up with matches its classic grant (same read/write tier, none without a grant), inactive grants,
 * inactive roles and other clients are handled, a second run adds nothing, an administrator's row
 * is never overwritten, and the system templates' rows are mirrored (updated and deleted).
 *
 * <p>Every fixture is written with native SQL on the rows of the sample data and rolled back, so
 * nothing is committed. The script is compiled in memory from its source, which is not on the test
 * classpath (module scripts are built apart).</p>
 *
 * @covers com.etendoerp.go.modulescript.SeedGoAccessScript
 */
public class SeedGoAccessScriptIntegrationTest extends OBBaseTest {

  private static final String WINDOW_FEATURE = "fiscal-models";
  private static final String WINDOW_ID = "3E8FEA1EA7404D979306C9EE7FD2E7E8";
  private static final String PROCESS_FEATURE = "not-posted-documents";
  private static final String PROCESS_ID = "D6AB95CE52D34E1599590526115E26C6";

  private static Class<?> scriptClass;

  @BeforeClass
  public static void compileScript() throws Exception {
    Path relative = Paths.get("src-util", "modulescript", "src", "com", "etendoerp", "go",
        "modulescript", "SeedGoAccessScript.java");
    Path here = Paths.get("");
    Path root = null;
    for (Path candidate : List.of(here, here.resolve(Paths.get("modules", "com.etendoerp.go")))) {
      if (Files.isRegularFile(candidate.resolve(relative))) {
        root = candidate;
      }
    }
    assertTrue("com.etendoerp.go module root not found from " + here.toAbsolutePath(),
        root != null);
    JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
    assertTrue("a JDK is required to compile SeedGoAccessScript", compiler != null);
    Path out = Files.createTempDirectory("seed-go-access");
    assertEquals(0, compiler.run(null, null, null, "-proc:none", "-encoding", "UTF-8",
        "-classpath", System.getProperty("java.class.path"), "-d", out.toString(),
        root.resolve(relative).toString()));
    URLClassLoader loader = new URLClassLoader(new URL[] { out.toUri().toURL() },
        SeedGoAccessScriptIntegrationTest.class.getClassLoader());
    scriptClass = Class.forName("com.etendoerp.go.modulescript.SeedGoAccessScript", true, loader);
  }

  @Before
  public void adminContext() {
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
  public void aRoleGetsItsLegacyTierAndARoleWithoutGrantGetsNothing() throws Exception {
    String readOnly = role(TEST_CLIENT_ID, "Y");
    String readWrite = role(TEST_CLIENT_ID, "Y");
    String noGrant = role(TEST_CLIENT_ID, "Y");
    windowGrant(readOnly, "Y", "N");
    windowGrant(readWrite, "Y", "Y");

    seed();

    assertEquals("N", goAccess(readOnly, WINDOW_FEATURE));
    assertEquals("Y", goAccess(readWrite, WINDOW_FEATURE));
    assertNull(goAccess(noGrant, WINDOW_FEATURE));
  }

  @Test
  public void theNotPostedProcessTierIsCopiedFromTheProcessGrant() throws Exception {
    String readOnly = role(TEST_CLIENT_ID, "Y");
    String readWrite = role(TEST_CLIENT_ID, "Y");
    String noGrant = role(TEST_CLIENT_ID, "Y");
    processGrant(readOnly, "Y", "N");
    processGrant(readWrite, "Y", "Y");

    seed();

    assertEquals("N", goAccess(readOnly, PROCESS_FEATURE));
    assertEquals("Y", goAccess(readWrite, PROCESS_FEATURE));
    assertNull(goAccess(noGrant, PROCESS_FEATURE));
  }

  @Test
  public void inactiveGrantsAndInactiveRolesAreNotSeeded() throws Exception {
    String inactiveGrant = role(TEST_CLIENT_ID, "Y");
    String inactiveRole = role(TEST_CLIENT_ID, "N");
    windowGrant(inactiveGrant, "N", "Y");
    windowGrant(inactiveRole, "Y", "Y");

    seed();

    assertNull(goAccess(inactiveGrant, WINDOW_FEATURE));
    assertNull(goAccess(inactiveRole, WINDOW_FEATURE));
  }

  @Test
  public void aRoleOfAnotherClientKeepsItsOwnClient() throws Exception {
    List<String> clients = strings("SELECT ad_client_id FROM ad_client WHERE ad_client_id NOT IN "
        + "('0', '" + TEST_CLIENT_ID + "') ORDER BY ad_client_id LIMIT 1");
    assertEquals("the sample data must hold a second client", 1, clients.size());
    String otherClient = clients.get(0);
    String other = role(otherClient, "Y");
    windowGrant(other, "Y", "Y");

    seed();

    assertEquals("Y", goAccess(other, WINDOW_FEATURE));
    assertEquals(otherClient, string("SELECT ad_client_id FROM etgo_go_access WHERE "
        + "ad_role_id = '" + other + "' AND feature = '" + WINDOW_FEATURE + "'"));
  }

  @Test
  public void aSecondRunAddsNothingAndAnAdministratorRowIsNotOverwritten() throws Exception {
    String admin = role(TEST_CLIENT_ID, "Y");
    String plain = role(TEST_CLIENT_ID, "Y");
    windowGrant(admin, "Y", "Y");
    windowGrant(plain, "Y", "Y");
    goAccessRow(admin, WINDOW_FEATURE, "N", "N");

    seed();
    int second = seed();

    assertEquals("an inactive administrator row is neither revived nor changed", "N",
        goAccess(admin, WINDOW_FEATURE));
    assertEquals("N", string("SELECT isactive FROM etgo_go_access WHERE ad_role_id = '" + admin
        + "' AND feature = '" + WINDOW_FEATURE + "'"));
    assertEquals("Y", goAccess(plain, WINDOW_FEATURE));
    assertEquals(1, count("SELECT count(*) FROM etgo_go_access WHERE ad_role_id = '" + plain
        + "' AND feature = '" + WINDOW_FEATURE + "'"));
    assertEquals("the second run changes no row", 0, second);
  }

  @Test
  public void aTemplateRowFollowsItsGrantUpdateAndDelete() throws Exception {
    String template = SystemRoleTemplates.FINANCE_ROLE_ID;
    assertEquals("the system template must exist in the sample data", 1,
        count("SELECT count(*) FROM ad_role WHERE ad_role_id = '" + template + "'"));
    exec("DELETE FROM ad_window_access WHERE ad_role_id = '" + template + "' AND ad_window_id = '"
        + WINDOW_ID + "'");
    exec("DELETE FROM obuiapp_process_access WHERE ad_role_id = '" + template
        + "' AND obuiapp_process_id = '" + PROCESS_ID + "'");
    exec("DELETE FROM etgo_go_access WHERE ad_role_id = '" + template + "' AND feature IN ('"
        + WINDOW_FEATURE + "', '" + PROCESS_FEATURE + "')");
    windowGrant(template, "Y", "Y");
    goAccessRow(template, WINDOW_FEATURE, "N", "Y");
    goAccessRow(template, PROCESS_FEATURE, "Y", "Y");

    seed();

    assertEquals("the template row is updated to the grant's tier", "Y",
        goAccess(template, WINDOW_FEATURE));
    assertNull("the template row of a dropped grant is deleted",
        goAccess(template, PROCESS_FEATURE));
  }

  // ---------------------------------------------------------------------

  private int seed() throws Exception {
    OBDal.getInstance().flush();
    Object script = scriptClass.getDeclaredConstructor().newInstance();
    Method method = scriptClass.getDeclaredMethod("seed",
        org.openbravo.database.ConnectionProvider.class);
    method.setAccessible(true);
    return (int) method.invoke(script, new DalConnectionProvider(false));
  }

  private String role(String clientId, String active) {
    String id = uuid();
    exec("INSERT INTO ad_role (ad_role_id, ad_client_id, ad_org_id, isactive, created, createdby, "
        + "updated, updatedby, name, userlevel, ismanual, is_client_admin, istemplate) VALUES ('"
        + id + "', '" + clientId + "', '0', '" + active + "', now(), '0', now(), '0', 'S4802 "
        + id.substring(0, 8) + "', '  O', 'Y', 'N', 'N')");
    return id;
  }

  private void windowGrant(String roleId, String active, String readWrite) {
    grant("ad_window_access", "ad_window_id", roleId, WINDOW_ID, active, readWrite);
  }

  private void processGrant(String roleId, String active, String readWrite) {
    grant("obuiapp_process_access", "obuiapp_process_id", roleId, PROCESS_ID, active, readWrite);
  }

  private void grant(String table, String element, String roleId, String elementId, String active,
      String readWrite) {
    String client = string("SELECT ad_client_id FROM ad_role WHERE ad_role_id = '" + roleId + "'");
    exec("INSERT INTO " + table + " (" + table + "_id, ad_client_id, ad_org_id, isactive, created, "
        + "createdby, updated, updatedby, ad_role_id, " + element + ", isreadwrite) VALUES ('"
        + uuid() + "', '" + client + "', '0', '" + active + "', now(), '0', now(), '0', '" + roleId
        + "', '" + elementId + "', '" + readWrite + "')");
  }

  private void goAccessRow(String roleId, String feature, String readWrite, String active) {
    String client = string("SELECT ad_client_id FROM ad_role WHERE ad_role_id = '" + roleId + "'");
    exec("INSERT INTO etgo_go_access (etgo_go_access_id, ad_client_id, ad_org_id, isactive, "
        + "created, createdby, updated, updatedby, ad_role_id, feature, isreadwrite) VALUES ('"
        + uuid() + "', '" + client + "', '0', '" + active + "', now(), '0', now(), '0', '"
        + roleId + "', '" + feature + "', '" + readWrite + "')");
  }

  /** {@code IsReadWrite} of the role's GO Access row for the feature, or null when it has none. */
  private String goAccess(String roleId, String feature) {
    List<String> rows = strings("SELECT isreadwrite FROM etgo_go_access WHERE ad_role_id = '"
        + roleId + "' AND feature = '" + feature + "'");
    assertTrue("At most one row per role and feature", rows.size() <= 1);
    return rows.isEmpty() ? null : rows.get(0);
  }

  private static String uuid() {
    return UUID.randomUUID().toString().replace("-", "").toUpperCase();
  }

  private static void exec(String sql) {
    OBDal.getInstance().getSession().createNativeQuery(sql).executeUpdate();
  }

  private static String string(String sql) {
    return (String) OBDal.getInstance().getSession().createNativeQuery(sql).getSingleResult();
  }

  private static int count(String sql) {
    return ((Number) OBDal.getInstance().getSession().createNativeQuery(sql).getSingleResult())
        .intValue();
  }

  @SuppressWarnings("unchecked")
  private static List<String> strings(String sql) {
    NativeQuery<?> query = OBDal.getInstance().getSession().createNativeQuery(sql);
    return (List<String>) query.list();
  }
}
