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

import static com.etendoerp.go.mcp.McpJsonSchema.buildObjectSchema;

import java.util.LinkedHashMap;
import java.util.List;

import org.apache.commons.lang3.StringUtils;
import org.codehaus.jettison.json.JSONException;
import org.codehaus.jettison.json.JSONObject;

import com.etendoerp.go.common.PublicUrlResolver;

/**
 * ETP-6421: tells the agent which Etendo this server is. Models answer "where do I do X in Etendo"
 * from what they learned about Etendo Classic (the SmartClient ERP), whose menus and windows the
 * new web app does not have. The same text goes to the clients that read the server
 * {@code instructions} ({@link #INSTRUCTIONS}); this tool reaches the ones that do not (Claude
 * desktop and claude.ai show the model only tool names until it searches them, and the name
 * {@code etendo_how_to} is the one models looked up most often in a name test).
 */
final class McpHowToTool {

  /** Result key holding {@link #INSTRUCTIONS}. */
  static final String KEY_GUIDE = "guide";
  /** Result key holding this instance's app link, when one is configured. */
  static final String KEY_APP_URL = "appUrl";

  /**
   * The server instructions, also returned by the tool. The essentials come first: some clients
   * read only the first 512 characters, and Claude Code cuts at 2 KB.
   */
  static final String INSTRUCTIONS = "This server is Etendo, the web business app (app.etendo.ai), "
      + "NOT Etendo Classic, the older Etendo/Openbravo ERP with the SmartClient interface. Screens, "
      + "menus, window names and steps differ, so never guide the user with menu paths or steps "
      + "you know from Etendo Classic or Openbravo. To tell the user where something is done, call "
      + "etendo_discover (pass spec when you know it): each window spec carries windowName, the "
      + "window's name in the user's language, and windowUrl, a direct link to it; give the user "
      + "those. To do the task for the user, use the other etendo_* tools; they act within the "
      + "user's role and permissions. If the tools do not show where something is, say so instead "
      + "of guessing.";

  private McpHowToTool() {
  }

  /**
   * Builds the tool definition. It takes no arguments.
   *
   * @return the definition
   */
  static McpToolDefinition definition() {
    return new McpToolDefinition(
        McpConstants.TOOL_NEO_HOW_TO,
        "How to help the user with Etendo: which Etendo this is (the web app, not Etendo Classic) "
            + "and how to find where something is done in it. Call it before telling the user how "
            + "or where to do something in Etendo, instead of answering from what you know about "
            + "Etendo. Changes nothing and is cheap.",
        buildObjectSchema(new LinkedHashMap<>(), List.of()));
  }

  /**
   * Handle one {@code etendo_how_to} call.
   *
   * @return the MCP tool result with {@link #INSTRUCTIONS} and, when configured, the app link
   */
  static JSONObject handle() {
    try {
      JSONObject body = new JSONObject();
      body.put(KEY_GUIDE, INSTRUCTIONS);
      String appUrl = PublicUrlResolver.resolveConfiguredAppBaseUrl();
      if (StringUtils.isNotBlank(appUrl)) {
        body.put(KEY_APP_URL, appUrl);
      }
      return McpToolRouter.wrapAsTextContent(body);
    } catch (JSONException e) {
      throw new McpToolException("Error building the etendo_how_to response", e);
    }
  }
}
