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

package com.etendoerp.go.schemaforge;

import javax.servlet.http.HttpServletResponse;

import org.apache.commons.lang3.StringUtils;
import org.codehaus.jettison.json.JSONObject;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.common.invoice.Invoice;

/**
 * ETP-6284 — the "Importación con DUA" check of a purchase invoice ({@code C_Invoice.EM_Etgo_Import_Dua},
 * owned by com.etendoerp.go) and the header rule it brings.
 *
 * <p>The check marks the invoice as an import with DUA <b>independently of SII</b>: an organization
 * that imports but does not file SII from Etendo still records the DUA, its date and the manual
 * import tax lines. The SII invoice type F5 / LC only says how SII reports it; for an organization
 * adopted to SII the SPA sets F5 when the check is on.
 *
 * <p>An SII invoice type F5 / LC counts as checked ({@link #isImportDua}), so a legacy or Classic
 * import without the check follows the same rules. Header rule ({@link #checkHeaderWrite}): on an
 * import and with Múltiple DUA off, the invoice needs DUA and Fecha DUA — the rule Classic
 * ({@code FacturasEventHandler.checkDUAFields}) applies to F5 / LC only. Turning the check on while the {@code sii-import-dua} flag is off is refused with
 * {@link SiiImportDuaFlag#featureDisabled()}. Called explicitly by
 * {@link PurchaseInvoiceHeaderHandler}, so it holds on REST single, batch and MCP alike.
 */
final class PurchaseInvoiceImportDua {

  /** DAL property of {@code EM_Etgo_Import_Dua} (read by name: no generated getter needed). */
  static final String FIELD_IMPORT_DUA = "etgoImportDua";
  static final String FIELD_MULTI_DUA = Invoice.PROPERTY_AEATSIIMULTIDUA;
  static final String FIELD_DUA = Invoice.PROPERTY_AEATSIIDUA;
  static final String FIELD_DUA_DATE = Invoice.PROPERTY_AEATSIIFECHADUA;
  static final String FIELD_SII_TYPE = Invoice.PROPERTY_AEATSIICLAVETIPOFC;

  static final String MSG_DUA_REQUIRED = "ETGO_PurchaseInvoiceImportDuaRequired";

  private PurchaseInvoiceImportDua() {
  }

  /**
   * Whether the stored invoice is an import with DUA: the "Importación con DUA" check on, or an SII
   * invoice type F5 / LC (a legacy or Classic invoice without the check counts as checked).
   */
  static boolean isImportDua(Invoice invoice) {
    if (invoice == null) {
      return false;
    }
    return storedCheck(invoice) || isImportKey(invoice.getAeatsiiClaveTipoFc());
  }

  /** The stored check alone. */
  private static boolean storedCheck(Invoice invoice) {
    try {
      return isYes(invoice.get(FIELD_IMPORT_DUA));
    } catch (RuntimeException e) {
      // The column is not in the runtime model yet (update.database pending): treat as off.
      return false;
    }
  }

  static boolean isImportKey(Object key) {
    return "F5".equals(key) || "LC".equals(key);
  }

  /**
   * Refuses a header create / update that would leave the check on without DUA and Fecha DUA
   * (Múltiple DUA off), or that turns the check on while the feature is off. {@code null} lets the
   * write go on.
   *
   * @param context the header request
   * @return the refusal, or {@code null}
   */
  static NeoResponse checkHeaderWrite(NeoContext context) {
    if (context == null || !NeoEndpointType.CRUD.equals(context.getEndpointType())) {
      return null;
    }
    String method = context.getHttpMethod();
    boolean create = "POST".equals(method);
    if (!create && !"PUT".equals(method) && !"PATCH".equals(method)) {
      return null;
    }
    JSONObject body = PurchaseInvoiceTaxExtension.dataNode(context.getRequestBody());
    if (body != null && body.has(FIELD_IMPORT_DUA) && isYes(body.opt(FIELD_IMPORT_DUA))
        && !SiiImportDuaFlag.isEnabled()) {
      return SiiImportDuaFlag.featureDisabled();
    }
    Invoice stored = create ? null : readInvoice(context.getRecordId());
    if (!create && stored == null) {
      return null; // unknown or invisible: the default CRUD path answers it
    }
    HeaderState state = HeaderState.of(body, stored);
    return state.missesDua()
        ? NeoMessageText.error(HttpServletResponse.SC_BAD_REQUEST, MSG_DUA_REQUIRED)
        : null;
  }

  /**
   * The values of the four import fields once the write is applied: the request merged over the
   * stored invoice, never the stored values alone. So a save that turns the check off AND moves the
   * SII type away from F5 / LC in one request (what the SPA sends when the user unchecks an
   * import it checked itself) is no longer an import and needs no DUA.
   */
  static final class HeaderState {
    final boolean importDua;
    final boolean multiDua;
    final boolean hasDua;
    final boolean hasDuaDate;

    HeaderState(boolean importDua, boolean multiDua, boolean hasDua, boolean hasDuaDate) {
      this.importDua = importDua;
      this.multiDua = multiDua;
      this.hasDua = hasDua;
      this.hasDuaDate = hasDuaDate;
    }

    /** The body's value when it names the field (a null or blank clears it), else the stored one. */
    static HeaderState of(JSONObject body, Invoice stored) {
      boolean check = flag(body, FIELD_IMPORT_DUA, stored != null && storedCheck(stored));
      Object type = body != null && body.has(FIELD_SII_TYPE) ? body.opt(FIELD_SII_TYPE)
          : stored == null ? null : stored.getAeatsiiClaveTipoFc();
      boolean importDua = check || isImportKey(type);
      boolean multiDua = flag(body, FIELD_MULTI_DUA,
          stored != null && Boolean.TRUE.equals(stored.isAeatsiiMultiDua()));
      boolean hasDua = present(body, FIELD_DUA,
          stored != null && StringUtils.isNotBlank(stored.getAeatsiiDua()));
      boolean hasDuaDate = present(body, FIELD_DUA_DATE,
          stored != null && stored.getAeatsiiFechaDua() != null);
      return new HeaderState(importDua, multiDua, hasDua, hasDuaDate);
    }

    boolean missesDua() {
      return importDua && !multiDua && !(hasDua && hasDuaDate);
    }
  }

  private static boolean flag(JSONObject body, String field, boolean fallback) {
    return body != null && body.has(field) ? isYes(body.opt(field)) : fallback;
  }

  private static boolean present(JSONObject body, String field, boolean fallback) {
    if (body == null || !body.has(field)) {
      return fallback;
    }
    Object value = body.opt(field);
    return value != null && !JSONObject.NULL.equals(value)
        && StringUtils.isNotBlank(String.valueOf(value));
  }

  static boolean isYes(Object value) {
    if (value instanceof Boolean) {
      return (Boolean) value;
    }
    return value != null && ("Y".equals(value) || "true".equalsIgnoreCase(String.valueOf(value)));
  }

  private static Invoice readInvoice(String id) {
    // Caller's own access: an invoice the role cannot see is left to the default CRUD path.
    return StringUtils.isBlank(id) ? null : OBDal.getInstance().get(Invoice.class, id);
  }
}
