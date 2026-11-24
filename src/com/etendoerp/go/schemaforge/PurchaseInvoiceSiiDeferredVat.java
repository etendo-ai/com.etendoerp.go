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
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.codehaus.jettison.json.JSONException;
import org.codehaus.jettison.json.JSONObject;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.common.invoice.Invoice;
import org.openbravo.module.sii.utils.SIIUtils;

import com.etendoerp.go.schemaforge.util.NeoActionContract;

/**
 * ETP-6284 — whether a purchase invoice is an import of deferred VAT, the case where the SPA offers
 * the compensatory import-tax line ("Añadir línea compensatoria").
 *
 * <pre>GET /sws/neo/purchase-invoice/header/{id}/action/siiDeferredVat</pre>
 *
 * <p>Body: {@code {deferredVat}}. {@code true} when the invoice is marked as an import with DUA
 * ({@link PurchaseInvoiceImportDua#isImportDua}, column {@code EM_Etgo_Import_Dua}) and its supplier
 * is outside the EU or in Canarias, Ceuta or Melilla — the address rule of
 * {@link SIIUtils#isDeferredVATInvoice}, which the Classic send uses to pick the deferred-VAT
 * registry builder. It does NOT depend on the SII configuration (adoption, tax type, invoice type):
 * an organization that imports but does not file SII from Etendo gets the same answer. That method
 * reads only the partner address; it never touches {@code AEATSIIConfig}. Read-only; works on a
 * draft. The action keeps its historic {@code sii} name, which the SPA and agents already call.
 *
 * <p>Called explicitly by {@link PurchaseInvoiceHeaderHandler}, never from shared code.
 */
final class PurchaseInvoiceSiiDeferredVat {

  static final String ACTION_NAME = "siiDeferredVat";

  static final String KEY_DEFERRED_VAT = "deferredVat";

  private static final String KEY_F5 = "F5";
  private static final String KEY_LC = "LC";

  private static final Logger log = LogManager.getLogger(PurchaseInvoiceSiiDeferredVat.class);

  /** Published for agents next to the header's other actions; served over GET only. */
  static final NeoActionContract CONTRACT = NeoActionContract.read(ACTION_NAME,
      "Whether this purchase invoice is an import with DUA (etgoImportDua=true) of deferred VAT, "
          + "i.e. its supplier is outside the EU or in Canarias, Ceuta or Melilla: {deferredVat}. "
          + "Independent of SII. When true, each import tax needs a compensatory manual "
          + "tax row (the matching '=0%' import tax, base = minus that tax's base, tax amount 0). "
          + "Read-only; works on a draft.")
      .withIdDescription("the id of the purchase invoice")
      .withHttpMethod("GET");

  private PurchaseInvoiceSiiDeferredVat() {
  }

  /**
   * Serves the action when the request is it, else {@code null}.
   *
   * @param context the header request
   * @return the response, or {@code null} when the request is not this action
   */
  static NeoResponse handleIfDeferredVat(NeoContext context) {
    if (context == null || !NeoEndpointType.ACTION.equals(context.getEndpointType())
        || !ACTION_NAME.equals(context.getFieldName())) {
      return null;
    }
    // Part of the imports-with-DUA feature: judged first, at request entry.
    if (!SiiImportDuaFlag.isEnabled()) {
      return SiiImportDuaFlag.featureDisabled();
    }
    if (!"GET".equals(context.getHttpMethod())) {
      return NeoResponse.error(HttpServletResponse.SC_METHOD_NOT_ALLOWED,
          ACTION_NAME + " only supports GET");
    }
    String recordId = context.getRecordId();
    // Read with the caller's own access, so an invoice the role cannot see is a 404.
    Invoice invoice = StringUtils.isBlank(recordId) ? null
        : OBDal.getInstance().get(Invoice.class, recordId);
    if (invoice == null) {
      return NeoResponse.error(HttpServletResponse.SC_NOT_FOUND,
          "Purchase invoice not found: " + recordId);
    }
    try {
      return NeoResponse.ok(new JSONObject().put(KEY_DEFERRED_VAT, isDeferredVatImport(invoice)));
    } catch (JSONException e) {
      log.error("Could not serialize the deferred VAT answer of invoice {}", recordId, e);
      return NeoResponse.error(HttpServletResponse.SC_INTERNAL_SERVER_ERROR,
          "Could not answer " + ACTION_NAME);
    }
  }

  /** Marked as an import with DUA and a supplier address of deferred VAT. No SII config needed. */
  static boolean isDeferredVatImport(Invoice invoice) {
    OBContext.setAdminMode(true);
    try {
      return invoice != null && PurchaseInvoiceImportDua.isImportDua(invoice) && isDeferredVat(invoice);
    } finally {
      OBContext.restorePreviousMode();
    }
  }

  static boolean isImportKey(String key) {
    return KEY_F5.equals(key) || KEY_LC.equals(key);
  }

  private static boolean isDeferredVat(Invoice invoice) {
    // The SII module reads the partner address country; without it the invoice is not deferred.
    if (invoice.getPartnerAddress() == null
        || invoice.getPartnerAddress().getLocationAddress() == null) {
      return false;
    }
    try {
      return SIIUtils.isDeferredVATInvoice(invoice);
    } catch (RuntimeException e) {
      return false;
    }
  }
}
