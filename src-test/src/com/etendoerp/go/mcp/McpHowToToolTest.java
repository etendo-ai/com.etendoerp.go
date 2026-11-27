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
 * All portions are Copyright (C) 2026 FUTIT SERVICES, S.L
 * All Rights Reserved.
 * Contributor(s): Futit Services S.L.
 * *************************************************************************
 */

package com.etendoerp.go.mcp;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.mockito.Mockito.mockStatic;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import com.etendoerp.go.common.PublicUrlResolver;
import com.etendoerp.go.mcp.McpRoutineStore.Routine;

/**
 * ETP-6421 — {@code etendo_how_to} and the server instructions: models answered "where do I do X
 * in Etendo" with Etendo Classic menus and had no overview of the tools; the routines part shows only
 * to sessions where {@code etendo_routine} is offered.
 */
class McpHowToToolTest {

  private MockedStatic<PublicUrlResolver> urls;

  @BeforeEach
  void configureAppUrl() {
    urls = mockStatic(PublicUrlResolver.class);
    urls.when(PublicUrlResolver::resolveConfiguredAppBaseUrl).thenReturn("https://go.example.com");
  }

  @AfterEach
  void closeMocks() {
    urls.close();
  }

  private static JSONObject body(JSONObject toolResult) throws Exception {
    return new JSONObject(toolResult.getJSONArray("content").getJSONObject(0).getString("text"));
  }

  private static List<Routine> routines(int count) {
    List<Routine> routines = new ArrayList<>();
    for (int i = 1; i <= count; i++) {
      routines.add(new Routine("R" + i, "routine-" + i, "Routine " + i, 1));
    }
    return routines;
  }

  @Test
  @DisplayName("The essentials fit in the first 512 characters and the whole text in Claude Code's 2 KB")
  void instructionsFitTheClientLimits() {
    String head = McpHowToTool.INSTRUCTIONS.substring(0, 512);
    assertTrue(head.contains("NOT Etendo Classic"), head);
    assertTrue(head.contains("etendo_discover"), head);
    assertTrue(McpHowToTool.INSTRUCTIONS.getBytes(StandardCharsets.UTF_8).length <= 2048);
    assertTrue(McpHowToTool.INSTRUCTIONS.contains("etendo_how_to"));
  }

  @Test
  @DisplayName("Flag off: which Etendo, how the tools are used and the app link; nothing about routines")
  void withoutTheFlagThereIsNoRoutinesPart() throws Exception {
    JSONObject body = body(McpHowToTool.handle(false,
        () -> fail("the routines must not be read with the flag off")));
    assertEquals(McpHowToTool.PRODUCT, body.getString(McpHowToTool.KEY_PRODUCT));
    assertEquals(McpHowToTool.USAGE, body.getString(McpHowToTool.KEY_USAGE));
    assertEquals("https://go.example.com", body.getString(McpHowToTool.KEY_APP_URL));
    assertFalse(body.has(McpHowToTool.KEY_ROUTINES));
    assertFalse(McpHowToTool.definition(false).getDescription().contains("routine"));
  }

  @Test
  @DisplayName("Flag on: the routines guide and the user's routines by name and description")
  void withTheFlagTheRoutinesGuideAndTheUsersRoutines() throws Exception {
    JSONObject routines = body(McpHowToTool.handle(true, () -> routines(2)))
        .getJSONObject(McpHowToTool.KEY_ROUTINES);
    assertEquals(McpHowToTool.ROUTINES, routines.getString(McpHowToTool.KEY_GUIDE));
    JSONArray yours = routines.getJSONArray(McpHowToTool.KEY_YOURS);
    assertEquals(2, yours.length());
    assertEquals("routine-1", yours.getJSONObject(0).getString("name"));
    assertEquals("Routine 1", yours.getJSONObject(0).getString("description"));
    assertFalse(routines.has(McpHowToTool.KEY_MORE));
    assertTrue(McpHowToTool.definition(true).getDescription().contains("routines"));
  }

  @Test
  @DisplayName("Many routines: the first 20 are listed and the rest counted")
  void manyRoutinesAreCappedAndCounted() throws Exception {
    JSONObject routines = body(McpHowToTool.handle(true, () -> routines(25)))
        .getJSONObject(McpHowToTool.KEY_ROUTINES);
    assertEquals(McpHowToTool.MAX_LISTED_ROUTINES,
        routines.getJSONArray(McpHowToTool.KEY_YOURS).length());
    assertEquals(5, routines.getInt(McpHowToTool.KEY_MORE));
  }

  @Test
  @DisplayName("A failing routine lookup keeps the guide and the rest of the answer")
  void aFailingLookupKeepsTheGuide() throws Exception {
    JSONObject body = body(McpHowToTool.handle(true, () -> {
      throw new IllegalStateException("db down");
    }));
    JSONObject routines = body.getJSONObject(McpHowToTool.KEY_ROUTINES);
    assertEquals(McpHowToTool.ROUTINES, routines.getString(McpHowToTool.KEY_GUIDE));
    assertFalse(routines.has(McpHowToTool.KEY_YOURS));
    assertEquals(McpHowToTool.USAGE, body.getString(McpHowToTool.KEY_USAGE));
  }

  @Test
  @DisplayName("Without a configured app URL the link is left out")
  void omitsTheLinkWhenNotConfigured() throws Exception {
    urls.when(PublicUrlResolver::resolveConfiguredAppBaseUrl).thenReturn(null);
    JSONObject body = body(McpHowToTool.handle(false, List::of));
    assertEquals(McpHowToTool.PRODUCT, body.getString(McpHowToTool.KEY_PRODUCT));
    assertFalse(body.has(McpHowToTool.KEY_APP_URL));
  }

  @Test
  @DisplayName("It takes no arguments, needs only read scope and addresses no spec")
  void takesNoArgumentsAndOnlyReadScope() {
    assertEquals(Optional.of(Set.of()), ToolRegistry.declaredArgumentNames(McpConstants.TOOL_NEO_HOW_TO));
    assertDoesNotThrow(() -> McpAuthorizationService.authorizeToolCall(
        McpConstants.TOOL_NEO_HOW_TO, Set.of("etendo:read")));
    assertNull(ToolRegistry.resolveSpecName(McpConstants.TOOL_NEO_HOW_TO, new JSONObject()));
  }
}
