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
import java.util.function.Supplier;

import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONException;
import org.codehaus.jettison.json.JSONObject;

import com.etendoerp.go.common.PublicUrlResolver;
import com.etendoerp.go.mcp.McpRoutineStore.Owner;
import com.etendoerp.go.mcp.McpRoutineStore.Routine;

/**
 * ETP-6421: {@code etendo_how_to}, the overview of this MCP. Models answered "where do I do X in
 * Etendo" from what they learned about Etendo Classic (the SmartClient ERP), whose menus and windows
 * the web app does not have, and nothing told them how the tools fit together. It answers three
 * things: which Etendo this is ({@link #PRODUCT}), how the tools are used ({@link #USAGE}) and, behind
 * the {@code mcp-routines} flag, how to reuse work with routines ({@link #ROUTINES}).
 * <p>The server {@code instructions} ({@link #INSTRUCTIONS}) say the first part to the clients that
 * read them; this tool reaches the ones that do not (Claude desktop and claude.ai show the model only
 * tool names until it searches them, and {@code etendo_how_to} is the name models looked up most often
 * in a name test).
 */
final class McpHowToTool {

  private static final Logger log = LogManager.getLogger();

  /** Result key: which Etendo this is. */
  static final String KEY_PRODUCT = "product";
  /** Result key: how the tools fit together. */
  static final String KEY_USAGE = "usage";
  /** Result key: the routines guide and the caller's routines; only with the flag on. */
  static final String KEY_ROUTINES = "routines";
  /** Key, inside {@link #KEY_ROUTINES}, of the guide text. */
  static final String KEY_GUIDE = "guide";
  /** Key, inside {@link #KEY_ROUTINES}, of the caller's saved routines. */
  static final String KEY_YOURS = "yours";
  /** Key, inside {@link #KEY_ROUTINES}, of how many saved routines {@link #KEY_YOURS} leaves out. */
  static final String KEY_MORE = "more";
  /** Result key holding this instance's app link, when one is configured. */
  static final String KEY_APP_URL = "appUrl";
  /** Most saved routines listed; the rest are counted in {@link #KEY_MORE}. */
  static final int MAX_LISTED_ROUTINES = 20;

  /** Which Etendo this is. First in {@link #INSTRUCTIONS}: some clients read only 512 characters. */
  static final String PRODUCT = "This server is Etendo, the web business app (app.etendo.ai), NOT "
      + "Etendo Classic, the older Etendo/Openbravo ERP with the SmartClient interface. Screens, "
      + "menus, window names and steps differ, so never guide the user with menu paths or steps you "
      + "know from Etendo Classic or Openbravo. To tell the user where something is done, call "
      + "etendo_discover (pass spec when you know it): each window spec carries windowName, the "
      + "window's name in the user's language, and windowUrl, a direct link to it; give the user "
      + "those. If the tools do not show where something is, say so instead of guessing.";

  /** The server instructions: {@link #PRODUCT}, then where to read more. Under Claude Code's 2 KB. */
  static final String INSTRUCTIONS = PRODUCT + " Call etendo_how_to for how these tools fit "
      + "together; if etendo_routine is among your tools, it also explains routines, which you are "
      + "expected to reuse and offer to save.";

  /** How the tools are used, in the order a task uses them. */
  static final String USAGE = "Every tool runs as the connected user, with that role's permissions "
      + "and organization. A spec is one window, process or report of the app; a window spec has "
      + "entities (header, lines, ...). "
      + "1) Context: etendo_whoami gives the user, role, organization, today's date, currency and "
      + "language; call it when the task depends on them or a call is refused unexpectedly. "
      + "2) Find: etendo_discover lists the specs you can use and their entities (pass spec to get "
      + "only one); etendo_vector_search finds records by meaning when you do not know their exact "
      + "values. "
      + "3) Understand a spec: etendo_schema gives an entity's fields (required, read-only, which "
      + "point to other records) and, with view:'actions', the buttons you can fire; "
      + "etendo_selectors gives the valid values of a field that points to another record; "
      + "etendo_defaults gives the values a new record starts with. "
      + "4) Read: etendo_list (filters, sorting, paging, fields or view:'summary' to keep answers "
      + "small) and etendo_get for one record. "
      + "5) Write: etendo_create (a line needs parentId, the header's id); etendo_update (read the "
      + "record first: its 'updated' value is required); etendo_delete; etendo_action fires a button "
      + "such as completing or posting a document; etendo_batch creates many records in one call "
      + "and runs as a job you follow with etendo_job_status. "
      + "6) Documents and reports, when listed: the document PDF and sending it by email, "
      + "attachments, reading a supplier invoice from a file, generate_* reports, and etendo_widget "
      + "for dashboard figures. "
      + "Rules: never invent ids, resolve them with etendo_list, etendo_selectors or what a call "
      + "returned; read each error, it names what to fix; before an irreversible step the user did "
      + "not clearly ask for (deleting, completing or posting a document, sending an email) confirm "
      + "with them. When something in this API confused you or failed, report it with "
      + "etendo_feedback. For this API's reference with examples, call docs with topic 'mcp'.";

  /** How to reuse work with routines; only for sessions where etendo_routine is offered. */
  static final String ROUTINES = "A routine is a saved flow of etendo_* calls you run again with "
      + "new values in one call (etendo_routine). Using them is expected: "
      + "1) Before a task of several steps, check 'yours' below: if a routine fits, run it "
      + "(mode run) instead of doing the steps one by one. "
      + "2) For a flow of several steps you already know (e.g. a customer with its address, an "
      + "order with its invoice), use mode exec: all the steps in one call. "
      + "3) When the user repeats a flow in this conversation, or asks for one people usually "
      + "repeat, finish it with exec and then OFFER to save it as a routine (mode save with "
      + "fromExecution, the values that change become parameters). Never save one without the "
      + "user's OK; once saved, tell them they can ask for it by name next time. "
      + "Call etendo_routine with mode help before writing your first routine.";

  private McpHowToTool() {
  }

  /**
   * Builds the tool definition. It takes no arguments.
   *
   * @param routines whether {@code etendo_routine} is offered to this session; the description then
   *     names routines too
   * @return the definition
   */
  static McpToolDefinition definition(boolean routines) {
    return new McpToolDefinition(
        McpConstants.TOOL_NEO_HOW_TO,
        "How to use this MCP: which Etendo this is (the web app, not Etendo Classic), how the "
            + "tools fit together"
            + (routines ? ", and your saved routines and when to offer new ones" : "")
            + ". Call it at the start, and before telling the user how or where to do something "
            + "in Etendo instead of answering from what you know about Etendo. Changes nothing and "
            + "is cheap.",
        buildObjectSchema(new LinkedHashMap<>(), List.of()));
  }

  /**
   * Handle one {@code etendo_how_to} call.
   *
   * @return the MCP tool result
   */
  static JSONObject handle() {
    return handle(McpRoutinesFlag.isEnabled(),
        () -> new McpRoutineJdbcStore().list(Owner.current()));
  }

  /**
   * Builds the answer.
   *
   * @param routinesEnabled whether the {@code mcp-routines} flag is on for this user
   * @param savedRoutines   the user's saved routines; read only when the flag is on
   * @return the MCP tool result
   */
  static JSONObject handle(boolean routinesEnabled, Supplier<List<Routine>> savedRoutines) {
    try {
      JSONObject body = new JSONObject();
      body.put(KEY_PRODUCT, PRODUCT);
      body.put(KEY_USAGE, USAGE);
      if (routinesEnabled) {
        body.put(KEY_ROUTINES, routines(savedRoutines));
      }
      String appUrl = PublicUrlResolver.resolveConfiguredAppBaseUrl();
      if (StringUtils.isNotBlank(appUrl)) {
        body.put(KEY_APP_URL, appUrl);
      }
      return McpToolRouter.wrapAsTextContent(body);
    } catch (JSONException e) {
      throw new McpToolException("Error building the etendo_how_to response", e);
    }
  }

  private static JSONObject routines(Supplier<List<Routine>> savedRoutines) throws JSONException {
    JSONObject routines = new JSONObject();
    routines.put(KEY_GUIDE, ROUTINES);
    List<Routine> saved;
    try {
      saved = savedRoutines.get();
    } catch (RuntimeException e) {
      // The guide still helps; the agent can list them with etendo_routine.
      log.debug("Could not read the saved routines for etendo_how_to", e);
      return routines;
    }
    JSONArray yours = new JSONArray();
    for (Routine routine : saved.subList(0, Math.min(saved.size(), MAX_LISTED_ROUTINES))) {
      yours.put(new JSONObject().put(McpRoutineTool.PARAM_NAME, routine.name)
          .put(McpRoutineTool.PARAM_DESCRIPTION, routine.description));
    }
    routines.put(KEY_YOURS, yours);
    if (saved.size() > MAX_LISTED_ROUTINES) {
      routines.put(KEY_MORE, saved.size() - MAX_LISTED_ROUTINES);
    }
    return routines;
  }
}
