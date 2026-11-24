/*
 * *************************************************************************
 * The contents of this file are subject to the Etendo License
 * (the "License"), you may not use this file except in compliance with
 * the License.
 * You may obtain a copy of the License at
 * https://github.com/etendosoftware/etendo_core/blob/main/legal/Etendo_license.txt
 * Software distributed under the License  is  distributed  on  an  "AS IS"
 * basis, WITHOUT WARRANTY OF ANY KIND, either express or implied. See the
 * License for the specific language governing rights and limitations under
 * the License.
 * All portions are Copyright © 2021-2026 FUTIT SERVICES, S.L
 * All Rights Reserved.
 * Contributor(s): Futit Services S.L.
 * *************************************************************************
 */
package com.etendoerp.go.schemaforge;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.UnaryOperator;

import org.codehaus.jettison.json.JSONObject;
import org.junit.Test;
import org.mockito.MockedStatic;
import org.openbravo.model.ad.datamodel.Table;
import org.openbravo.model.ad.ui.Tab;

import com.etendoerp.go.mcp.McpFieldValidationRules;
import com.etendoerp.go.schemaforge.data.SFEntity;

/**
 * Production evaluation of the declarative constraints (ETP-4558), bypassing any frontend.
 *
 * @covers com.etendoerp.go.schemaforge.NeoDeclarativeValidation
 */
public class NeoDeclarativeValidationTest {

  private static final UnaryOperator<String> SAME = UnaryOperator.identity();

  private static Map<String, JSONObject> rules(String key, String json) throws Exception {
    Map<String, JSONObject> rules = new LinkedHashMap<>();
    rules.put(key, new JSONObject(json));
    return rules;
  }

  /** The only violation of the field: asserts the list holds exactly one entry (no duplicates). */
  private static String code(Map<String, List<JSONObject>> errors, String key) {
    assertEquals("exactly one violation for " + key, 1, errors.get(key).size());
    return errors.get(key).get(0).optString("code");
  }

  @Test
  public void malformedEmailIsInvalidFormat() throws Exception {
    Map<String, List<JSONObject>> e = NeoDeclarativeValidation.evaluate(
        rules("etgoEmail", "{\"format\":\"email\"}"), SAME,
        new JSONObject("{\"etgoEmail\":\"not-an-email\"}"), true);
    assertEquals("INVALID_FORMAT", code(e, "etgoEmail"));
    assertEquals("email", e.get("etgoEmail").get(0).optString("format"));
    assertFalse(e.get("etgoEmail").get(0).has("value"));
  }

  @Test
  public void validEmailPasses() throws Exception {
    assertTrue(NeoDeclarativeValidation.evaluate(rules("e", "{\"format\":\"email\"}"), SAME,
        new JSONObject("{\"e\":\"a@b.com\"}"), true).isEmpty());
  }

  @Test
  public void disallowedSchemeOnPatch() throws Exception {
    Map<String, JSONObject> r = rules("web", "{\"allowedSchemes\":[\"https\"]}");
    assertEquals("DISALLOWED_SCHEME", code(NeoDeclarativeValidation.evaluate(r, SAME,
        new JSONObject("{\"web\":\"javascript:alert(1)\"}"), false), "web"));
    assertEquals("DISALLOWED_SCHEME", code(NeoDeclarativeValidation.evaluate(r, SAME,
        new JSONObject("{\"web\":\"http://x.com\"}"), false), "web"));
    assertTrue(NeoDeclarativeValidation.evaluate(r, SAME,
        new JSONObject("{\"web\":\"HTTPS://x.com\"}"), false).isEmpty());
  }

  @Test
  public void lengthRangeEnumAndRequired() throws Exception {
    assertEquals("TOO_LONG", code(NeoDeclarativeValidation.evaluate(
        rules("n", "{\"maxLength\":3}"), SAME, new JSONObject("{\"n\":\"abcd\"}"), true), "n"));
    assertEquals("ABOVE_MAXIMUM", code(NeoDeclarativeValidation.evaluate(
        rules("q", "{\"minimum\":0,\"maximum\":10}"), SAME, new JSONObject("{\"q\":11}"), true),
        "q"));
    assertEquals("BELOW_MINIMUM", code(NeoDeclarativeValidation.evaluate(
        rules("q", "{\"minimum\":0}"), SAME, new JSONObject("{\"q\":-1}"), true), "q"));
    assertEquals("INVALID_ENUM", code(NeoDeclarativeValidation.evaluate(
        rules("t", "{\"enum\":[\"A\",\"B\"]}"), SAME, new JSONObject("{\"t\":\"C\"}"), true), "t"));
    assertEquals("REQUIRED", code(NeoDeclarativeValidation.evaluate(
        rules("r", "{\"required\":true}"), SAME, new JSONObject("{}"), true), "r"));
  }

  @Test
  public void zeroAndFalseAreRealValues() throws Exception {
    assertTrue(NeoDeclarativeValidation.evaluate(
        rules("q", "{\"minimum\":0,\"required\":true}"), SAME, new JSONObject("{\"q\":0}"),
        true).isEmpty());
    assertTrue(NeoDeclarativeValidation.evaluate(
        rules("b", "{\"required\":true}"), SAME, new JSONObject("{\"b\":false}"), true).isEmpty());
  }

  @Test
  public void partialPatchDoesNotRequireOmittedFieldButRefusesExplicitNull() throws Exception {
    Map<String, JSONObject> r = rules("r", "{\"required\":true}");
    assertTrue(NeoDeclarativeValidation.evaluate(r, SAME, new JSONObject("{\"x\":1}"), false)
        .isEmpty());
    assertEquals("REQUIRED", code(NeoDeclarativeValidation.evaluate(r, SAME,
        new JSONObject("{\"r\":null}"), false), "r"));
  }

  @Test
  public void dalPropertyNameAndDataWrapperAreChecked() throws Exception {
    Map<String, List<JSONObject>> e = NeoDeclarativeValidation.evaluate(
        rules("etgoEmail", "{\"format\":\"email\"}"),
        k -> "etgoEmail".equals(k) ? "email" : k, new JSONObject("{\"data\":{\"email\":\"bad\"}}"),
        true);
    assertEquals("INVALID_FORMAT", code(e, "etgoEmail"));
  }

  @Test
  public void unknownFormatIsNotEnforced() throws Exception {
    assertTrue(NeoDeclarativeValidation.evaluate(rules("x", "{\"format\":\"v9-new\"}"), SAME,
        new JSONObject("{\"x\":\"anything\"}"), true).isEmpty());
  }

  @Test
  public void responseShapeIsStableAndLeaksNothing() throws Exception {
    Map<String, List<JSONObject>> e = NeoDeclarativeValidation.evaluate(
        rules("etgoEmail", "{\"format\":\"email\"}"), SAME,
        new JSONObject("{\"etgoEmail\":\"secret@@\"}"), true);
    NeoResponse response = NeoDeclarativeValidation.toResponse(e);
    assertEquals(400, response.getHttpStatus());
    JSONObject error = response.getBody().getJSONObject("error");
    assertEquals("VALIDATION_FAILED", error.getString("code"));
    assertFalse(response.getBody().toString().contains("secret"));
    assertEquals("INVALID_FORMAT",
        error.getJSONObject("fields").getJSONArray("etgoEmail").getJSONObject(0).getString("code"));
  }

  @Test
  public void numericEnumComparesNumerically() throws Exception {
    Map<String, JSONObject> r = rules("n", "{\"enum\":[1,2]}");
    assertTrue(NeoDeclarativeValidation.evaluate(r, SAME, new JSONObject("{\"n\":1.0}"), true)
        .isEmpty());
    assertEquals("INVALID_ENUM", code(NeoDeclarativeValidation.evaluate(r, SAME,
        new JSONObject("{\"n\":3}"), true), "n"));
  }

  @Test
  public void unknownConstraintKeysAreIgnored() throws Exception {
    assertTrue(NeoDeclarativeValidation.evaluate(rules("x", "{\"pattern\":\"a+\",\"v\":2}"),
        SAME, new JSONObject("{\"x\":\"zzz\"}"), true).isEmpty());
  }

  @Test
  public void lengthCountsUtf16UnitsLikeTheContract() throws Exception {
    // U+1F600 is two UTF-16 units; the contract documents that semantics.
    assertEquals("TOO_LONG", code(NeoDeclarativeValidation.evaluate(rules("n", "{\"maxLength\":1}"),
        SAME, new JSONObject("{\"n\":\"\uD83D\uDE00\"}"), true), "n"));
  }

  // ---- refusalFor: the boundary NeoWritePreflight calls ----

  private static NeoContext context(String method, String body, SFEntity entity, Tab tab)
      throws Exception {
    return NeoContext.builder().specName("contacts").entityName("contact").httpMethod(method)
        .requestBody(new JSONObject(body)).adTab(tab).sfEntity(entity)
        .endpointType(NeoEndpointType.CRUD).build();
  }

  private Tab tab() {
    Table table = mock(Table.class);
    when(table.getName()).thenReturn("BusinessPartner");
    Tab tab = mock(Tab.class);
    when(tab.getTable()).thenReturn(table);
    return tab;
  }

  @Test
  public void refusalForWithoutRulesProceeds() throws Exception {
    SFEntity entity = mock(SFEntity.class);
    try (MockedStatic<McpFieldValidationRules> rules = mockStatic(McpFieldValidationRules.class)) {
      rules.when(() -> McpFieldValidationRules.forEntity(entity)).thenReturn(new LinkedHashMap<>());
      assertNull(NeoDeclarativeValidation.refusalFor(context("POST", "{}", entity, tab()), tab()));
    }
  }

  @Test
  public void refusalForViolationIsA400WithTheStableBody() throws Exception {
    SFEntity entity = mock(SFEntity.class);
    Tab tab = tab();
    NeoFieldFilter filter = mock(NeoFieldFilter.class);
    when(filter.resolveWritablePropName("etgoEmail")).thenReturn("email");
    try (MockedStatic<McpFieldValidationRules> rules = mockStatic(McpFieldValidationRules.class);
        MockedStatic<NeoFieldFilter> filters = mockStatic(NeoFieldFilter.class)) {
      rules.when(() -> McpFieldValidationRules.forEntity(entity))
          .thenReturn(rules("etgoEmail", "{\"format\":\"email\"}"));
      filters.when(() -> NeoFieldFilter.forEntity(entity, "BusinessPartner")).thenReturn(filter);

      NeoResponse post = NeoDeclarativeValidation.refusalFor(
          context("POST", "{\"etgoEmail\":\"top-secret\"}", entity, tab), tab);
      assertEquals(400, post.getHttpStatus());
      assertEquals("VALIDATION_FAILED", post.getBody().getJSONObject("error").getString("code"));
      assertFalse(post.getBody().toString().contains("top-secret"));

      // The DAL spelling of the same field is judged too, and a valid PATCH proceeds.
      assertEquals(400, NeoDeclarativeValidation.refusalFor(
          context("PATCH", "{\"email\":\"bad\"}", entity, tab), tab).getHttpStatus());
      assertNull(NeoDeclarativeValidation.refusalFor(
          context("PATCH", "{\"etgoEmail\":\"a@b.com\"}", entity, tab), tab));
    }
  }

  @Test
  public void createRequiresPresenceButPatchDoesNot() throws Exception {
    SFEntity entity = mock(SFEntity.class);
    Tab tab = tab();
    NeoFieldFilter filter = mock(NeoFieldFilter.class);
    when(filter.resolveWritablePropName(org.mockito.ArgumentMatchers.anyString()))
        .thenAnswer(inv -> inv.getArgument(0));
    try (MockedStatic<McpFieldValidationRules> rules = mockStatic(McpFieldValidationRules.class);
        MockedStatic<NeoFieldFilter> filters = mockStatic(NeoFieldFilter.class)) {
      rules.when(() -> McpFieldValidationRules.forEntity(entity))
          .thenReturn(rules("name", "{\"required\":true}"));
      filters.when(() -> NeoFieldFilter.forEntity(entity, "BusinessPartner")).thenReturn(filter);

      assertEquals(400, NeoDeclarativeValidation.refusalFor(
          context("POST", "{\"other\":1}", entity, tab), tab).getHttpStatus());
      assertNull(NeoDeclarativeValidation.refusalFor(
          context("PATCH", "{\"other\":1}", entity, tab), tab));
    }
  }
}
