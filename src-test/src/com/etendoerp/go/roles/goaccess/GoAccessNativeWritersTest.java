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

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

/**
 * Guards the mirror's safety rule (ETP-4802): no GO Access row may outlive a revoked classic grant.
 * The DAL observer covers every DAL write, so the only way to bypass it is native SQL that writes
 * {@code AD_Window_Access} or {@code OBUIAPP_Process_Access}. Any production class or module script that does must
 * call {@link GoAccessLegacyMirror}; a new native writer fails this test until it does.
 *
 * @covers com.etendoerp.go.roles.goaccess.GoAccessLegacyMirror
 */
class GoAccessNativeWritersTest {

  private static final Pattern CLASSIC_TABLE = Pattern.compile(
      "ad_window_access|obuiapp_process_access", Pattern.CASE_INSENSITIVE);
  /** A write keyword anywhere in the code, so SQL assembled from templates is caught too. */
  private static final Pattern WRITE_KEYWORD = Pattern.compile(
      "\\b(insert\\s+into|update\\s+[\"\\w]|delete\\s+from)", Pattern.CASE_INSENSITIVE);
  private static final Pattern COMMENTS = Pattern.compile("/\\*.*?\\*/|//[^\\n]*",
      Pattern.DOTALL);
  /** An actual call, not an import or a mention in a comment. */
  private static final Pattern MIRROR_CALL = Pattern.compile(
      "GoAccessLegacyMirror\\s*\\.\\s*(snapshot|syncChanged|onClassicGrantChange)\\s*\\(");
  /** Files that name a classic access table next to a write keyword but never write it. */
  private static final Set<String> CONFIRMED_READ_ONLY = Set.of(
      // the mirror itself: it only reads the classic tables and writes ETGO_GO_ACCESS
      "src/com/etendoerp/go/roles/goaccess/GoAccessLegacyMirror.java",
      // module scripts: each writes grants of its own roles only, and none grants a role a GO
      // feature through a path the mirror must follow
      // templates: re-mirrored by SeedGoAccessScript on every update.database
      "src-util/modulescript/src/com/etendoerp/go/modulescript/EnsureSystemRoleTemplatesScript.java",
      // the seed itself writes ETGO_GO_ACCESS from the classic grants and only reads them
      "src-util/modulescript/src/com/etendoerp/go/modulescript/SeedGoAccessScript.java",
      // grants the support role the Support window and processes, none of which is a GO feature
      "src-util/modulescript/src/com/etendoerp/go/modulescript/EnsureSupportAccessSeedScript.java");

  @Test
  void everyNativeWriterOfAClassicGrantGoesThroughTheMirror() throws IOException {
    Path root = moduleRoot();
    List<String> offenders = new ArrayList<>();
    for (String dir : List.of("src", "src-util/modulescript/src")) {
      try (Stream<Path> files = Files.walk(root.resolve(dir))) {
        for (Path file : (Iterable<Path>) files
            .filter(f -> f.toString().endsWith(".java"))::iterator) {
          String source = COMMENTS.matcher(
              new String(Files.readAllBytes(file), StandardCharsets.UTF_8)).replaceAll("");
          String relative = root.relativize(file).toString();
          if (CLASSIC_TABLE.matcher(source).find() && WRITE_KEYWORD.matcher(source).find()
              && !MIRROR_CALL.matcher(source).find() && !CONFIRMED_READ_ONLY.contains(relative)) {
            offenders.add(relative);
          }
        }
      }
    }
    assertTrue(offenders.isEmpty(),
        "files that name AD_Window_Access / OBUIAPP_Process_Access next to a write keyword and do "
            + "not call GoAccessLegacyMirror.snapshot/syncChanged/onClassicGrantChange (add them "
            + "to CONFIRMED_READ_ONLY only when they never write the table): " + offenders);
  }

  private static Path moduleRoot() {
    Path here = Paths.get("");
    for (Path candidate : List.of(here, here.resolve(Paths.get("modules", "com.etendoerp.go")))) {
      if (Files.isDirectory(candidate.resolve(Paths.get("src", "com", "etendoerp", "go", "roles",
          "goaccess")))) {
        return candidate;
      }
    }
    throw new AssertionError("com.etendoerp.go module root not found from " + here.toAbsolutePath());
  }
}
