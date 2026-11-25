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

import java.util.Date;

import javax.servlet.http.HttpServletResponse;

import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.codehaus.jettison.json.JSONObject;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.common.enterprise.Organization;
import org.openbravo.model.common.invoice.Invoice;
import org.openbravo.module.sii.data.AEATSIIConfig;
import org.openbravo.module.sii.utils.SIIUtils;

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
 * ({@code FacturasEventHandler.checkDUAFields}) applies to F5 / LC only. That requirement holds
 * only when the organization files SII for the invoice ({@link #orgFilesSii}); for any other
 * organization DUA and Fecha DUA stay optional. Everything else of the feature is independent of
 * SII. Turning the check on while the {@code sii-import-dua} flag is off is refused with
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
  static final String FIELD_ORG = Invoice.PROPERTY_ORGANIZATION;
  static final String FIELD_ACCOUNTING_DATE = Invoice.PROPERTY_ACCOUNTINGDATE;
  static final String FIELD_INVOICE_DATE = Invoice.PROPERTY_INVOICEDATE;

  static final String MSG_DUA_REQUIRED = "ETGO_PurchaseInvoiceImportDuaRequired";

  private static final Logger log = LogManager.getLogger(PurchaseInvoiceImportDua.class);

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
      log.debug("[ETP-6284] Import DUA check not readable on invoice {}: {}", invoice.getId(),
          e.getMessage());
      return false;
    }
  }

  static boolean isImportKey(Object key) {
    return "F5".equals(key) || "LC".equals(key);
  }

  /**
   * Refuses a header create / update that would leave the check on without DUA and Fecha DUA
   * (Múltiple DUA off) on an invoice whose organization files SII, or that turns the check on while
   * the feature is off. {@code null} lets the write go on.
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
    Invoice stored = create ? null : readInvoice(context.getRecordId());
    if (!create && stored == null) {
      return null; // unknown or invisible: the default CRUD path answers it
    }
    if (turnsCheckOn(body, stored) && !SiiImportDuaFlag.isEnabled()) {
      return SiiImportDuaFlag.featureDisabled();
    }
    HeaderState state = HeaderState.of(body, stored);
    // The SII lookup runs only when the DUA is actually missing.
    return state.missesDua() && orgFilesSii(orgIdOf(body, stored), accountingDateOf(body, stored))
        ? NeoMessageText.error(HttpServletResponse.SC_BAD_REQUEST, MSG_DUA_REQUIRED)
        : null;
  }

  /**
   * Whether the write actually turns the check on: the body sends it on and the invoice did not
   * already have it (a create, or a stored check that is off). Re-sending {@code true} on an
   * already-checked invoice is not turning it on.
   */
  private static boolean turnsCheckOn(JSONObject body, Invoice stored) {
    return body != null && body.has(FIELD_IMPORT_DUA) && isYes(body.opt(FIELD_IMPORT_DUA))
        && (stored == null || !storedCheck(stored));
  }

  /**
   * Whether the organization of the stored invoice files SII for it (see
   * {@link #orgFilesSii(String, Date)}).
   */
  static boolean orgFilesSii(Invoice invoice) {
    if (invoice == null || invoice.getOrganization() == null) {
      return false;
    }
    Date date = invoice.getAccountingDate() != null ? invoice.getAccountingDate()
        : invoice.getInvoiceDate();
    return orgFilesSii(invoice.getOrganization().getId(), date);
  }

  /**
   * Whether the organization files SII for an invoice of that accounting date — Classic's own
   * criterion for a purchase invoice ({@code FacturasEventHandler.checkSIIValidations}): an active
   * SII configuration ({@link SIIUtils#getSiiConfigFromOrg}, which skips inactive rows) that is
   * "Acogida al SII" and whose adoption date ({@code AEATSIIConfig.fechaAcogidaSII}) is on or
   * before the accounting date. Both are read from the same configuration, looked up once. An
   * unknown date (a create that does not send one: GO defaults it to today) reads as adopted. Read
   * in admin mode: the SII configuration is not part of the caller's purchase-invoice access.
   * Never throws.
   *
   * @param orgId the invoice's organization
   * @param accountingDate the invoice's accounting date (else its invoice date), or {@code null}
   * @return {@code true} when DUA and Fecha DUA are required by SII for the invoice
   */
  static boolean orgFilesSii(String orgId, Date accountingDate) {
    if (StringUtils.isBlank(orgId)) {
      return false;
    }
    OBContext.setAdminMode(true);
    try {
      Organization org = OBDal.getInstance().get(Organization.class, orgId);
      AEATSIIConfig config = org == null ? null : SIIUtils.getSiiConfigFromOrg(org);
      if (config == null || !Boolean.TRUE.equals(config.isAcogidaAlSII())) {
        return false;
      }
      Date adoption = config.getFechaAcogidaSII();
      return adoption != null && (accountingDate == null || !accountingDate.before(adoption));
    } catch (RuntimeException e) {
      log.debug("[ETP-6284] SII config lookup failed for org {}: {}", orgId, e.getMessage());
      return false;
    } finally {
      OBContext.restorePreviousMode();
    }
  }

  /** The organization once the write is applied: the body's, else the stored one, else the session's. */
  private static String orgIdOf(JSONObject body, Invoice stored) {
    String fromBody = idOf(body, FIELD_ORG);
    if (fromBody != null) {
      return fromBody;
    }
    if (stored != null && stored.getOrganization() != null) {
      return stored.getOrganization().getId();
    }
    Organization current = OBContext.getOBContext().getCurrentOrganization();
    return current == null ? null : current.getId();
  }

  /** A link the body names, as an id or as {@code {id}}; {@code null} when absent. */
  private static String idOf(JSONObject body, String field) {
    if (body == null || !body.has(field)) {
      return null;
    }
    JSONObject asObject = body.optJSONObject(field);
    String id = asObject != null ? asObject.optString("id", null) : body.optString(field, null);
    return StringUtils.trimToNull(id);
  }

  /**
   * The accounting date once the write is applied (Classic falls back to the invoice date): body
   * accounting date, stored accounting date, body invoice date, stored invoice date.
   */
  private static Date accountingDateOf(JSONObject body, Invoice stored) {
    Date accounting = dateOf(body, FIELD_ACCOUNTING_DATE);
    if (accounting == null && stored != null) {
      accounting = stored.getAccountingDate();
    }
    if (accounting != null) {
      return accounting;
    }
    Date invoiceDate = dateOf(body, FIELD_INVOICE_DATE);
    return invoiceDate != null || stored == null ? invoiceDate : stored.getInvoiceDate();
  }

  private static Date dateOf(JSONObject body, String field) {
    if (body == null || !body.has(field)) {
      return null;
    }
    return BankStatementsSupport.parseIsoDate(body.optString(field, null), null);
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
      boolean importDua = check || isImportKey(typeOf(body, stored));
      boolean multiDua = flag(body, FIELD_MULTI_DUA,
          stored != null && Boolean.TRUE.equals(stored.isAeatsiiMultiDua()));
      boolean hasDua = present(body, FIELD_DUA,
          stored != null && StringUtils.isNotBlank(stored.getAeatsiiDua()));
      boolean hasDuaDate = present(body, FIELD_DUA_DATE,
          stored != null && stored.getAeatsiiFechaDua() != null);
      return new HeaderState(importDua, multiDua, hasDua, hasDuaDate);
    }

    /** The SII invoice type once the write is applied: the body's if it names it, else stored. */
    private static Object typeOf(JSONObject body, Invoice stored) {
      if (body != null && body.has(FIELD_SII_TYPE)) {
        return body.opt(FIELD_SII_TYPE);
      }
      return stored == null ? null : stored.getAeatsiiClaveTipoFc();
    }

    boolean missesDua() {
      return importDua && !multiDua && !(hasDua && hasDuaDate);
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
