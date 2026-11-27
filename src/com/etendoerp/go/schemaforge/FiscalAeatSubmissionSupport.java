/*
 *************************************************************************
 * The contents of this file are subject to the Etendo License
 * (the "License"), you may not use this file except in compliance
 * with the License.
 * You may obtain a copy of the License at
 * https://github.com/etendosoftware/etendo_core/blob/main/legal/Etendo_license.txt
 * Software distributed under the License is distributed on an
 * "AS IS" basis, WITHOUT WARRANTY OF ANY KIND, either express or
 * implied. See the License for the specific language governing rights
 * and limitations under the License.
 * All portions are Copyright (C) 2021-2026 FUTIT SERVICES, S.L
 * All Rights Reserved.
 * Contributor(s): Futit Services S.L.
 *************************************************************************
 */
package com.etendoerp.go.schemaforge;

import java.io.BufferedReader;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileAttribute;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Set;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.apache.commons.lang3.StringUtils;
import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONObject;
import org.hibernate.criterion.Restrictions;
import org.openbravo.dal.service.OBCriteria;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.ad.utility.Attachment;
import org.openbravo.model.common.enterprise.Organization;
import org.openbravo.module.aeat303.es.presentation.AEAT303SubmissionResult;
import org.openbravo.module.aeat303.es.presentation.AEAT303SubmissionService;

import com.etendoerp.go.common.BoundedJsonParser;
import com.etendoerp.go.schemaforge.data.FiscalDecl;

/**
 * Model-agnostic pieces of an AEAT telematic submission (PresBasicaDos / ServValiDos), shared by
 * {@link Fiscal303SubmissionSupport} ({@code POST /fiscal303/submit}) and
 * {@link Fiscal390SubmissionSupport} ({@code POST /fiscal390/submit}) — ETP-5716.
 *
 * <p>Everything here was moved verbatim out of the Modelo 303 submission code, where it was the
 * only caller: the JSON request/response plumbing, the response shapes of a submission that
 * reached the AEAT ({@link #resultJson}) or failed before it ({@link #failureJson}), and the
 * justificante PDF attachment ({@link #attachPdf}). None of it branches on the model; the
 * model-specific parts (file generation, presenter/NRC rules, declaration data) stay in each
 * model's support class.
 */
final class FiscalAeatSubmissionSupport {

  static final String PARAM_TEST_MODE = "testMode";
  /** Key of the AEAT errors array in a submission answer. */
  private static final String ERRORS_KEY = "errors";

  private FiscalAeatSubmissionSupport() {
  }

  /** The request body as JSON; an empty body yields an empty object. */
  static JSONObject readJsonBody(HttpServletRequest request) throws Exception {
    StringBuilder sb = new StringBuilder();
    try (BufferedReader reader = request.getReader()) {
      String line;
      while ((line = reader.readLine()) != null) sb.append(line);
    }
    String raw = sb.toString();
    return raw.isEmpty() ? new JSONObject() : BoundedJsonParser.parseObject(raw);
  }

  static void writeJson(HttpServletResponse response, int status, JSONObject body)
      throws Exception {
    response.setContentType(AbstractFiscalHandler.JSON_CT);
    response.setStatus(status);
    response.getWriter().write(body.toString());
  }

  /**
   * The response of a submission that reached the AEAT (successfully or not):
   * {@code status} ({@code SUCCESS} / {@code TEST_SUCCESS} / {@code ERROR}), {@code testMode},
   * CSV, presentation date, registry and justificante numbers, the PDF as base64, the
   * {@code pdfDownloadFailed} flag, the AEAT {@code errors}/{@code warnings} and the model's
   * {@code declarationData}.
   */
  static JSONObject resultJson(AEAT303SubmissionResult result, JSONObject declarationData)
      throws Exception {
    boolean testMode = result.isTestMode();
    boolean successful = result.isSuccessful();
    String status;
    if (!successful) {
      status = "ERROR";
    } else if (testMode) {
      status = "TEST_SUCCESS";
    } else {
      status = "SUCCESS";
    }

    JSONObject o = new JSONObject();
    o.put("status", status);
    o.put(PARAM_TEST_MODE, testMode);
    o.put("csv", StringUtils.defaultString(result.getCsv()));
    o.put("presentationDate", StringUtils.defaultString(result.getPresentationDate()));
    o.put("registryNumber", StringUtils.defaultString(result.getRegistryNumber()));
    o.put("justificanteNumber", StringUtils.defaultString(result.getJustificanteNumber()));
    byte[] pdf = result.getPdfContent();
    o.put("pdfBase64", pdf != null ? Base64.getEncoder().encodeToString(pdf) : JSONObject.NULL);
    o.put("pdfDownloadFailed", result.isPdfDownloadFailed());
    o.put(ERRORS_KEY, new JSONArray(result.getErrors()));
    o.put("warnings", new JSONArray(result.getWarnings()));
    o.put("declarationData", declarationData);
    return o;
  }

  // ── Validation before a real presentation (ETP-5716) ─────────────────────

  /**
   * What a real presentation does when the validation that precedes it gets NO answer from the
   * AEAT validator (timeout, connection failure, an answer that is not the validator's):
   * {@code true} presents anyway — an outage of the validation service must not stop a filing on
   * a deadline day; {@code false} stops and tells the user. The only place to flip.
   */
  static final boolean PRESENT_WHEN_VALIDATOR_UNANSWERED = true;
  /** {@code errorCode} of the answer when {@link #PRESENT_WHEN_VALIDATOR_UNANSWERED} is false. */
  static final String ERR_VALIDATION_UNAVAILABLE = "VALIDATION_UNAVAILABLE";
  /** {@code stage} of an answer that stopped at the validation: nothing was presented. */
  static final String STAGE_VALIDATION = "validation";
  /** Bounded wait for the validation that precedes a presentation. */
  static final int VALIDATION_CONNECT_TIMEOUT_MILLIS = 10_000;
  static final int VALIDATION_READ_TIMEOUT_MILLIS = 30_000;

  /**
   * {@code preValidation} value of a real presentation's answer when its validation got no
   * verdict ({@link PreSubmissionValidation#notValidated}).
   */
  static final String PRE_VALIDATION_NOT_VALIDATED = "NOT_VALIDATED";
  /** Field of a real presentation's answer that reports a validation with no verdict. */
  static final String PARAM_PRE_VALIDATION = "preValidation";

  /** What the validation before a presentation decided. */
  static final class PreSubmissionValidation {
    /** The validator's rejection: the presentation must not go ahead. Null otherwise. */
    final AEAT303SubmissionResult rejected;
    /** No answer and the policy is to stop ({@link #PRESENT_WHEN_VALIDATOR_UNANSWERED}). */
    final boolean unavailable;
    /**
     * No verdict was obtained — the validator did not answer, or answered without accepting or
     * rejecting the file ("model not available", EV0). The presentation may still go ahead
     * ({@link #PRESENT_WHEN_VALIDATOR_UNANSWERED}); its answer then says the file was not
     * validated first ({@link #withPreValidation}).
     */
    final boolean notValidated;

    private PreSubmissionValidation(AEAT303SubmissionResult rejected, boolean unavailable,
        boolean notValidated) {
      this.rejected = rejected;
      this.unavailable = unavailable;
      this.notValidated = notValidated;
    }

    /** True when the real presentation goes ahead. */
    boolean proceed() {
      return rejected == null && !unavailable;
    }
  }

  /** The file a presentation is about to send, and the declaration it belongs to. */
  record FileToPresent(String modelo, String fileContent, String fiscalYear, String period,
      String language) {
  }

  /**
   * Has the AEAT validator check {@code file} — the very content about to be presented — before
   * a real presentation, with a bounded wait, and records the attempt (path
   * {@code pre_submission_validation}). Never throws. The service's timeouts are restored for the
   * presentation that follows.
   *
   * <ul>
   *   <li>accepted (warnings allowed) → proceed;</li>
   *   <li>rejected (at least one error) → stop, with the validator's result;</li>
   *   <li>no answer — or an answer that is neither accepted nor carries an error →
   *       {@link #PRESENT_WHEN_VALIDATOR_UNANSWERED}.</li>
   * </ul>
   */
  static PreSubmissionValidation validateBeforeSubmission(AEAT303SubmissionService service,
      FiscalAeatAttemptLog attemptLog, FiscalAeatAttemptLog.Declaration declaration,
      FileToPresent file) {
    FiscalAeatValidationOutcome.Path path =
        FiscalAeatValidationOutcome.Path.PRE_SUBMISSION_VALIDATION;
    String endpoint = FiscalAeatAttemptLog.endpointOf(service, true);
    AEAT303SubmissionResult result = null;
    FiscalAeatValidationOutcome outcome;
    try {
      service.withTimeouts(VALIDATION_CONNECT_TIMEOUT_MILLIS, VALIDATION_READ_TIMEOUT_MILLIS);
      result = service.submitValidation(file.modelo(), file.fileContent(), file.fiscalYear(),
          file.period(), file.language());
      if (result == null) {
        throw new IllegalStateException("The AEAT validator returned no result");
      }
      outcome = FiscalAeatValidationOutcome.answered(declaration.model(), path, result);
    } catch (Exception e) {
      AbstractFiscalHandler.log.warn("AEAT " + declaration.model() + " (decl="
          + declaration.declId() + "): the validation before the presentation got no answer", e);
      result = null;
      outcome = FiscalAeatValidationOutcome.unanswered(declaration.model(), path, true, e);
    } finally {
      restoreTimeouts(service);
    }
    attemptLog.recordAttempt(declaration, outcome, endpoint);
    // Only a verdict decides: an answer that is neither accepted nor carries a single error is
    // "no answer", never a rejection.
    if (outcome.outcome() == FiscalAeatValidationOutcome.Outcome.REJECTED) {
      return new PreSubmissionValidation(result, false, false);
    }
    if (outcome.outcome() == FiscalAeatValidationOutcome.Outcome.ACCEPTED) {
      return new PreSubmissionValidation(null, false, false);
    }
    return new PreSubmissionValidation(null, !PRESENT_WHEN_VALIDATOR_UNANSWERED, true);
  }

  /**
   * True when the validation that precedes a real presentation stopped it (and already answered
   * the request). A null validation — test mode, where nothing is validated first — never stops.
   */
  static boolean stopsPresentation(PreSubmissionValidation validation) {
    return validation != null && !validation.proceed();
  }

  /**
   * Marks a real presentation's answer — success or failure — with
   * {@code preValidation: "NOT_VALIDATED"} when the validation that preceded it got no verdict, so
   * the user learns the file was presented without being validated first. An accepted validation
   * (or none, in test mode: {@code validation == null}) leaves the answer unchanged. Returns
   * {@code answer}.
   */
  static JSONObject withPreValidation(JSONObject answer, PreSubmissionValidation validation)
      throws Exception {
    if (validation != null && validation.notValidated) {
      answer.put(PARAM_PRE_VALIDATION, PRE_VALIDATION_NOT_VALIDATED);
    }
    return answer;
  }

  private static void restoreTimeouts(AEAT303SubmissionService service) {
    try {
      service.withDefaultTimeouts();
    } catch (Exception e) {
      AbstractFiscalHandler.log.warn("Could not restore the AEAT service timeouts", e);
    }
  }

  /** One call to the AEAT service (a validation or a presentation). */
  @FunctionalInterface
  interface AeatCall {
    /**
     * Calls the AEAT.
     *
     * @return the AEAT answer
     */
    AEAT303SubmissionResult call();
  }

  /**
   * Makes one call to the AEAT and leaves its attempt row, whatever happens: the answer is
   * recorded ({@code accepted} / {@code rejected} / {@code not_validated}) and returned; a
   * RuntimeException (an {@link org.openbravo.base.exception.OBException}, or e.g. an answer that
   * cannot be parsed) is recorded as unanswered and rethrown unchanged for the caller to handle.
   */
  static AEAT303SubmissionResult callRecordingAttempt(FiscalAeatAttemptLog attemptLog,
      FiscalAeatAttemptLog.Declaration attempted, FiscalAeatValidationOutcome.Path path,
      boolean testMode, String endpoint, AeatCall call) {
    AEAT303SubmissionResult result;
    try {
      result = call.call();
    } catch (RuntimeException e) {
      attemptLog.recordAttempt(attempted,
          FiscalAeatValidationOutcome.unanswered(attempted.model(), path, testMode, e), endpoint);
      throw e;
    }
    attemptLog.recordAttempt(attempted,
        FiscalAeatValidationOutcome.answered(attempted.model(), path, result), endpoint);
    return result;
  }

  /**
   * {@code errorCode} of a "Validar sin presentar" whose answer is no verdict about the file: the
   * validator has not loaded the model/exercise yet
   * ({@link FiscalAeatValidationOutcome#isModelNotAvailable}).
   */
  static final String ERR_NOT_VALIDATED = "NOT_VALIDATED";

  /**
   * True when a TEST-mode answer (ServValiDos) is the validator saying it cannot validate this
   * model/exercise yet. Such an answer is not a rejection of the file: the caller answers
   * {@link #notValidatedJson} and stores no AEAT incident (like a validator that did not answer).
   * A production answer is never matched: there it is the filing service speaking.
   */
  static boolean isNotValidatedTestAnswer(boolean testMode, AEAT303SubmissionResult result) {
    return testMode && result != null && !result.isSuccessful()
        && FiscalAeatValidationOutcome.isModelNotAvailable(result.getErrors());
  }

  /**
   * The answer of a "Validar sin presentar" the validator could not validate
   * ({@link #isNotValidatedTestAnswer}): {@code status ERROR}, {@code testMode true},
   * {@code errorCode NOT_VALIDATED} and the AEAT text in {@code errors}, for reference.
   */
  static JSONObject notValidatedJson(AEAT303SubmissionResult result) throws Exception {
    JSONObject o = failureJson(true, ERR_NOT_VALIDATED,
        "The AEAT could not validate the file: its validation service has no information about"
            + " this model and exercise yet.");
    o.put(ERRORS_KEY, new JSONArray(result.getErrors()));
    return o;
  }

  /** The answer of a presentation stopped because the validator could not be reached. */
  static JSONObject validationUnavailableJson() throws Exception {
    JSONObject o = failureJson(false, ERR_VALIDATION_UNAVAILABLE,
        "The declaration was not submitted: the AEAT validation service did not answer.");
    o.put("stage", STAGE_VALIDATION);
    return o;
  }

  /**
   * The answer of a presentation stopped by the validator's errors: the usual result shape
   * ({@link #resultJson}, status {@code ERROR}, the AEAT errors) marked as NOT a test-mode
   * request and with {@code stage = validation} — nothing was presented.
   */
  static JSONObject validationRejectedJson(AEAT303SubmissionResult rejected,
      JSONObject declarationData) throws Exception {
    JSONObject o = resultJson(rejected, declarationData);
    o.put(PARAM_TEST_MODE, false);
    o.put("pdfBase64", JSONObject.NULL);
    o.put("stage", STAGE_VALIDATION);
    return o;
  }

  /** The response of a submission that failed before (or without) reaching the AEAT. */
  static JSONObject failureJson(boolean testMode, String errorCode, String message)
      throws Exception {
    JSONObject o = new JSONObject();
    o.put("status", "ERROR");
    o.put(PARAM_TEST_MODE, testMode);
    o.put("errorCode", errorCode);
    JSONArray errors = new JSONArray();
    errors.put(StringUtils.defaultString(message));
    o.put(ERRORS_KEY, errors);
    o.put("warnings", new JSONArray());
    return o;
  }

  /**
   * Attaches a justificante/receipt PDF to the declaration record with the same generic
   * attachment infrastructure the {@code /neo/attachments} endpoints use
   * ({@link NeoAttachmentsHelper}). Best-effort: a failure is logged and never thrown, because the
   * PDF is also returned inline (base64) in the API response. Does NOT commit — the caller commits
   * once, together with the rest of the submission write (ETP-4456 atomicity fix).
   *
   * @param tempPrefix prefix of the owner-only temporary directory the PDF is staged in
   * @param modelLabel model number used in the log lines only (e.g. {@code "303"})
   * @return true when the PDF was stored as an attachment
   */
  static boolean attachPdf(FiscalDecl decl, Organization org, String fileName, byte[] pdfContent,
      String tempPrefix, String modelLabel) {
    String tabId;
    try {
      String tableId = NeoAttachmentsHelper.resolveTableId(FiscalDecl.TABLE_NAME);
      tabId = NeoAttachmentsHelper.resolveTabId(tableId, null);
    } catch (Exception e) {
      AbstractFiscalHandler.log.warn("Could not resolve an AD_Tab for " + FiscalDecl.TABLE_NAME + " attachments", e);
      tabId = null;
    }
    if (tabId == null) {
      AbstractFiscalHandler.log.warn("No AD_Tab configured for " + FiscalDecl.TABLE_NAME + " attachments yet — the "
          + "AEAT " + modelLabel + " justificante PDF for declaration " + decl.getId() + " was not persisted as "
          + "an attachment (it is still returned inline in the API response).");
      return false;
    }

    Path tempDir = null;
    try {
      // SonarQube java:S5443 (ETP-4456): explicit owner-only permissions. Etendo GO's runtime is
      // Linux-only, so PosixFilePermissions is safe to use unconditionally here.
      FileAttribute<Set<PosixFilePermission>> ownerOnly =
          PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------"));
      tempDir = Files.createTempDirectory(tempPrefix, ownerOnly);
      Path pdfPath = tempDir.resolve(fileName);
      Files.write(pdfPath, pdfContent);
      NeoAttachmentsHelper.getAttachManager()
          .upload(new HashMap<>(), tabId, decl.getId(), org.getId(), pdfPath.toFile());
      return true;
    } catch (Exception e) {
      AbstractFiscalHandler.log.error("Could not attach the AEAT " + modelLabel + " justificante PDF to declaration "
          + decl.getId(), e);
      return false;
    } finally {
      cleanupTempDir(tempDir);
    }
  }

  /**
   * Same as {@link #attachPdf}, but the declaration keeps ONE attachment of that name: every
   * attachment of the declaration called exactly {@code fileName} (or one of
   * {@code supersededNames}, earlier names of the same document) is removed once the new one is
   * stored — a failed upload leaves the previous one in place. For a
   * document each new AEAT answer supersedes (the validation draft of a generated file). Only
   * that name is touched — never another attachment of the declaration. Best-effort and without
   * commit, like {@link #attachPdf}.
   */
  static void replaceAttachedPdf(FiscalDecl decl, Organization org, String fileName,
      byte[] pdfContent, String tempPrefix, String modelLabel, String... supersededNames) {
    List<String> names = new ArrayList<>(Arrays.asList(supersededNames));
    names.add(fileName);
    // The previous ones are read BEFORE the upload (so the new file is not among them) and
    // removed only AFTER it is stored: a failed upload leaves the previous PDF in place.
    List<Attachment> previous = new ArrayList<>();
    try {
      String tableId = NeoAttachmentsHelper.resolveTableId(FiscalDecl.TABLE_NAME);
      OBCriteria<Attachment> criteria = OBDal.getInstance().createCriteria(Attachment.class);
      criteria.add(Restrictions.eq(Attachment.PROPERTY_TABLE + ".id", tableId));
      criteria.add(Restrictions.eq(Attachment.PROPERTY_RECORD, decl.getId()));
      criteria.add(Restrictions.in(Attachment.PROPERTY_NAME, names));
      criteria.setFilterOnReadableOrganization(false);
      previous.addAll(criteria.list());
    } catch (Exception e) {
      AbstractFiscalHandler.log.error("Could not read the previous " + fileName
          + " attachments of declaration " + decl.getId(), e);
    }
    if (!attachPdf(decl, org, fileName, pdfContent, tempPrefix, modelLabel)) {
      return;
    }
    for (Attachment old : previous) {
      try {
        NeoAttachmentsHelper.getAttachManager().delete(old);
      } catch (Exception e) {
        AbstractFiscalHandler.log.error("Could not remove the previous " + fileName
            + " attachment of declaration " + decl.getId(), e);
      }
    }
  }

  private static void cleanupTempDir(Path tempDir) {
    if (tempDir == null) {
      return;
    }
    try {
      File[] children = tempDir.toFile().listFiles();
      if (children != null) {
        for (File child : children) {
          Files.deleteIfExists(child.toPath());
        }
      }
      Files.deleteIfExists(tempDir);
    } catch (Exception e) {
      AbstractFiscalHandler.log.warn("Could not clean up temporary directory " + tempDir, e);
    }
  }
}
