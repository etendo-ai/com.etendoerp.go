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
 * All portions are Copyright (C) 2021-2026 FUTIT SERVICES, S.L
 * All Rights Reserved.
 * Contributor(s): Futit Services S.L.
 * *************************************************************************
 */
package com.etendoerp.go.mcp;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.Arrays;
import java.util.Collections;
import java.util.Map;

import org.codehaus.jettison.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBCriteria;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.ad.datamodel.Column;

import com.etendoerp.go.schemaforge.data.SFEntity;
import com.etendoerp.go.schemaforge.data.SFField;

/**
 * Which fields' {@code validation} sections {@link McpFieldValidationRules} hands to NEO, read in
 * admin mode: own section only, never from non-sendable or unusable fields.
 *
 * @covers com.etendoerp.go.mcp.McpFieldValidationRules
 */
public class McpFieldValidationRulesTest {

  private MockedStatic<OBDal> obDal;
  private MockedStatic<OBContext> obContext;
  private OBCriteria<SFField> criteria;
  private SFEntity entity;

  @Before
  @SuppressWarnings("unchecked")
  public void setUp() {
    obDal = Mockito.mockStatic(OBDal.class);
    obContext = Mockito.mockStatic(OBContext.class);
    OBDal dal = mock(OBDal.class);
    criteria = mock(OBCriteria.class);
    obDal.when(OBDal::getInstance).thenReturn(dal);
    when(dal.createCriteria(SFField.class)).thenReturn(criteria);
    when(criteria.add(any())).thenReturn(criteria);
    entity = mock(SFEntity.class);
    when(entity.getId()).thenReturn("E1");
  }

  @After
  public void tearDown() {
    obDal.close();
    obContext.close();
    Mockito.framework().clearInlineMocks();
  }

  private static SFField field(String qualifier, boolean included, boolean readOnly, String config,
      String defaultValue) {
    SFField field = mock(SFField.class);
    when(field.getJavaQualifier()).thenReturn(qualifier);
    when(field.isIncluded()).thenReturn(included);
    when(field.isReadOnly()).thenReturn(readOnly);
    when(field.getDefaultValue()).thenReturn(defaultValue);
    when(field.get(McpEntityConfig.PROPERTY_MCP_CONFIG)).thenReturn(config);
    return field;
  }

  private Map<String, JSONObject> rulesOf(SFField... fields) {
    when(criteria.list()).thenReturn(Arrays.asList(fields));
    return McpFieldValidationRules.forEntity(entity);
  }

  @Test
  public void readsTheFieldsOwnSectionInAdminMode() {
    Map<String, JSONObject> rules = rulesOf(field("etgoEmail", true, false,
        "{\"fields\":{\"reason\":\"r\"},\"validation\":{\"format\":\"email\",\"later\":1}}", null));

    assertEquals("email", rules.get("etgoEmail").optString("format"));
    obContext.verify(() -> OBContext.setAdminMode(false));
    obContext.verify(OBContext::restorePreviousMode);
  }

  @Test
  public void skipsNotIncludedReadOnlyAndFieldsWithoutSection() {
    Map<String, JSONObject> rules = rulesOf(
        field("a", false, false, "{\"validation\":{\"required\":true}}", null),
        field("b", true, true, "{\"validation\":{\"required\":true}}", null),
        field("c", true, false, "{\"fields\":{\"reason\":\"r\"}}", null),
        field("d", true, false, null, null),
        field(null, true, false, "{\"validation\":{\"required\":true}}", null));

    assertTrue(rules.isEmpty());
  }

  @Test
  public void serverDefaultedFieldKeepsItsRulesExceptRequired() {
    Map<String, JSONObject> rules = rulesOf(field("n", true, false,
        "{\"validation\":{\"required\":true,\"maxLength\":3}}", "'X'"));
    assertFalse(rules.get("n").has("required"));
    assertEquals(3, rules.get("n").optInt("maxLength"));

    SFField withColumnDefault = field("m", true, false,
        "{\"validation\":{\"required\":true}}", null);
    Column column = mock(Column.class);
    when(column.getDefaultValue()).thenReturn("0");
    when(withColumnDefault.getADColumn()).thenReturn(column);
    assertFalse(rulesOf(withColumnDefault).get("m").has("required"));
  }

  @Test
  public void unusableOrMalformedPayloadIsSkippedNotHalfApplied() {
    Map<String, JSONObject> rules = rulesOf(
        field("bad", true, false, "{\"validation\":{\"maxLength\":\"five\"}}", null),
        field("junk", true, false, "not json", null),
        field("good", true, false, "{\"validation\":{\"maxLength\":5}}", null));

    assertEquals(Collections.singleton("good"), rules.keySet());
  }

  @Test
  public void nullEntityYieldsNoRules() {
    assertTrue(McpFieldValidationRules.forEntity(null).isEmpty());
  }
}
