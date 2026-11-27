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
import static org.mockito.Mockito.mockStatic;

import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.Set;

import org.codehaus.jettison.json.JSONObject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import com.etendoerp.go.common.PublicUrlResolver;

/**
 * ETP-6421 — {@code etendo_how_to} and the server instructions: models answered "where do I do X
 * in Etendo" with Etendo Classic menus; this tells them which Etendo the server is.
 */
class McpHowToToolTest {

  private static JSONObject body(JSONObject toolResult) throws Exception {
    return new JSONObject(toolResult.getJSONArray("content").getJSONObject(0).getString("text"));
  }

  @Test
  @DisplayName("The essentials fit in the first 512 characters and the whole text in Claude Code's 2 KB")
  void instructionsFitTheClientLimits() {
    String head = McpHowToTool.INSTRUCTIONS.substring(0, 512);
    assertTrue(head.contains("NOT Etendo Classic"), head);
    assertTrue(head.contains("etendo_discover"), head);
    assertTrue(McpHowToTool.INSTRUCTIONS.getBytes(StandardCharsets.UTF_8).length <= 2048);
  }

  @Test
  @DisplayName("The tool returns the instructions and this instance's app link")
  void returnsTheGuideAndTheAppLink() throws Exception {
    try (MockedStatic<PublicUrlResolver> urls = mockStatic(PublicUrlResolver.class)) {
      urls.when(PublicUrlResolver::resolveConfiguredAppBaseUrl).thenReturn("https://go.example.com");
      JSONObject body = body(McpHowToTool.handle());
      assertEquals(McpHowToTool.INSTRUCTIONS, body.getString(McpHowToTool.KEY_GUIDE));
      assertEquals("https://go.example.com", body.getString(McpHowToTool.KEY_APP_URL));
    }
  }

  @Test
  @DisplayName("Without a configured app URL the link is left out, the guide is not")
  void omitsTheLinkWhenNotConfigured() throws Exception {
    try (MockedStatic<PublicUrlResolver> urls = mockStatic(PublicUrlResolver.class)) {
      urls.when(PublicUrlResolver::resolveConfiguredAppBaseUrl).thenReturn(null);
      JSONObject body = body(McpHowToTool.handle());
      assertEquals(McpHowToTool.INSTRUCTIONS, body.getString(McpHowToTool.KEY_GUIDE));
      assertFalse(body.has(McpHowToTool.KEY_APP_URL));
    }
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
