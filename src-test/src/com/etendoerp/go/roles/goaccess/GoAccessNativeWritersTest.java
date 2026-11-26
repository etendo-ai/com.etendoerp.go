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
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

/**
 * Guards the mirror's safety rule (ETP-4802): no GO Access row may outlive a revoked classic grant.
 * The DAL observer covers every DAL write, so the only way to bypass it is native SQL that writes
 * {@code AD_Window_Access} or {@code OBUIAPP_Process_Access}. Any production class that does must
 * call {@link GoAccessLegacyMirror}; a new native writer fails this test until it does.
 *
 * @covers com.etendoerp.go.roles.goaccess.GoAccessLegacyMirror
 */
class GoAccessNativeWritersTest {

  private static final Pattern NATIVE_WRITE = Pattern.compile(
      "(insert\\s+into|update|delete\\s+from)\\s+(ad_window_access|obuiapp_process_access)\\b",
      Pattern.CASE_INSENSITIVE);

  @Test
  void everyNativeWriterOfAClassicGrantGoesThroughTheMirror() throws IOException {
    Path root = moduleRoot();
    List<String> offenders = new ArrayList<>();
    try (Stream<Path> files = Files.walk(root.resolve("src"))) {
      for (Path file : (Iterable<Path>) files.filter(f -> f.toString().endsWith(".java"))::iterator) {
        String source = new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
        if (NATIVE_WRITE.matcher(source).find() && !source.contains("GoAccessLegacyMirror")) {
          offenders.add(root.relativize(file).toString());
        }
      }
    }
    assertTrue(offenders.isEmpty(),
        "native writers of AD_Window_Access / OBUIAPP_Process_Access that do not call "
            + "GoAccessLegacyMirror: " + offenders);
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
