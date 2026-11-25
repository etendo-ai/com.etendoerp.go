/*
 * *************************************************************************
 * Etendo License. See https://github.com/etendosoftware/etendo_core/blob/main/legal/Etendo_license.txt
 * *************************************************************************
 */
package com.etendoerp.go.modulescript;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.openbravo.database.ConnectionProvider;

import com.etendoerp.go.schemaforge.util.GoFeature;

/**
 * ETP-4802 — the GO Access seed copies each role's classic proxy/anchor grant at the same tier,
 * skips inactive grants, never overwrites an existing row, and its inlined feature table matches
 * {@code GoFeature}.
 *
 * <p>Like {@code SeedAllowedPlanChangesScriptTest}, the script is compiled from source into a temp
 * directory and driven through a mocked {@link ConnectionProvider}: no database, no build step.
 *
 * @covers com.etendoerp.go.modulescript.SeedGoAccessScript
 */
class SeedGoAccessScriptTest {

  private static final Path SCRIPT_RELATIVE = Paths.get("src-util", "modulescript", "src", "com",
      "etendoerp", "go", "modulescript", "SeedGoAccessScript.java");

  @TempDir
  static Path classesDir;

  private static Path moduleRoot;
  private static Class<?> scriptClass;

  @BeforeAll
  static void compileScript() throws Exception {
    Path here = Paths.get("");
    Optional<Path> root = List.of(here, here.resolve(Paths.get("modules", "com.etendoerp.go")))
        .stream()
        .filter(candidate -> Files.isRegularFile(candidate.resolve(SCRIPT_RELATIVE)))
        .findFirst();
    Assumptions.assumeTrue(root.isPresent(),
        () -> "Skipping: com.etendoerp.go module root not found from " + here.toAbsolutePath());
    moduleRoot = root.orElseThrow();
    JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
    Assumptions.assumeTrue(compiler != null, "Skipping: no system Java compiler (JRE only)");

    int status = compiler.run(null, null, null, "-proc:none", "-encoding", "UTF-8", "-classpath",
        System.getProperty("java.class.path"), "-d", classesDir.toString(),
        moduleRoot.resolve(SCRIPT_RELATIVE).toString());
    assertEquals(0, status, "SeedGoAccessScript.java must compile");

    URLClassLoader loader = new URLClassLoader(new URL[] { classesDir.toUri().toURL() },
        SeedGoAccessScriptTest.class.getClassLoader());
    scriptClass = Class.forName("com.etendoerp.go.modulescript.SeedGoAccessScript", true, loader);
  }

  @Test
  void seedsEveryFeatureFromItsLegacyGrantAndSumsTheInsertedRows() throws Exception {
    ConnectionProvider cp = mock(ConnectionProvider.class);
    PreparedStatement windowSeed = mock(PreparedStatement.class);
    PreparedStatement processSeed = mock(PreparedStatement.class);
    when(cp.getPreparedStatement(constant("WINDOW_SEED_SQL"))).thenReturn(windowSeed);
    when(cp.getPreparedStatement(constant("PROCESS_SEED_SQL"))).thenReturn(processSeed);
    when(windowSeed.executeUpdate()).thenReturn(2, 0, 1, 0, 3);
    when(processSeed.executeUpdate()).thenReturn(4);

    assertEquals(10, seed(cp));

    verify(windowSeed, times(5)).executeUpdate();
    verify(processSeed, times(1)).executeUpdate();
    verify(processSeed).setString(1, "not-posted-documents");
    verify(processSeed).setString(2, "D6AB95CE52D34E1599590526115E26C6");
    verify(processSeed).setString(3, "not-posted-documents");
  }

  @Test
  void aSecondRunInsertsNothing() throws Exception {
    ConnectionProvider cp = mock(ConnectionProvider.class);
    PreparedStatement none = mock(PreparedStatement.class);
    when(cp.getPreparedStatement(constant("WINDOW_SEED_SQL"))).thenReturn(none);
    when(cp.getPreparedStatement(constant("PROCESS_SEED_SQL"))).thenReturn(none);
    when(none.executeUpdate()).thenReturn(0);

    assertEquals(0, seed(cp));
  }

  @Test
  void aFailingStatementPropagatesSoTheBuildFails() throws Exception {
    ConnectionProvider cp = mock(ConnectionProvider.class);
    PreparedStatement failing = mock(PreparedStatement.class);
    when(cp.getPreparedStatement(constant("WINDOW_SEED_SQL"))).thenReturn(failing);
    when(failing.executeUpdate()).thenThrow(new SQLException("relation does not exist"));

    InvocationTargetException thrown = Assertions.assertThrows(InvocationTargetException.class,
        () -> seedReflective(cp));
    assertTrue(thrown.getCause() instanceof SQLException);
  }

  @Test
  void theSeedCopiesTheTierSkipsInactiveGrantsAndNeverOverwritesARow() throws Exception {
    for (String name : List.of("WINDOW_SEED_SQL", "PROCESS_SEED_SQL")) {
      String sql = constant(name).replaceAll("\\s+", " ");
      assertTrue(sql.contains("s.isreadwrite"), "same tier as the legacy row: " + sql);
      assertTrue(sql.contains(".isactive = 'Y'"), "inactive legacy rows are not seeded: " + sql);
      assertTrue(sql.contains("WHERE NOT EXISTS (SELECT 1 FROM etgo_go_access g"
          + " WHERE g.ad_role_id = s.ad_role_id AND g.feature = ?)"),
          "an existing row (active or not) is never touched: " + sql);
      assertTrue(sql.contains("DISTINCT ON (") && sql.contains("ORDER BY"), sql);
      assertFalse(sql.contains("UPDATE") || sql.contains("DELETE"), sql);
    }
    assertTrue(constant("WINDOW_SEED_SQL").contains("FROM ad_window_access"));
    assertTrue(constant("PROCESS_SEED_SQL").contains("FROM obuiapp_process_access"));
  }

  @Test
  void theInlinedFeatureTableMatchesGoFeature() throws Exception {
    Set<String> seeded = new HashSet<>();
    for (String[] f : table("WINDOW_FEATURES")) {
      GoFeature feature = GoFeature.forId(f[0]);
      assertEquals(GoFeature.LegacyKind.WINDOW, feature.legacyKind, f[0]);
      assertEquals(feature.legacyId, f[1], f[0]);
      seeded.add(f[0]);
    }
    for (String[] f : table("PROCESS_FEATURES")) {
      GoFeature feature = GoFeature.forId(f[0]);
      assertEquals(GoFeature.LegacyKind.OBUIAPP_PROCESS, feature.legacyKind, f[0]);
      assertEquals(feature.legacyId, f[1], f[0]);
      seeded.add(f[0]);
    }
    assertEquals(GoFeature.ids(), seeded);
  }

  @Test
  void theCompiledScriptIsCommittedSoUpdateDatabaseRunsIt() {
    Path compiled = moduleRoot.resolve(Paths.get("build", "classes", "com", "etendoerp", "go",
        "modulescript", "SeedGoAccessScript.class"));
    assertTrue(Files.isRegularFile(compiled),
        "update.database only runs compiled module scripts and a deploy never compiles them: run "
            + "./gradlew compile.modulescript -Dmodule=com.etendoerp.go and git add -f " + compiled);
  }

  private static String constant(String name) throws Exception {
    Field field = scriptClass.getDeclaredField(name);
    field.setAccessible(true);
    return (String) field.get(null);
  }

  private static String[][] table(String name) throws Exception {
    Field field = scriptClass.getDeclaredField(name);
    field.setAccessible(true);
    return (String[][]) field.get(null);
  }

  private static int seed(ConnectionProvider cp) throws Exception {
    try {
      return seedReflective(cp);
    } catch (InvocationTargetException e) {
      throw (Exception) e.getCause();
    }
  }

  private static int seedReflective(ConnectionProvider cp) throws Exception {
    Object script = scriptClass.getDeclaredConstructor().newInstance();
    Method method = scriptClass.getDeclaredMethod("seed", ConnectionProvider.class);
    method.setAccessible(true);
    return (int) method.invoke(script, cp);
  }
}
