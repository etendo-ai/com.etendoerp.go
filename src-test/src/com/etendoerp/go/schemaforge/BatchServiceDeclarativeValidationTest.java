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
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONObject;
import org.junit.Test;
import org.mockito.MockedStatic;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBCriteria;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.ad.datamodel.Table;
import org.openbravo.model.ad.ui.Tab;

import com.etendoerp.go.mcp.McpFieldValidationRules;
import com.etendoerp.go.schemaforge.data.SFEntity;
import com.etendoerp.go.schemaforge.data.SFSpec;
import com.etendoerp.go.schemaforge.util.NeoAccessHelper;
import com.etendoerp.go.schemaforge.util.NeoAccountingAccessPolicy;

/**
 * A batch create that breaks a declared constraint goes through the REAL {@link NeoWritePreflight}
 * and {@link NeoDeclarativeValidation}: the batch fails with the stable body, reports where, rolls
 * back, and no create reaches a customization or the default CRUD step.
 *
 * @covers com.etendoerp.go.schemaforge.BatchService
 */
public class BatchServiceDeclarativeValidationTest {

  private static final String SPEC = "contacts";

  @Test
  public void oneInvalidCreateFailsTheBatchAndPersistsNothing() throws Exception {
    JSONArray ops = new JSONArray();
    ops.put(op("bad", "{\"etgoEmail\":\"not-an-email\"}"));
    ops.put(op("never", "{\"etgoEmail\":\"c@d.com\"}"));

    OBDal obDal = mock(OBDal.class);
    SFEntity entity = mock(SFEntity.class);
    when(entity.isPost()).thenReturn(true);
    Table table = mock(Table.class);
    when(table.getName()).thenReturn("BusinessPartner");
    Tab tab = mock(Tab.class);
    when(tab.getTable()).thenReturn(table);
    when(entity.getADTab()).thenReturn(tab);
    when(entity.getJavaQualifier()).thenReturn("business-partner");

    Map<String, JSONObject> rules = new LinkedHashMap<>();
    rules.put("etgoEmail", new JSONObject("{\"format\":\"email\"}"));
    NeoFieldFilter filter = mock(NeoFieldFilter.class);
    when(filter.resolveWritablePropName("etgoEmail")).thenReturn("etgoEmail");

    try (MockedStatic<OBDal> dal = mockStatic(OBDal.class);
        MockedStatic<OBContext> ctx = mockStatic(OBContext.class);
        MockedStatic<NeoServletSupport> support = mockStatic(NeoServletSupport.class);
        MockedStatic<NeoAccessHelper> access = mockStatic(NeoAccessHelper.class);
        MockedStatic<NeoAccountingAccessPolicy> acct = mockStatic(NeoAccountingAccessPolicy.class);
        MockedStatic<McpFieldValidationRules> mcp = mockStatic(McpFieldValidationRules.class);
        MockedStatic<NeoFieldFilter> filters = mockStatic(NeoFieldFilter.class)) {
      dal.when(OBDal::getInstance).thenReturn(obDal);
      ctx.when(OBContext::getOBContext).thenReturn(mock(OBContext.class));
      access.when(() -> NeoAccessHelper.hasWindowAccessForSpec(any(), any())).thenReturn(true);
      acct.when(() -> NeoAccountingAccessPolicy.isEntityNameAllowed(any(), any()))
          .thenReturn(true);
      SFSpec spec = mock(SFSpec.class);
      when(spec.getId()).thenReturn("spec-1");
      when(spec.getName()).thenReturn(SPEC);
      support.when(() -> NeoServletSupport.findSpec(SPEC)).thenReturn(spec);
      @SuppressWarnings("unchecked")
      OBCriteria<SFEntity> criteria = (OBCriteria<SFEntity>) mock(OBCriteria.class);
      when(obDal.createCriteria(SFEntity.class)).thenReturn(criteria);
      when(criteria.list()).thenReturn(Collections.singletonList(entity));
      mcp.when(() -> McpFieldValidationRules.forEntity(entity)).thenReturn(rules);
      filters.when(() -> NeoFieldFilter.forEntity(entity, "BusinessPartner")).thenReturn(filter);
      support.when(() -> NeoServletSupport.handleWithHooks(any(), any(NeoContext.class), any(),
          any())).thenReturn(created());

      JSONObject result = BatchService.forBatchOnly().executeBatch(ops);

      assertFalse(result.getBoolean("committed"));
      assertEquals(0, result.getJSONObject("failedAt").getInt("index"));
      JSONObject error = result.getJSONObject("error");
      assertEquals(400, error.getInt("status"));
      assertTrue(result.toString().contains("VALIDATION_FAILED"));
      assertTrue(result.toString().contains("INVALID_FORMAT"));
      assertFalse(result.toString().contains("not-an-email"));
      // Neither create reached a customization or the default CRUD step, and the transaction was
      // rolled back, so nothing is persisted.
      support.verify(() -> NeoServletSupport.handleWithHooks(any(), any(NeoContext.class), any(),
          any()), never());
      verify(obDal, never()).commitAndClose();
      verify(obDal).rollbackAndClose();
    }
  }

  private static JSONObject op(String id, String body) throws Exception {
    return new JSONObject().put("id", id).put("spec", SPEC).put("entity", "contact")
        .put("body", new JSONObject(body));
  }

  private static NeoResponse created() throws Exception {
    JSONObject row = new JSONObject().put("id", "REC1");
    return new NeoResponse(201, new JSONObject().put("response",
        new JSONObject().put("data", new JSONArray().put(row))));
  }
}
