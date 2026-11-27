/*
 *************************************************************************
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
 *************************************************************************
 */
package com.etendoerp.go.schemaforge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mockStatic;

import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.MockedStatic;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBDal;

/**
 * Unit tests for {@link ContactCategoryRequiredFields}: a contact category needs a search key and
 * a name on create, and an update may not blank them. No database; the message text comes from the
 * bundled catalog (the DAL statics are mocked so the {@code AD_MESSAGE} lookups find nothing).
 *
 * @covers com.etendoerp.go.schemaforge.ContactCategoryRequiredFields
 */
class ContactCategoryRequiredFieldsTest {

  private MockedStatic<OBContext> obContext;
  private MockedStatic<OBDal> obDal;

  @BeforeEach
  void setUp() {
    obContext = mockStatic(OBContext.class);
    obDal = mockStatic(OBDal.class);
  }

  @AfterEach
  void tearDown() {
    obDal.close();
    obContext.close();
  }

  private static NeoContext ctx(String method, NeoEndpointType type, JSONObject body) {
    return NeoContext.builder().specName("business-partner-category")
        .entityName("businessPartnerCategory").httpMethod(method).endpointType(type)
        .requestBody(body).build();
  }

  private static NeoContext crud(String method, JSONObject body) {
    return ctx(method, NeoEndpointType.CRUD, body);
  }

  /** Puts {@code value}: {@code -} leaves the key out, {@code null} is JSON null. */
  private static JSONObject body(String searchKey, String name) throws Exception {
    JSONObject body = new JSONObject();
    put(body, "searchKey", searchKey);
    put(body, "name", name);
    return body;
  }

  private static void put(JSONObject body, String key, String value) throws Exception {
    if ("-".equals(value)) {
      return;
    }
    body.put(key, "null".equals(value) ? JSONObject.NULL : value);
  }

  private static void assertMissing(NeoResponse response, String... fields) throws Exception {
    assertNotNull(response);
    assertEquals(400, response.getHttpStatus());
    JSONObject error = response.getBody().getJSONObject("error");
    assertEquals("MISSING_REQUIRED_FIELDS", error.getString("code"));
    JSONArray actual = error.getJSONArray("fields");
    assertEquals(fields.length, actual.length());
    for (int i = 0; i < fields.length; i++) {
      assertEquals(fields[i], actual.getString(i));
    }
    assertEquals(ContactCategoryRequiredFields.MSG_NAME_REQUIRED,
        error.getJSONArray(NeoProcessService.MESSAGE_KEYS).getString(0));
  }

  @ParameterizedTest(name = "POST name {0} is refused")
  @CsvSource({"-", "null", "''", "'   '"})
  void createWithoutAUsableNameIsRefused(String name) throws Exception {
    assertMissing(ContactCategoryRequiredFields.validate(crud("POST", body("CLI", name))), "name");
  }

  @ParameterizedTest(name = "POST searchKey {0} is refused")
  @CsvSource({"-", "null", "''", "'   '"})
  void createWithoutAUsableSearchKeyIsRefused(String key) throws Exception {
    assertMissing(ContactCategoryRequiredFields.validate(crud("POST", body(key, "Clientes"))),
        "searchKey");
  }

  @Test
  void createMissingBothFieldsNamesBoth() throws Exception {
    assertMissing(ContactCategoryRequiredFields.validate(crud("POST", new JSONObject())),
        "searchKey", "name");
  }

  @Test
  void validCreateContinuesAndLeavesTheBodyAlone() throws Exception {
    JSONObject body = body("CLI", "Clientes");
    assertNull(ContactCategoryRequiredFields.validate(crud("POST", body)));
    assertEquals("{\"searchKey\":\"CLI\",\"name\":\"Clientes\"}", body.toString());
  }

  @ParameterizedTest(name = "{0} without the keys is not judged")
  @CsvSource({"PATCH", "PUT"})
  void updateThatDoesNotCarryTheKeysIsNotJudged(String method) throws Exception {
    JSONObject body = new JSONObject().put("description", "x").put("etgoIscustomer", true);
    assertNull(ContactCategoryRequiredFields.validate(crud(method, body)));
  }

  @ParameterizedTest(name = "{0} name {1} is refused")
  @CsvSource({"PATCH, null", "PATCH, ''", "PUT, '   '"})
  void updateThatBlanksTheNameIsRefused(String method, String name) throws Exception {
    JSONObject body = new JSONObject();
    put(body, "name", name);
    assertMissing(ContactCategoryRequiredFields.validate(crud(method, body)), "name");
  }

  @Test
  void updateThatBlanksTheSearchKeyIsRefusedAndAValidNameIsNot() throws Exception {
    assertMissing(ContactCategoryRequiredFields.validate(crud("PATCH", body("", "Clientes"))),
        "searchKey");
    assertNull(ContactCategoryRequiredFields.validate(crud("PATCH", body("CLI", "Clientes"))));
  }

  @Test
  void onlyTheEnvelopeContentIsJudged() throws Exception {
    JSONObject inside = new JSONObject().put("data", new JSONObject().put("searchKey", "CLI"));
    assertMissing(ContactCategoryRequiredFields.validate(crud("POST", inside)), "name");

    JSONObject beside = new JSONObject().put("name", "").put("data", body("CLI", "Clientes"));
    assertNull(ContactCategoryRequiredFields.validate(crud("POST", beside)));
  }

  @ParameterizedTest(name = "{0} {1} is not a category write")
  @CsvSource({"GET, CRUD", "DELETE, CRUD", "POST, SELECTOR", "POST, DEFAULTS"})
  void requestsThatAreNotCrudWritesAreNotJudged(String method, NeoEndpointType type) {
    assertNull(ContactCategoryRequiredFields.validate(ctx(method, type, new JSONObject())));
  }

  @Test
  void nullContextOrBodyIsNotJudged() {
    assertNull(ContactCategoryRequiredFields.validate(null));
    assertNull(ContactCategoryRequiredFields.validate(crud("POST", null)));
  }
}
