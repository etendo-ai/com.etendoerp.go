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

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.codehaus.jettison.json.JSONObject;
import org.openbravo.base.model.Entity;
import org.openbravo.base.model.ModelProvider;
import org.openbravo.base.model.Property;
import org.openbravo.base.provider.OBProvider;
import org.openbravo.base.structure.BaseOBObject;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.ad.ui.Tab;
import org.openbravo.service.json.DataResolvingMode;
import org.openbravo.service.json.DataToJsonConverter;

/**
 * Gives an update the callout effects the UI gives it when a reference changes (ETP-6176).
 *
 * <p>The form re-runs a field's callout when the user changes it: a new product brings its own tax,
 * unit of measure and price. A create already runs the cascade, but an update used to store only
 * the fields sent, so the line kept the PREVIOUS product's tax. Here the callouts of the references
 * whose value differs from the stored one are fired, reading the stored record overlaid with the
 * patch (what the form holds), and their outputs land in the body for every field the caller did
 * not send — a value the caller sent always wins.
 *
 * <p>Generic: which fields depend on which is the Application Dictionary's callout metadata, and
 * nothing names an entity. Total: a failure is logged and leaves the body as it was.
 */
public final class NeoUpdateCalloutRefire {

  private static final Logger log = LogManager.getLogger(NeoUpdateCalloutRefire.class);

  private NeoUpdateCalloutRefire() {
  }

  /**
   * Re-fires the callouts of the references {@code body} changes.
   *
   * @param ctx           the update's request context
   * @param body          the DAL-keyed update body, mutated in place with the derived values
   * @param dalEntityName the DAL entity name of the record
   */
  public static void apply(NeoContext ctx, JSONObject body, String dalEntityName) {
    if (ctx == null || body == null || StringUtils.isBlank(ctx.getRecordId())
        || ctx.getAdTab() == null) {
      return;
    }
    try {
      Entity entity = ModelProvider.getInstance().getEntity(dalEntityName, false);
      BaseOBObject stored = entity == null ? null
          : OBDal.getInstance().get(dalEntityName, ctx.getRecordId());
      if (stored == null) {
        return;
      }
      Tab adTab = ctx.getAdTab();
      List<String> triggers = changedReferencesWithCallout(entity, stored, body, adTab);
      if (triggers.isEmpty()) {
        return;
      }
      JSONObject formState = withoutBlankValues(OBProvider.getInstance()
          .get(DataToJsonConverter.class).toJsonObject(stored, DataResolvingMode.FULL));
      Set<String> sent = new HashSet<>();
      Iterator<?> keys = body.keys();
      while (keys.hasNext()) {
        String key = String.valueOf(keys.next());
        sent.add(key);
        formState.put(key, body.get(key));
      }
      NeoDefaultsCascadeHelper.executeCalloutsForTriggerFields(ctx, adTab, formState, body,
          triggers, sent);
      // ETP-6107: then the values sent with it, as when the user types a price after picking the
      // product — a price sent with a new product is discounted from the NEW list price.
      List<String> sentValues = sentValuesWithCallout(entity, body, sent, adTab);
      if (!sentValues.isEmpty()) {
        NeoDefaultsCascadeHelper.executeCalloutsForTriggerFields(ctx, adTab, formState, body,
            sentValues, sent);
      }
    } catch (Exception e) {
      log.warn("Could not re-fire the callouts of an update on {}: {}", dalEntityName,
          e.getMessage());
    }
  }

  /**
   * Drops the stored record's empty columns from the form state (ETP-6107).
   *
   * <p>The callout request takes a parent (header) column only when the form state does not carry
   * it ({@code CalloutRequestBuilder#injectParentRecordFields}). A create never carries an empty
   * one, but the stored record does: an order line's own business partner and address are empty,
   * so they hid the header's, and the product callout picked a tax without the customer's address.
   * A callout reads a missing parameter as empty anyway, so dropping them changes nothing else.
   *
   * @param formState the stored record as JSON; mutated in place
   * @return {@code formState}
   */
  static JSONObject withoutBlankValues(JSONObject formState) {
    List<String> blank = new ArrayList<>();
    Iterator<?> keys = formState.keys();
    while (keys.hasNext()) {
      String key = String.valueOf(keys.next());
      Object value = formState.opt(key);
      if (value == null || JSONObject.NULL.equals(value) || "".equals(value)) {
        blank.add(key);
      }
    }
    for (String key : blank) {
      formState.remove(key);
    }
    return formState;
  }

  /** The non-reference fields the caller sent that have a callout, in the order sent. */
  static List<String> sentValuesWithCallout(Entity entity, JSONObject body,
      Set<String> sent, Tab adTab) {
    List<String> fields = new ArrayList<>();
    Iterator<?> keys = body.keys();
    while (keys.hasNext()) {
      String key = String.valueOf(keys.next());
      Property property = entity.getProperty(key, false);
      if (sent.contains(key) && property != null && property.isPrimitive()
          && NeoCalloutService.resolveCallout(adTab, key) != null) {
        fields.add(key);
      }
    }
    return fields;
  }

  private static List<String> changedReferencesWithCallout(Entity entity, BaseOBObject stored,
      JSONObject body, Tab adTab) {
    List<String> triggers = new ArrayList<>();
    Iterator<?> keys = body.keys();
    while (keys.hasNext()) {
      String key = String.valueOf(keys.next());
      Property property = entity.getProperty(key, false);
      Object value = body.opt(key);
      if (property == null || property.isPrimitive() || property.isOneToMany()
          || property.getTargetEntity() == null || !(value instanceof String)
          || NeoReferenceActiveness.isNotAReference((String) value)) {
        continue;
      }
      Object current = stored.get(key);
      boolean changed = !(current instanceof BaseOBObject)
          || !value.equals(((BaseOBObject) current).getId());
      if (changed && NeoCalloutService.resolveCallout(adTab, key) != null) {
        triggers.add(key);
      }
    }
    return triggers;
  }
}
