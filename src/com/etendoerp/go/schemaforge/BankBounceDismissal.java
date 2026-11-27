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
 * All portions are Copyright © 2021–2026 FUTIT SERVICES, S.L
 * All Rights Reserved.
 * Contributor(s): Futit Services S.L.
 * *************************************************************************
 */

package com.etendoerp.go.schemaforge;

import java.sql.Timestamp;
import java.util.Date;

import javax.servlet.http.HttpServletResponse;

import org.apache.commons.lang3.StringUtils;
import org.codehaus.jettison.json.JSONException;
import org.codehaus.jettison.json.JSONObject;
import org.openbravo.advpaymentmngt.hook.BankStatementLineFilterContext.Usage;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.financialmgmt.payment.FIN_BankStatementLine;
import org.openbravo.model.financialmgmt.payment.FIN_FinancialAccount;

import com.etendoerp.go.schemaforge.BankBounceKpiTelemetry.Match;
import com.etendoerp.go.schemaforge.BankBouncePairService.Detection;

/**
 * The panel's "No es un rechazo", persisted: the user said a statement line is NOT a bank
 * rejection. The mark is stored on that line, the one that played the rejection role of the pair,
 * as {@code EM_ETGO_Bounce_Dismissed} (when it was said), and detection never proposes that line as
 * a rejection again, with any outflow ({@link BankBounceDetector}). Its outflows stay free to pair
 * with another, real rejection, and the line itself may still be the original of a later reversal:
 * the user said it is not a rejection, not that it can never be reversed. Both lines stay ordinary
 * pending lines; nothing else is written.
 *
 * <p><b>Writing the mark</b> ({@code dismissPair}, {@link #dismiss}): the pair is resolved from the
 * request's lines through the action's own detection, exactly as the bounce-pair KPI resolves it
 * ({@link BankBounceKpiTelemetry#find}), and the mark goes on that pair's rejection line, whichever
 * line the panel had selected. The rejection line is locked {@code FOR UPDATE} and re-read before it
 * is written ({@link BankBouncePairService#lockLines}); if it left the pending pool meanwhile the
 * call is refused with 409 {@code LINE_NOT_PENDING} and nothing is written. Idempotent: the
 * rejection of a dismissed pair is no longer detected as one, so repeating the call finds no pair
 * and answers the line already marked.
 *
 * <p><b>Clearing it.</b> {@code restoreBounceSuggestion} ({@link #restoreSuggestion}) is the undo of
 * the panel: the line becomes detectable again. A hand discard that uses a marked line as the
 * rejection clears it too ({@link #clear}, from {@link BankBouncePairService#discard}): the user has
 * now said the opposite.
 *
 * <p><b>Gating.</b> Both actions require {@link BankBounceSuggestionsFlag} (403
 * {@code BOUNCE_SUGGESTIONS_OFF} otherwise); with the flag off nothing is detected, so the mark has
 * no effect and is never exposed. <b>Read by column name</b> ({@link StatementLineDiscard#get}), like
 * the other {@code EM_ETGO_Bounce_*} columns: until the DAL is regenerated the column is absent, the
 * mark is never read nor written, and {@code dismissPair} behaves as before it existed (the KPI
 * decision only).
 */
final class BankBounceDismissal {

  /** {@code POST bank-reconciliation?action=restoreBounceSuggestion}: clears the mark. */
  static final String ACTION_RESTORE_SUGGESTION = "restoreBounceSuggestion";

  /** When the user said the line is not a bank rejection; {@code null} while it was not said. */
  static final String COL_DISMISSED_AT = "EM_ETGO_Bounce_Dismissed";

  /** Row field of {@code pendingLines} and field of the {@code dismissPair} answer. */
  static final String KEY_BOUNCE_DISMISSED_AT = "bounceDismissedAt";
  /** Field of the {@code dismissPair} answer: the line that carries the mark. */
  static final String KEY_DISMISSED_LINE_ID = "dismissedLineId";
  /** Field of the {@code restoreBounceSuggestion} answer. */
  static final String KEY_RESTORED = "restored";

  /** Utility class: static members only, never instantiated. */
  private BankBounceDismissal() {
  }

  /**
   * The outcome of {@link #dismiss}.
   *
   * @param lineId the line that carries the mark after the call, or {@code null} when nothing is
   *               marked (the lines are not a detected pair, or the column is not mapped yet)
   * @param at     when the mark was set, or {@code null}
   * @param report whether the call is a new decision for the bounce-pair KPI: {@code false} when
   *               it repeats a dismissal already stored, or when nothing could be resolved
   * @param error  the refusal, or {@code null}
   */
  record Outcome(String lineId, Date at, boolean report, NeoResponse error) {

    /**
     * A refusal.
     *
     * @param error the refusal
     * @return the outcome
     */
    static Outcome refused(NeoResponse error) {
      return new Outcome(null, null, false, error);
    }

    /**
     * The {@code dismissPair} answer of this outcome.
     *
     * @return {@code {ok:true, dismissedLineId, bounceDismissedAt}}
     * @throws JSONException when it cannot be written
     */
    JSONObject json() throws JSONException {
      JSONObject data = new JSONObject();
      data.put("ok", true);
      data.put(KEY_DISMISSED_LINE_ID, lineId != null ? lineId : JSONObject.NULL);
      data.put(KEY_BOUNCE_DISMISSED_AT, at != null ? format(at) : JSONObject.NULL);
      return data;
    }
  }

  // ---------------------------------------------------------------------------
  // Reading
  // ---------------------------------------------------------------------------

  /**
   * Whether the running model maps {@link #COL_DISMISSED_AT}: before the DAL is regenerated it does
   * not, and the mark is neither read nor written.
   *
   * @return {@code true} when the column can be used
   */
  static boolean isSupported() {
    return StatementLineDiscard.property(COL_DISMISSED_AT) != null;
  }

  /**
   * When the user said the line is not a bank rejection.
   *
   * @param line the line, or {@code null}
   * @return the time, or {@code null} for a line with no mark, a {@code null} line or an unmapped
   *         column
   */
  static Date dismissedAt(FIN_BankStatementLine line) {
    if (line == null) {
      return null;
    }
    Object value = StatementLineDiscard.get(line, COL_DISMISSED_AT);
    return value instanceof Date ? (Date) value : null;
  }

  /**
   * Whether the line carries the mark, i.e. detection must never take it as a rejection.
   *
   * @param line the line, or {@code null}
   * @return {@code true} when it carries the mark
   */
  static boolean isDismissed(FIN_BankStatementLine line) {
    return dismissedAt(line) != null;
  }

  /**
   * The {@code bounceDismissedAt} value of a {@code pendingLines} row.
   *
   * @param line the DAL line behind the row, or {@code null} when it is not a pending candidate
   * @return the wire datetime, or {@link JSONObject#NULL}
   */
  static Object dismissedAtJson(FIN_BankStatementLine line) {
    Date at = dismissedAt(line);
    return at != null ? format(at) : JSONObject.NULL;
  }

  /**
   * A mark time as the canonical NEO wire datetime.
   *
   * @param at the time
   * @return the formatted value
   */
  private static String format(Date at) {
    return ReconciliationSupport.formatDate(new Timestamp(at.getTime()));
  }

  // ---------------------------------------------------------------------------
  // dismissPair: writing the mark
  // ---------------------------------------------------------------------------

  /**
   * Marks the rejection of the pair the panel dismissed. The caller has already checked the flag,
   * the account and that the named lines belong to it.
   *
   * <ol>
   *   <li>Column not mapped yet: nothing is marked; the KPI decision is still reported.</li>
   *   <li>No detected pair for the lines: nothing is marked and no KPI decision is reported; the
   *       answer names a line of the request that already carries the mark, if any — the repeat of
   *       a dismissal, since the bounce of a dismissed pair is no longer detected.</li>
   *   <li>Otherwise the pair's rejection line is locked and re-read; one that left the pending
   *       pool is refused (409 {@code LINE_NOT_PENDING}), one already marked meanwhile keeps its
   *       time, and any other gets the mark now.</li>
   * </ol>
   *
   * @param handler       the reconciliation handler, for its DAL seams
   * @param detection     the action's detection, shared with the KPI event
   * @param lineId        the line selected in the panel (rejection, original or candidate)
   * @param counterpartId the other line the card shows, or {@code null}
   * @return the outcome
   * @throws Exception when the detection cannot be read
   */
  static Outcome dismiss(ReconciliationHandler handler, Detection detection, String lineId,
      String counterpartId) throws Exception {
    if (!isSupported()) {
      return new Outcome(null, null, true, null);
    }
    // The lookup comes first, also for a line that already carries the mark: a marked line is
    // never a bounce, but it may be the original of another detected pair, whose bounce this call
    // is about (the panel omits the counterpart when that bounce is outside its date range).
    Match match = BankBounceKpiTelemetry.find(detection.pairs(), lineId, counterpartId);
    if (match == null) {
      return alreadyMarked(handler, lineId, counterpartId);
    }
    FIN_BankStatementLine rejection = handler.loadLine(match.pair().bounce().id());
    BankBouncePairService.lockLines(rejection);
    NeoResponse notPending = pendingError(rejection);
    if (notPending != null) {
      return Outcome.refused(notPending);
    }
    Date already = dismissedAt(rejection);
    if (already != null) {
      return new Outcome(rejection.getId(), already, false, null);
    }
    Date now = new Date();
    StatementLineDiscard.set(rejection, COL_DISMISSED_AT, now);
    OBDal.getInstance().save(rejection);
    OBDal.getInstance().flush();
    return new Outcome(rejection.getId(), now, true, null);
  }

  /**
   * The answer for lines that are not a detected pair: the first of them that already carries the
   * mark, or nothing.
   *
   * @param handler       the reconciliation handler
   * @param lineId        the selected line
   * @param counterpartId the other line, or {@code null}
   * @return the outcome, never a KPI decision
   */
  private static Outcome alreadyMarked(ReconciliationHandler handler, String lineId,
      String counterpartId) {
    for (String id : new String[] { lineId, counterpartId }) {
      Date at = id != null ? dismissedAt(handler.loadLine(id)) : null;
      if (at != null) {
        return new Outcome(id, at, false, null);
      }
    }
    return new Outcome(null, null, false, null);
  }

  /**
   * Clears the mark of a line about to be discarded as the rejection of a pair: the user has now
   * said it is one. A no-op when the column is not mapped or the line carries no mark. Not saved.
   *
   * @param line the rejection line of the pair
   */
  static void clear(FIN_BankStatementLine line) {
    if (isDismissed(line)) {
      StatementLineDiscard.set(line, COL_DISMISSED_AT, null);
    }
  }

  // ---------------------------------------------------------------------------
  // restoreBounceSuggestion: clearing the mark
  // ---------------------------------------------------------------------------

  /**
   * {@code POST restoreBounceSuggestion}: the shared POST envelope of the reconciliation actions
   * ({@code ReconciliationHandlerSupport.runPostAction}) around {@link #restoreSuggestion}. UI-only:
   * not declared to agents ({@link ReconciliationAgentActions}), like {@code dismissPair}.
   *
   * @param handler the reconciliation handler
   * @param context the request
   * @return the action's answer
   */
  static NeoResponse handleRestoreSuggestion(ReconciliationHandler handler, NeoContext context) {
    return ReconciliationHandlerSupport.runPostAction(handler, context, ACTION_RESTORE_SUGGESTION);
  }

  /**
   * {@code POST restoreBounceSuggestion {financialAccountId, statementLineId}}: the undo of "No es
   * un rechazo" from the line's own panel. Clears the mark, so detection may propose the line as a
   * rejection again. Requires the flag (403 {@code BOUNCE_SUGGESTIONS_OFF}); 400 without an
   * account or a {@code statementLineId}; 404 / 400 for a line not found or of another account; the
   * line is locked and re-read, and one that is not pending answers 409 {@code LINE_NOT_PENDING}.
   * A pending line with no mark (or a column not mapped yet) is left as it is and answered
   * {@code restored:false}, so repeating the call is harmless.
   *
   * @param handler the reconciliation handler, for its DAL seams
   * @param body    the request body
   * @return 200 with {@code {restored, statementLineId}}, or the refusal
   * @throws JSONException when the answer cannot be written
   */
  static NeoResponse restoreSuggestion(ReconciliationHandler handler, JSONObject body)
      throws JSONException {
    if (!BankBounceSuggestionsFlag.isEnabled()) {
      return BankBounceErrors.suggestionsOff();
    }
    FIN_FinancialAccount account = BankBouncePairService.requireAccount(handler, body);
    if (account == null) {
      return NeoResponse.error(HttpServletResponse.SC_BAD_REQUEST,
          ReconciliationHandler.MSG_ACCOUNT_NOT_FOUND
              + body.optString(ReconciliationHandler.KEY_FINANCIAL_ACCOUNT_ID, ""));
    }
    String lineId = BankBouncePairService.optId(body, ReconciliationHandler.KEY_STATEMENT_LINE_ID);
    if (lineId == null) {
      return NeoResponse.error(HttpServletResponse.SC_BAD_REQUEST,
          ReconciliationHandler.MSG_MISSING_PARAM + ReconciliationHandler.KEY_STATEMENT_LINE_ID);
    }
    FIN_BankStatementLine line = handler.loadLine(lineId);
    NeoResponse owned = BankBouncePairService.ownedLineError(line, lineId, account);
    if (owned != null) {
      return owned;
    }
    BankBouncePairService.lockLines(line);
    NeoResponse notPending = pendingError(line);
    if (notPending != null) {
      return notPending;
    }
    boolean restored = isDismissed(line);
    if (restored) {
      StatementLineDiscard.set(line, COL_DISMISSED_AT, null);
      OBDal.getInstance().save(line);
      OBDal.getInstance().flush();
    }
    JSONObject data = new JSONObject();
    data.put(KEY_RESTORED, restored);
    data.put(ReconciliationHandler.KEY_STATEMENT_LINE_ID, lineId);
    return ReconciliationSupport.envelope(data);
  }

  // ---------------------------------------------------------------------------
  // Validation
  // ---------------------------------------------------------------------------

  /**
   * 409 {@code LINE_NOT_PENDING} for a line outside the pending pool the panel lists: matched to a
   * transaction, discarded or excluded by a {@code BankStatementLineFilterHook}, inactive, on a
   * draft statement or part of a split group. {@code null} for a pending line.
   *
   * @param line the line, owned by the account and already locked
   * @return the refusal, or {@code null}
   */
  private static NeoResponse pendingError(FIN_BankStatementLine line) {
    boolean pending = line.getFinancialAccountTransaction() == null
        && Boolean.TRUE.equals(line.isActive())
        && !ReconciliationSupport.isOnDraftStatement(line)
        && StringUtils.isBlank(ReactivationSupport.readMatchGroupId(line))
        && !StatementLineDiscard.isDiscarded(line)
        && !StatementLineFilter.isExcluded(true, line, Usage.AUTO_MATCH_CANDIDATES);
    return pending ? null : BankBounceErrors.conflict(BankBounceErrors.CODE_LINE_NOT_PENDING);
  }
}
