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
 * All portions are Copyright © 2021-2026 FUTIT SERVICES, S.L
 * All Rights Reserved.
 * Contributor(s): Futit Services S.L.
 * *************************************************************************
 */

package com.etendoerp.go.schemaforge;

import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Map;

import javax.servlet.http.HttpServletResponse;

import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.codehaus.jettison.json.JSONException;
import org.codehaus.jettison.json.JSONObject;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBDal;
import org.openbravo.erpCommon.utility.OBError;
import org.openbravo.module.remittance.Remittance;
import org.openbravo.module.remittance.ad_actionbutton.CreateFile;
import org.openbravo.sepa.iso20022.util.SEPAUtil;

import com.etendoerp.go.schemaforge.util.NeoActionContract;

/**
 * ETP-6247 — the SEPA file actions of the remittance header ({@link RemittanceHeaderHandler}):
 * <ul>
 *   <li>{@value #ACTION_READINESS} (read): whether the file can be generated and why not
 *       ({@link RemittanceFileReadiness});</li>
 *   <li>{@value #ACTION_CONFIRM} (write): «Confirmar» — readiness, then in ONE transaction the
 *       remittance is processed by Classic's {@code REMRemittanceProcess} WITHOUT the automatic
 *       settlement (the payments are created Remitted, the invoices stay unpaid, the status is
 *       {@code PR}) and its file is generated in memory with the remittance type's Classic
 *       generator, only to prove it can be: a file that fails rolls the confirmation back. The file
 *       is NOT stored. The later steps «Enviada al banco» / «Cobrada» are
 *       {@link RemittanceStatusAction};</li>
 *   <li>{@value #ACTION_DOWNLOAD} (read): «Descargar fichero» — the file of a processed remittance,
 *       generated now from its current data and answered in the response, the way Classic's
 *       «Create File» button ({@link CreateFile}) does: nothing is stored, every instruction of the
 *       remittance is written (returned receipts included, counted in
 *       {@value #KEY_RETURNED_COUNT}), and the message identifier is the remittance's document
 *       number, so every download of one remittance carries the same {@code MsgId} (only
 *       {@code CreDtTm} changes).</li>
 * </ul>
 * A refusal is a 422 with the message key in {@code error.messageKeys}; the readiness refusal
 * ({@value #MSG_NOT_READY}) also carries {@code error.problems}. A download emits
 * {@code backend_remittance_file_generated} ({@link RemittanceFileTelemetry}).
 *
 * <p>XML files earlier versions attached to a remittance stay as ordinary attachments.
 */
final class RemittanceFileAction {

  private static final Logger log = LogManager.getLogger(RemittanceFileAction.class);

  static final String ACTION_READINESS = RemittanceFileReadiness.ACTION;
  static final String ACTION_CONFIRM = "confirm";
  static final String ACTION_DOWNLOAD = "downloadFile";

  static final String MSG_NOT_READY = "ETGO_RemittanceFileNotReady";
  static final String MSG_NOT_PROCESSED = "ETGO_RemittanceFileNotProcessed";

  static final String KEY_STATUS = "status";
  static final String KEY_FILE_NAME = "fileName";
  static final String KEY_CONTENT_TYPE = "contentType";
  static final String KEY_CONTENT = "content";
  static final String KEY_RETURNED_COUNT = RemittanceFileReadiness.KEY_RETURNED_COUNT;
  static final String CONTENT_TYPE = "application/xml";

  /** Classic's type parameter naming the file extension ({@code CreateFile}). */
  static final String PARAM_FILE_EXTENSION = RemittanceSepaFileSetup.PARAM_FILE_EXTENSION;

  private static final int SC_UNPROCESSABLE = NeoActionContract.SC_UNPROCESSABLE;
  private static final String POST = "POST";

  private RemittanceFileAction() {
  }

  /** The description of the record id the three actions take. */
  private static final String ID_DESCRIPTION = "the id of the remittance";

  /** Declares the three actions on the remittance header. */
  static void addContracts(Map<String, NeoActionContract> contracts) {
    contracts.put(ACTION_READINESS, NeoActionContract.read(ACTION_READINESS,
        "Checks whether the SEPA file of the remittance can be generated. Returns ready, scheme "
            + "(CORE collection or SCT payment), problems (each with scope org/account/line, code, "
            + "message, params and, for a line, lineId, lineIds, businessPartnerId, "
            + "businessPartnerName, invoiceId, documentNo), summary (invoiceCount, lineCount, "
            + "totalAmount, currency, dueDate) and returnedCount (receipts the bank returned, "
            + "which a download still lists). Fix the problems before calling confirm.")
        .withIdDescription(ID_DESCRIPTION));
    contracts.put(ACTION_CONFIRM, NeoActionContract.write(ACTION_CONFIRM,
        "Confirms the remittance: processes it (creates the payments of its lines, status "
            + "Remitted; nothing is settled and the invoices stay unpaid) and checks that its "
            + "SEPA file can be generated, all or nothing; the remittance status becomes PR. The "
            + "file is not stored: get it with downloadFile. Refused when it is already "
            + "processed, has no lines or is not ready (the refusal lists the problems, as "
            + "fileReadiness). Returns status (the remittance status code) and fileName.",
        RemittanceProcessAction.groupingParam())
        .withIdDescription(ID_DESCRIPTION)
        .withFieldValuesBody());
    contracts.put(ACTION_DOWNLOAD, NeoActionContract.read(ACTION_DOWNLOAD,
        "Generates the SEPA file of a confirmed (processed) remittance now, from its current "
            + "data, as Classic's Create File button does; nothing is stored, so every call "
            + "generates it again (same MsgId, the remittance's document number). Returns "
            + "fileName, contentType (application/xml), content (the XML document itself) and "
            + "returnedCount (receipts the bank returned; the file still lists them, as "
            + "Classic's). Refused when the remittance is not processed or the file is not "
            + "ready (the refusal lists the problems, as fileReadiness). Takes no parameters.")
        .withIdDescription(ID_DESCRIPTION));
  }

  /** @return whether {@code action} is one of this class's actions */
  static boolean serves(String action) {
    return ACTION_READINESS.equals(action) || ACTION_CONFIRM.equals(action)
        || ACTION_DOWNLOAD.equals(action);
  }

  /**
   * Runs one of the actions: 405 for a write that is not a POST, the contract's 422 for a
   * malformed call, 404 for an unknown remittance.
   *
   * @param handler the remittance header customization (its seams)
   * @param context the action request
   * @return the answer
   */
  static NeoResponse handle(RemittanceHeaderHandler handler, NeoContext context) {
    String action = context.getFieldName();
    if (ACTION_CONFIRM.equals(action) && !POST.equals(context.getHttpMethod())) {
      return NeoResponse.error(HttpServletResponse.SC_METHOD_NOT_ALLOWED,
          action + " only supports POST");
    }
    OBContext.setAdminMode(true);
    try {
      JSONObject body = context.getRequestBody() != null ? context.getRequestBody()
          : new JSONObject();
      JSONObject params = RemittanceHeaderActions.actionParams(handler, action, body);
      NeoResponse invalid = NeoActionContract.validate(handler.actionContracts(), action, params);
      if (invalid != null) {
        return invalid;
      }
      Remittance remittance = handler.loadRemittance(context.getRecordId());
      if (remittance == null) {
        return NeoResponse.error(HttpServletResponse.SC_NOT_FOUND, "Remittance not found");
      }
      if (ACTION_READINESS.equals(action)) {
        return NeoResponse.ok(RemittanceFileReadiness.body(remittance));
      }
      handler.lockRemittance(remittance);
      return ACTION_CONFIRM.equals(action) ? confirm(remittance, params) : download(remittance);
    } catch (Exception e) {
      log.error("remittance action {} failed on {}", action, context.getRecordId(), e);
      NeoErrorResponses.rollbackUnlessBatchOwned();
      return NeoResponse.error(HttpServletResponse.SC_INTERNAL_SERVER_ERROR,
          "Could not run " + action + ". Please check logs for details.");
    } finally {
      OBContext.restorePreviousMode();
    }
  }

  /**
   * «Confirmar».
   *
   * @param remittance a remittance of the tenant
   * @param params     {@value RemittanceProcessAction#P_GROUPING}, optional
   * @return {@code {status, fileName}}, or 422
   */
  static NeoResponse confirm(Remittance remittance, JSONObject params) throws JSONException {
    if (Boolean.TRUE.equals(remittance.isProcessed())) {
      return NeoMessageText.error(SC_UNPROCESSABLE, RemittanceProcessAction.MSG_ALREADY_PROCESSED);
    }
    RemittanceFileReadiness.Result readiness = RemittanceFileReadiness.evaluate(remittance);
    if (readiness.lineCount == 0) {
      return NeoMessageText.error(SC_UNPROCESSABLE, RemittanceProcessAction.MSG_NO_LINES);
    }
    if (!readiness.isReady()) {
      return notReady(readiness);
    }
    String remittanceId = remittance.getId();
    RemittanceSepaFileSetup.ensure(remittance.getRemittanceType(), readiness.scheme);
    // ETP-6247: processed WITHOUT the automatic settlement (payments stay Remitted, invoices
    // unpaid); «Cobrada» settles. A remittance an earlier version created automatic is reset.
    RemittanceProcessAction.disableAutomaticSettlement(remittance);
    String grouping = StringUtils.defaultIfBlank(
        params.optString(RemittanceProcessAction.P_GROUPING, null),
        RemittanceProcessAction.GROUPING_NONE);
    OBError result = runProcess(remittanceId, grouping);
    NeoResponse refused = RemittanceProcessAction.refusalOf(result, remittanceId, grouping,
        RemittanceProcessAction.MSG_PROCESS_FAILED);
    if (refused != null) {
      return refused;
    }
    Remittance processed = OBDal.getInstance().get(Remittance.class, remittanceId);
    if (processed == null || !Boolean.TRUE.equals(processed.isProcessed())) {
      RemittanceProcessAction.rollback();
      return NeoMessageText.error(SC_UNPROCESSABLE, RemittanceProcessAction.MSG_PROCESS_FAILED,
          "-");
    }
    // The file is generated only to prove it can be (schema and EPC rules); a refusal rolls the
    // confirmation back. Nothing is stored: «Descargar fichero» generates it again.
    try {
      generate(processed);
    } catch (RemittanceFileGenerator.Refusal refusal) {
      RemittanceProcessAction.rollback();
      return NeoMessageText.error(SC_UNPROCESSABLE, refusal.getKey(), refusal.getArgs());
    }
    JSONObject body = new JSONObject();
    body.put(KEY_STATUS, statusOf(processed));
    body.put(KEY_FILE_NAME, fileName(processed));
    return NeoResponse.ok(body);
  }

  /**
   * «Descargar fichero»: generates the file now and answers it. Read only: whatever the
   * generation touches (the type's file parameters backfilled by
   * {@link RemittanceSepaFileSetup#ensure}, the creditor identifier copy of
   * {@link RemittanceCreditorId#forFile}, the line messages Classic's generator writes on a
   * refusal) is rolled back before answering, unless a batch owns the transaction.
   *
   * <p>Any processed remittance is served, a voided one ({@code AN}) included, as Classic's
   * «Create File» button (display logic {@code @Processed@='Y'}) does; the SPA offers the
   * download only on the statuses that sent a file to the bank.
   *
   * @param remittance a remittance of the tenant
   * @return {@code {fileName, contentType, content, returnedCount}}, or 422
   */
  static NeoResponse download(Remittance remittance) throws JSONException {
    try {
      if (!Boolean.TRUE.equals(remittance.isProcessed())) {
        return NeoMessageText.error(SC_UNPROCESSABLE, MSG_NOT_PROCESSED);
      }
      RemittanceFileReadiness.Result readiness = RemittanceFileReadiness.evaluate(remittance);
      if (!readiness.isReady()) {
        return notReady(readiness);
      }
      RemittanceSepaFileSetup.ensure(remittance.getRemittanceType(), readiness.scheme);
      byte[] content;
      try {
        content = generate(remittance);
      } catch (RemittanceFileGenerator.Refusal refusal) {
        return NeoMessageText.error(SC_UNPROCESSABLE, refusal.getKey(), refusal.getArgs());
      }
      JSONObject body = new JSONObject();
      body.put(KEY_FILE_NAME, fileName(remittance));
      body.put(KEY_CONTENT_TYPE, CONTENT_TYPE);
      body.put(KEY_CONTENT, new String(content, StandardCharsets.UTF_8));
      body.put(KEY_RETURNED_COUNT, RemittanceReturnState.returnedCount(remittance));
      RemittanceFileTelemetry.fileDownloaded(remittance, readiness.isCollection(),
          readiness.invoiceCount);
      return NeoResponse.ok(body);
    } finally {
      RemittanceProcessAction.rollback();
    }
  }

  private static Object statusOf(Remittance remittance) {
    Object status = remittance.get(
        org.openbravo.module.remittance.utility.RemittanceStatusUtil.PROPERTY_REMITTANCE_STATUS);
    return status != null ? status : JSONObject.NULL;
  }

  /**
   * The file name Classic's «Create File» button gives it ({@code CreateFile#getFileName}):
   * {@code <documentNo>_<name>}, its accents and spaces replaced by
   * {@link CreateFile#replaceStrangeCharacters}, then a dot and the remittance type's
   * {@value #PARAM_FILE_EXTENSION} parameter in lower case. Without that parameter it is
   * {@value RemittanceSepaFileSetup#FILE_EXTENSION} (Classic says {@code DAT}; every type GO
   * provisions sets {@code xml}).
   *
   * @param remittance the remittance
   * @return the file name
   */
  static String fileName(Remittance remittance) {
    String base = CreateFile.replaceStrangeCharacters(
        StringUtils.defaultString(remittance.getDocumentNo()) + "_"
            + StringUtils.defaultString(remittance.getName()));
    return base + "." + extensionOf(remittance);
  }

  private static String extensionOf(Remittance remittance) {
    String value = remittance.getRemittanceType() == null ? null
        : StringUtils.trimToNull(
            SEPAUtil.getStringPropertyValue(remittance, PARAM_FILE_EXTENSION));
    return value != null ? value.toLowerCase(Locale.ROOT) : RemittanceSepaFileSetup.FILE_EXTENSION;
  }

  private static NeoResponse notReady(RemittanceFileReadiness.Result readiness)
      throws JSONException {
    NeoResponse refusal = NeoMessageText.error(SC_UNPROCESSABLE, MSG_NOT_READY,
        readiness.problems.size());
    JSONObject error = refusal.getBody() != null ? refusal.getBody().optJSONObject("error") : null;
    if (error != null) {
      error.put(RemittanceFileReadiness.KEY_PROBLEMS, readiness.problems.toJson());
    }
    return refusal;
  }

  // ---------------------------------------------------------------------------
  // Seams
  // ---------------------------------------------------------------------------

  /** Seam over {@link RemittanceProcessAction#run}. */
  static OBError runProcess(String remittanceId, String grouping) {
    return RemittanceProcessAction.run(remittanceId, grouping);
  }

  /** Seam over {@link RemittanceFileGenerator#generate}. */
  static byte[] generate(Remittance remittance) throws RemittanceFileGenerator.Refusal {
    return RemittanceFileGenerator.generate(remittance);
  }
}
