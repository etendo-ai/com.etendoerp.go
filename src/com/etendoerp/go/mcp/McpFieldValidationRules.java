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

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.codehaus.jettison.json.JSONObject;
import org.hibernate.criterion.Restrictions;
import org.openbravo.dal.service.OBCriteria;
import org.openbravo.dal.service.OBDal;

import com.etendoerp.go.schemaforge.data.SFEntity;
import com.etendoerp.go.schemaforge.data.SFField;

/**
 * Read side of the {@code validation} section (ETP-4558) for code outside this package: the
 * declared constraints of an entity, keyed by the field's API key.
 *
 * <p>Fields the caller cannot send are left out: not included, read-only, or with a configured
 * default (server-defaulted, so a "required" there must not refuse a request that omits it). A
 * field whose payload is unusable is skipped and logged, never half-applied.</p>
 */
public final class McpFieldValidationRules {

  private static final Logger log = LogManager.getLogger(McpFieldValidationRules.class);

  private McpFieldValidationRules() {
  }

  /**
   * @param sfEntity the SchemaForge entity; {@code null} yields no rules
   * @return API key to constraints, empty when nothing is declared
   */
  public static Map<String, JSONObject> forEntity(SFEntity sfEntity) {
    Map<String, JSONObject> rules = new LinkedHashMap<>();
    if (sfEntity == null) {
      return rules;
    }
    OBCriteria<SFField> crit = OBDal.getInstance().createCriteria(SFField.class);
    crit.add(Restrictions.eq(SFField.PROPERTY_ETGOSFENTITY + ".id", sfEntity.getId()));
    crit.add(Restrictions.eq(SFField.PROPERTY_ISACTIVE, true));
    List<SFField> fields = crit.list();
    for (SFField field : fields) {
      String apiKey = StringUtils.trimToNull(field.getJavaQualifier());
      if (apiKey == null || !Boolean.TRUE.equals(field.isIncluded())
          || Boolean.TRUE.equals(field.isReadOnly())) {
        continue;
      }
      McpEntityConfig.Resolved resolved = McpEntityConfig.forField(field);
      if (!resolved.isUsable()) {
        log.warn("Declarative validation skipped for field {}: {}", apiKey,
            resolved.describeProblems());
        continue;
      }
      resolved.section(McpValidationSection.NAME).ifPresent(body -> {
        rules.put(apiKey, hasDefault(field) ? withoutRequired(body) : body);
      });
    }
    return rules;
  }

  private static boolean hasDefault(SFField field) {
    return StringUtils.isNotBlank(field.getDefaultValue())
        || (field.getADColumn() != null && StringUtils.isNotBlank(field.getADColumn().getDefaultValue()));
  }

  private static JSONObject withoutRequired(JSONObject body) {
    try {
      JSONObject copy = new JSONObject(body.toString());
      copy.remove("required");
      return copy;
    } catch (org.codehaus.jettison.json.JSONException e) {
      return body;
    }
  }
}
