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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.PreparedStatement;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.openbravo.database.ConnectionProvider;

import com.etendoerp.go.roles.SystemRoleTemplates;
import com.etendoerp.go.schemaforge.util.GoFeature;

/**
 * Unit tests for {@code SeedGoAccessScript} (ETP-4802), compiled in memory from its real source
 * like {@code EnsureLegacyPlanScriptTest}: the literal feature and template tables must match
 * {@link GoFeature} and {@link SystemRoleTemplates}, and the statements must be idempotent and
 * never overwrite an administrator's row.
 */
class SeedGoAccessScriptTest {

  private static final Path SCRIPT_RELATIVE = Paths.get("src-util", "modulescript", "src", "com",
      "etendoerp", "go", "modulescript", "SeedGoAccessScript.java");

  @TempDir
  static Path classesDir;

  private static Class<?> scriptClass;

  @BeforeAll
  static void compileScript() throws Exception {
    Path here = Paths.get("");
    Optional<Path> root = List.of(here, here.resolve(Paths.get("modules", "com.etendoerp.go")))
        .stream().filter(candidate -> Files.isRegularFile(candidate.resolve(SCRIPT_RELATIVE)))
        .findFirst();
    assertTrue(root.isPresent(),
        "com.etendoerp.go module root not found from " + here.toAbsolutePath());
    JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
    assertTrue(compiler != null, "a JDK is required to compile SeedGoAccessScript");
    int status = compiler.run(null, null, null, "-proc:none", "-encoding", "UTF-8", "-classpath",
        System.getProperty("java.class.path"), "-d", classesDir.toString(),
        root.orElseThrow().resolve(SCRIPT_RELATIVE).toString());
    assertEquals(0, status, "SeedGoAccessScript.java must compile");
    URLClassLoader loader = new URLClassLoader(new URL[] { classesDir.toUri().toURL() },
        SeedGoAccessScriptTest.class.getClassLoader());
    scriptClass = Class.forName("com.etendoerp.go.modulescript.SeedGoAccessScript", true, loader);
  }

  @Test
  void theLiteralFeatureTableMatchesGoFeature() throws Exception {
    String[][] literal = (String[][]) staticField("FEATURES");

    assertEquals(GoFeature.values().length, literal.length);
    for (GoFeature feature : GoFeature.values()) {
      String[] row = null;
      for (String[] candidate : literal) {
        if (candidate[0].equals(feature.id)) {
          row = candidate;
        }
      }
      assertTrue(row != null, feature.id + " is missing from the script's feature table");
      assertEquals(feature.legacyId, row[2], feature.id + " legacy id");
      assertEquals(feature.legacyKind == GoFeature.LegacyKind.WINDOW ? "W" : "P", row[1],
          feature.id + " legacy kind");
    }
  }

  @Test
  void theLiteralTemplateRolesMatchTheSystemTemplates() throws Exception {
    List<String> literal = List.of((String[]) staticField("TEMPLATE_ROLE_IDS"));

    assertEquals(
        List.of(SystemRoleTemplates.FINANCE_ROLE_ID, SystemRoleTemplates.SALES_ROLE_ID,
            SystemRoleTemplates.PURCHASING_ROLE_ID, SystemRoleTemplates.INVENTORY_ROLE_ID),
        literal);
  }

  @Test
  void everyFeatureRunsTheSeedTheMirrorUpdateAndTheMirrorDelete() throws Exception {
    ConnectionProvider cp = mock(ConnectionProvider.class);
    PreparedStatement ps = mock(PreparedStatement.class);
    when(cp.getPreparedStatement(anyString())).thenReturn(ps);
    when(ps.executeUpdate()).thenReturn(1);

    int changed = seed(cp);

    assertEquals(3 * GoFeature.values().length, changed);
    ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
    verify(cp, times(3 * GoFeature.values().length)).getPreparedStatement(sql.capture());
    List<String> statements = new ArrayList<>(sql.getAllValues());
    assertTrue(statements.stream().anyMatch(s -> s.contains("obuiapp_process_access")),
        "the not-posted process feature reads obuiapp_process_access");
    assertTrue(statements.stream().anyMatch(s -> s.contains("ad_window_access g")));
    assertTrue(statements.stream().noneMatch(s -> s.contains("{t}") || s.contains("{c}")),
        "every placeholder is resolved");
  }

  @Test
  void seedingNeverOverwritesAndOnlyTemplatesAreMirrored() throws Exception {
    String seed = field("SEED_SQL").replaceAll("\\s+", " ");
    String update = field("MIRROR_UPDATE_SQL").replaceAll("\\s+", " ");
    String delete = field("MIRROR_DELETE_SQL").replaceAll("\\s+", " ");

    assertTrue(seed.contains("ON CONFLICT ON CONSTRAINT etgo_goacc_role_feature_un DO NOTHING"),
        "a re-run adds nothing and never overwrites a row: " + seed);
    assertTrue(seed.contains("g.isactive = 'Y'") && seed.contains("r.isactive = 'Y'"),
        "inactive grants and inactive roles do not resolve today, so they are not seeded: " + seed);
    assertFalse(seed.contains("DO UPDATE"), seed);
    for (String mirror : List.of(update, delete)) {
      for (String template : (String[]) staticField("TEMPLATE_ROLE_IDS")) {
        assertTrue(mirror.contains("'" + template + "'"),
            "the mirror is limited to the four system templates: " + mirror);
      }
    }
  }

  /**
   * The seed reproduces today's decisions: the row it inserts carries the legacy tier, so the
   * decision before the seed (legacy grant) and after it (GO row) is the same for each case.
   */
  @Test
  void theSeededRowReproducesTheLegacyDecisionForEveryCase() throws Exception {
    String seed = field("SEED_SQL").replaceAll("\\s+", " ");

    // read-only and read-write: the tier is copied from the legacy row, never widened
    assertTrue(seed.contains("MAX(g.isreadwrite)"), seed);
    // no legacy grant: the inner join inserts nothing for that role
    assertTrue(seed.contains("FROM ad_role r JOIN {t} g ON g.ad_role_id = r.ad_role_id"), seed);
    // an inactive legacy row, or an inactive role, never resolved: no row
    assertTrue(seed.contains("g.isactive = 'Y'") && seed.contains("r.isactive = 'Y'"), seed);
    // another client: the row belongs to the role's own client and organisation
    assertTrue(seed.contains("r.ad_client_id, r.ad_org_id"), seed);
    // the feature is matched on its own legacy record only
    assertTrue(seed.contains("g.{c} = ?"), seed);

    ConnectionProvider cp = mock(ConnectionProvider.class);
    PreparedStatement ps = mock(PreparedStatement.class);
    when(cp.getPreparedStatement(anyString())).thenReturn(ps);
    seed(cp);
    for (GoFeature feature : GoFeature.values()) {
      verify(ps, atLeastOnce()).setString(1, feature.id);
      verify(ps, atLeastOnce()).setString(2, feature.legacyId);
    }
  }

  private static int seed(ConnectionProvider cp) throws Exception {
    Object script = scriptClass.getDeclaredConstructor().newInstance();
    Method method = scriptClass.getDeclaredMethod("seed", ConnectionProvider.class);
    method.setAccessible(true);
    return (int) method.invoke(script, cp);
  }

  private static Object staticField(String name) throws Exception {
    Field f = scriptClass.getDeclaredField(name);
    f.setAccessible(true);
    return f.get(null);
  }

  private static String field(String name) throws Exception {
    return (String) staticField(name);
  }
}
