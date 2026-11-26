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

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONException;
import org.codehaus.jettison.json.JSONObject;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.financialmgmt.payment.FIN_BankStatementLine;

import com.etendoerp.go.llm.DecisionInput;
import com.etendoerp.go.llm.DecisionQuestion;
import com.etendoerp.go.llm.DecisionResult;
import com.etendoerp.go.llm.LlmConfig;
import com.etendoerp.go.llm.LlmDecider;
import com.etendoerp.go.llm.LlmDecisionException;
import com.etendoerp.go.schemaforge.BankBounceAiQuestions.Assessment;
import com.etendoerp.go.schemaforge.BankBounceAiQuestions.Batch;
import com.etendoerp.go.schemaforge.BankBounceAiQuestions.Component;
import com.etendoerp.go.schemaforge.BankBounceAiQuestions.Question;
import com.etendoerp.go.schemaforge.BankBounceAiQuestions.Raw;
import com.etendoerp.go.schemaforge.BankBouncePairService.Detection;
import com.etendoerp.go.schemaforge.BankBouncePairService.Validated;
import com.etendoerp.go.schemaforge.BankBounceDetector.Pair;
import com.google.common.base.Ticker;
import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;

/**
 * ETP-6359 — the AI second layer of the bank-rejection suggestions: after the deterministic
 * detection of ETP-5581, the Decisions API ({@link LlmDecider}) is asked how likely each candidate
 * outflow is the one a {@code review} rejection reverses, and the {@code autoMatch} answer carries
 * the percentages ({@value #KEY_ASSESSMENTS}). The pure part (components, prompt, validation, the
 * 80/30 rule) is {@link BankBounceAiQuestions}; this class is the part with state and effects.
 *
 * <p><b>What the AI never does.</b> It never adds a pair, never discards on its own and never
 * changes a pair, its {@code groupKey}, its confidence or the order of its candidates. Whatever
 * goes wrong, the answer simply lacks the field and is byte for byte the one of ETP-5581.</p>
 *
 * <p><b>Gate.</b> There is no flag of its own: it runs only when {@code bank-bounce-suggestions}
 * is on for the account (the boolean the action already evaluated, carried by the {@code Plan} or
 * the {@code Detection}), on the REST channel outside a batch (an MCP call and a batch operation
 * are never annotated), when a key is configured and there is a {@code review} pair on offer.</p>
 *
 * <p><b>Order inside {@link #annotate}</b>, which matters because the call is slow and runs in the
 * request: (1) gate; (2) build the components and read the cache, all with the DAL; (3) if
 * something is missing and the back-off, the concurrency slot and the daily cap allow it, release
 * the DAL connection ({@code flush} + {@code commitAndClose}, as
 * {@code Fiscal349BoxesHandler.releaseDalConnection}); (4) one {@code decide()} call of at most
 * {@value BankBounceAiQuestions#MAX_QUESTIONS_PER_CALL} questions; (5) annotate the JSON with no
 * DAL access. Two requests missing the same component share one call (single flight).</p>
 *
 * <p><b>Guardrails.</b> Timeout {@value #TIMEOUT_MS} ms; at most {@value #MAX_CONCURRENT_CALLS}
 * calls in flight JVM-wide ({@code tryAcquire}: no slot, no AI this time); a retryable failure
 * (408, 429, 5xx, network) pauses every call for 5 minutes and any other failure for 30; at most
 * {@value #DAILY_CALL_CAP} calls per tenant per day, counted in memory; an invalid or refused
 * answer is cached as "no assessment" for an hour. A cache entry lives 24 hours. A failure logs
 * its class and whether it was retryable, never a message, an id or any part of the prompt.</p>
 *
 * <p><b>Reading the cache elsewhere.</b> {@link #checkAiChoice} (the server-side control of
 * {@code discardPair}) and {@link #facts} (the KPI) rebuild the key of a rejection's component
 * from the current detection and read the cache; they never call the model.</p>
 *
 * <p><b>Tests.</b> {@link #setSeams} replaces the decider, the connection releaser, the ticker
 * and the clock; {@link #reset} restores the runtime ones and empties every state.</p>
 */
final class BankBounceAi {

  private static final Logger log = LogManager.getLogger(BankBounceAi.class);

  /** Top-level key of the {@code autoMatch} data: the assessments, only when there are some. */
  static final String KEY_ASSESSMENTS = "bounceAiAssessments";
  /** Request key of {@code discardPair}: who chose the outflow. */
  static final String KEY_CHOICE_SOURCE = "choiceSource";
  /** {@code choiceSource} of an outflow the user took from the AI recommendation. */
  static final String CHOICE_SOURCE_AI = "ai";
  /** Usage-event target of the calls, so their spend can be told apart. */
  static final String TARGET = "bank-bounce-suggestions";
  /** Timeout of the decision call, in milliseconds (the default of the client is 30 s). */
  static final int TIMEOUT_MS = 8000;
  /** Decision calls in flight at once in the JVM. */
  static final int MAX_CONCURRENT_CALLS = 4;
  /** Decision calls per tenant per calendar day, counted in memory. */
  static final int DAILY_CALL_CAP = 200;
  /** Entries of the cache. */
  static final long CACHE_MAX_SIZE = 5000;
  /** How long an assessment is kept. */
  static final Duration CACHE_TTL = Duration.ofHours(24);
  /** How long an invalid or refused answer is remembered as "no assessment". */
  static final Duration NO_ASSESSMENT_TTL = Duration.ofHours(1);
  /** How long a failure of our own side (a bug, not the gateway) is remembered the same way. */
  static final Duration ERROR_NO_ASSESSMENT_TTL = Duration.ofMinutes(5);
  /** Pause after a retryable failure. */
  static final Duration BACKOFF_RETRYABLE = Duration.ofMinutes(5);
  /** Pause after any other failure. */
  static final Duration BACKOFF_FATAL = Duration.ofMinutes(30);
  /** Extra time a request waits for a call another request made. */
  private static final long WAIT_MARGIN_MS = 2000;

  private static final String KEY_GROUPS = "groups";
  private static final String KEY_COUNTERPART_LINE = "counterpartLine";
  private static final String KEY_STATEMENT_LINE = "statementLine";
  private static final String KEY_CANDIDATES = "candidates";
  private static final String KEY_LINE = "line";
  private static final String KEY_ID = "id";
  private static final String KEY_AMBIGUOUS = "ambiguous";

  /** The model: an injectable seam, so no test needs a gateway. */
  interface Decider {

    /**
     * Whether a call can be attempted at all (a key is configured).
     *
     * @return {@code true} when configured
     */
    boolean configured();

    /**
     * The gateway model, part of every cache key.
     *
     * @return the model id
     */
    String model();

    /**
     * One decision call.
     *
     * @param input     the evidence
     * @param questions the questions
     * @return the answers
     */
    DecisionResult decide(DecisionInput input, List<DecisionQuestion> questions);
  }

  /** Releases the DAL connection before the slow call: an injectable seam. */
  interface ConnectionReleaser {

    /** Flushes and closes the DAL session so no database connection is held during the call. */
    void release();
  }

  /**
   * A cached verdict on ONE rejection (the cache is keyed per rejection, so a change to a sibling
   * does not drop it). It holds the raw answer, never a recommendation: that is computed on read.
   *
   * @param raw             the validated raw answer, or {@code null} for "no assessment"
   * @param validUntilNanos ticker time until which a "no assessment" entry holds
   */
  private record Entry(Raw raw, long validUntilNanos) {
  }

  /**
   * What the AI facts of a decision are: the properties of the KPI event. They exist only for a
   * rejection the AI actually assessed, so {@code assessed} is always {@code true}.
   *
   * @param assessed    whether the rejection had an assessment
   * @param probability the percentage of the chosen outflow, or {@code null}
   * @param recommended whether the AI recommended an outflow
   * @param accepted    whether the recommended outflow was the one discarded
   */
  record Facts(boolean assessed, Integer probability, boolean recommended, boolean accepted) {
  }

  /** The mutable state of the class, replaced as a whole by {@link #setSeams} and {@link #reset}. */
  private static final class State {
    private final Decider decider;
    private final ConnectionReleaser releaser;
    private final Ticker ticker;
    private final Clock clock;
    private final Cache<String, Entry> cache;
    private final Map<String, CompletableFuture<Map<String, Raw>>> inFlight = new ConcurrentHashMap<>();
    private final Semaphore slots = new Semaphore(MAX_CONCURRENT_CALLS);
    private final Map<String, AtomicInteger> dailyCalls = new ConcurrentHashMap<>();
    private volatile long backoffUntilNanos;
    private volatile boolean backedOff;

    /**
     * A fresh state.
     *
     * @param decider  the model
     * @param releaser the connection releaser
     * @param ticker   the monotonic clock
     * @param clock    the wall clock (for the daily cap)
     */
    State(Decider decider, ConnectionReleaser releaser, Ticker ticker, Clock clock) {
      this.decider = decider;
      this.releaser = releaser;
      this.ticker = ticker;
      this.clock = clock;
      this.cache = CacheBuilder.newBuilder().maximumSize(CACHE_MAX_SIZE)
          .expireAfterWrite(CACHE_TTL.toNanos(), TimeUnit.NANOSECONDS).ticker(ticker).build();
    }

    /**
     * The cache entry of a key that still holds.
     *
     * @param key the component key
     * @return the entry, or {@code null} for a miss
     */
    Entry lookup(String key) {
      Entry entry = cache.getIfPresent(key);
      if (entry == null) {
        return null;
      }
      if (entry.raw() == null && ticker.read() - entry.validUntilNanos() >= 0) {
        return null;
      }
      return entry;
    }

    /**
     * The cached raw answers of every rejection of a component; a component is a hit only when
     * all of its rejections are present (a negative entry counts as present).
     *
     * <p>A component always has at least one question, so a hit always holds at least one key and
     * an empty map can only mean a miss.</p>
     *
     * @param c the component
     * @return the raw answer by rejection id ({@code null} value: no assessment), or an empty map
     *         when any rejection is missing (a miss)
     */
    Map<String, Raw> lookupComponent(Component c) {
      Map<String, Raw> raws = new HashMap<>();
      for (Question q : c.questions()) {
        Entry entry = lookup(q.key());
        if (entry == null) {
          return Map.of();
        }
        raws.put(q.rejection().id(), entry.raw());
      }
      return raws;
    }

    /**
     * Caches the verdict of every rejection of a component.
     *
     * @param c      the component
     * @param raws   the raw answers by rejection id, or {@code null} for "no assessment"
     * @param ttl    how long a "no assessment" holds (a positive one lives {@link #CACHE_TTL})
     */
    void store(Component c, Map<String, Raw> raws, Duration ttl) {
      long now = ticker.read();
      for (Question q : c.questions()) {
        Raw raw = raws != null ? raws.get(q.rejection().id()) : null;
        cache.put(q.key(), new Entry(raw, now + (raw != null ? CACHE_TTL : ttl).toNanos()));
      }
    }

    /**
     * Whether a back-off is running.
     *
     * @return {@code true} while calls are paused
     */
    boolean isBackedOff() {
      return backedOff && ticker.read() - backoffUntilNanos < 0;
    }

    /**
     * Pauses the calls.
     *
     * @param retryable whether the failure was retryable (shorter pause)
     */
    void backOff(boolean retryable) {
      Duration pause = retryable ? BACKOFF_RETRYABLE : BACKOFF_FATAL;
      backoffUntilNanos = ticker.read() + pause.toNanos();
      backedOff = true;
    }

    /**
     * Counts one call of the tenant today, unless the cap is reached.
     *
     * @param clientId the tenant
     * @return {@code true} when the call may be made
     */
    boolean takeDailyCall(String clientId) {
      LocalDate today = LocalDate.now(clock);
      dailyCalls.keySet().removeIf(k -> !k.endsWith("|" + today));
      AtomicInteger counter =
          dailyCalls.computeIfAbsent(StringUtils.defaultString(clientId) + "|" + today,
              k -> new AtomicInteger());
      if (counter.incrementAndGet() > DAILY_CALL_CAP) {
        counter.decrementAndGet();
        return false;
      }
      return true;
    }
  }

  /**
   * The current state. Held in an {@link AtomicReference} so a replacement by {@link #setSeams} or
   * {@link #reset} is safely published to every thread, which reads it once per request.
   */
  private static final AtomicReference<State> STATE = new AtomicReference<>(runtimeState());

  /**
   * The single runtime decider, created on first use by {@link LazyRuntimeDecider#runtime()};
   * {@code null} until then.
   */
  private static final AtomicReference<RuntimeDecider> RUNTIME_DECIDER = new AtomicReference<>();

  /** Utility class: static members only, never instantiated. */
  private BankBounceAi() {
  }

  // ---------------------------------------------------------------------------
  // Seams
  // ---------------------------------------------------------------------------

  /**
   * Package-private seam for tests: replaces the model, the connection releaser and the clocks,
   * with empty state.
   *
   * @param decider  the model
   * @param releaser the connection releaser
   * @param ticker   the monotonic clock of the cache and the back-off
   * @param clock    the wall clock of the daily cap
   */
  static void setSeams(Decider decider, ConnectionReleaser releaser, Ticker ticker, Clock clock) {
    STATE.set(new State(decider, releaser, ticker, clock));
  }

  /** Package-private seam for tests: restores the runtime seams and empties every state. */
  static void reset() {
    STATE.set(runtimeState());
  }

  /**
   * The state with the runtime model, the DAL releaser and the system clocks.
   *
   * @return the state
   */
  private static State runtimeState() {
    return new State(new LazyRuntimeDecider(), BankBounceAi::releaseDalConnection,
        Ticker.systemTicker(), Clock.systemDefaultZone());
  }

  /**
   * Flushes and closes the DAL session: no database connection is held while the model thinks.
   * Never throws.
   */
  private static void releaseDalConnection() {
    try {
      OBDal.getInstance().flush();
      OBDal.getInstance().commitAndClose();
    } catch (RuntimeException e) {
      log.warn("Bounce AI: could not release the DAL connection ({})", e.getClass().getSimpleName());
    }
  }

  /**
   * The runtime model. The configuration is read on every call (cheap), so a key added later is
   * picked up without a restart; the {@link LlmDecider} (it owns an HTTP client) is rebuilt only
   * when the gateway, the model or the configured state change. A key that is replaced by another
   * one keeps the old decider until the JVM restarts.
   */
  private static final class RuntimeDecider implements Decider {
    private LlmDecider decider;
    private String fingerprint;

    /**
     * The current configuration.
     *
     * @return the runtime config with the call timeout
     */
    private static LlmConfig config() {
      return LlmConfig.fromRuntime().withTimeout(TIMEOUT_MS);
    }

    @Override
    public boolean configured() {
      return config().isConfigured();
    }

    @Override
    public String model() {
      return config().getModel();
    }

    @Override
    public DecisionResult decide(DecisionInput input, List<DecisionQuestion> questions) {
      return decider().decide(input, questions);
    }

    /**
     * The decider for the current configuration.
     *
     * @return the decider, rebuilt when the configuration changed
     */
    private synchronized LlmDecider decider() {
      LlmConfig config = config();
      String current = config.decisionsUrl() + "|" + config.getModel() + "|"
          + config.isConfigured();
      if (decider == null || !current.equals(fingerprint)) {
        decider = new LlmDecider(config, TARGET);
        fingerprint = current;
      }
      return decider;
    }
  }

  /** Builds the {@link RuntimeDecider} on first use, so loading this class reads no property. */
  private static final class LazyRuntimeDecider implements Decider {

    @Override
    public boolean configured() {
      return runtime().configured();
    }

    @Override
    public String model() {
      return runtime().model();
    }

    @Override
    public DecisionResult decide(DecisionInput input, List<DecisionQuestion> questions) {
      return runtime().decide(input, questions);
    }

    /**
     * The single runtime decider. The first caller creates it; a concurrent first caller that
     * loses the race discards its own (it holds nothing yet) and uses the winner's, so every
     * caller shares one instance.
     *
     * @return the decider
     */
    private static RuntimeDecider runtime() {
      RuntimeDecider local = RUNTIME_DECIDER.get();
      if (local != null) {
        return local;
      }
      return RUNTIME_DECIDER.updateAndGet(
          current -> current != null ? current : new RuntimeDecider());
    }
  }

  // ---------------------------------------------------------------------------
  // autoMatch
  // ---------------------------------------------------------------------------

  /**
   * Adds {@value #KEY_ASSESSMENTS} to the {@code autoMatch} data when at least one rejection of
   * it was assessed. Called once, at the end of {@code buildAutoMatch}. Never throws: on any
   * failure the data is left as it was.
   *
   * @param plan the bounce plan of the request ({@code enabled()} is the
   *             {@code bank-bounce-suggestions} flag as the request evaluated it)
   * @param data the answer's {@code data}, with its {@code groups} already complete
   */
  static void annotate(BankBounceSuggestions.Plan plan, JSONObject data) {
    try {
      if (plan == null || !plan.enabled()
          || !NeoWriteChannel.REST.equals(NeoWriteChannel.current())) {
        return;
      }
      Map<String, JSONObject> offered = offeredReviewGroups(data);
      State st = STATE.get();
      if (offered.isEmpty() || !st.decider.configured()) {
        return;
      }
      List<Component> components = BankBounceAiQuestions.components(plan.offered(Set.of()),
          offered.keySet(), id -> referenceOf(plan.line(id)), st.decider.model(),
          BankBounceKpiTelemetry.accountId(null));
      if (components.isEmpty()) {
        return;
      }
      Map<String, Map<String, Assessment>> resolved =
          resolve(st, components, BankBounceKpiTelemetry.accountId(null));
      JSONObject assessments = render(components, resolved, offered);
      if (assessments.length() > 0) {
        data.put(KEY_ASSESSMENTS, assessments);
      }
    } catch (RuntimeException | JSONException | LinkageError e) {
      log.warn("Bounce AI: no assessments this time ({})", e.getClass().getSimpleName());
    }
  }

  /**
   * The offered {@code review} groups of the answer, by the id of their rejection.
   *
   * @param data the answer's data
   * @return the groups, possibly empty
   * @throws JSONException when a group cannot be read
   */
  private static Map<String, JSONObject> offeredReviewGroups(JSONObject data)
      throws JSONException {
    Map<String, JSONObject> out = new LinkedHashMap<>();
    JSONArray groups = data.optJSONArray(KEY_GROUPS);
    for (int i = 0; groups != null && i < groups.length(); i++) {
      JSONObject group = groups.optJSONObject(i);
      if (group == null
          || !BankBounceSuggestions.KIND_BOUNCE_PAIR.equals(
              group.optString(BankBounceSuggestions.KEY_KIND))
          || !BankBounceDetector.CONFIDENCE_REVIEW.equals(group.optString("confidence"))) {
        continue;
      }
      JSONObject bounce = group.optJSONObject(KEY_COUNTERPART_LINE);
      if (bounce != null && StringUtils.isNotBlank(bounce.optString(KEY_ID))) {
        out.put(bounce.getString(KEY_ID), group);
      }
    }
    return out;
  }

  /**
   * The reference of a line, as the prompt and the cache key carry it.
   *
   * @param line the line, or {@code null}
   * @return the reference, empty when there is none
   */
  private static String referenceOf(FIN_BankStatementLine line) {
    return line == null ? "" : StringUtils.trimToEmpty(line.getReferenceNo());
  }

  /**
   * The verdict of every component: from the cache, from another request's call, or from a call of
   * this request. Releases the DAL connection before any wait or call. The recommendations are
   * computed here, on read, from the cached raw answers ({@link BankBounceAiQuestions#assess}).
   *
   * @param st         the state
   * @param components the components to resolve
   * @param clientId   the tenant
   * @return the assessments by rejection id per component key
   */
  private static Map<String, Map<String, Assessment>> resolve(State st,
      List<Component> components, String clientId) {
    Map<String, Map<String, Raw>> raws = new HashMap<>();
    List<Component> misses = new ArrayList<>();
    for (Component c : components) {
      Map<String, Raw> hit = st.lookupComponent(c);
      if (hit.isEmpty()) {
        misses.add(c);
      } else {
        raws.put(c.key(), hit);
      }
    }
    if (!misses.isEmpty() && !st.isBackedOff()) {
      ask(st, BankBounceAiQuestions.pack(misses), clientId, raws);
    }
    Map<String, Map<String, Assessment>> resolved = new HashMap<>();
    for (Component c : components) {
      if (raws.containsKey(c.key())) {
        resolved.put(c.key(), BankBounceAiQuestions.assess(c, raws.get(c.key())));
      }
    }
    return resolved;
  }

  /**
   * Claims the components nobody is asking about, asks them in one call and waits for the ones
   * another request is asking about, all within one shared deadline.
   *
   * @param st       the state
   * @param toAsk    the components that fit in one call
   * @param clientId the tenant
   * @param raws     receives the raw answers by component key
   */
  private static void ask(State st, List<Component> toAsk, String clientId,
      Map<String, Map<String, Raw>> raws) {
    Map<String, CompletableFuture<Map<String, Raw>>> owned = new LinkedHashMap<>();
    Map<String, CompletableFuture<Map<String, Raw>>> awaited = new LinkedHashMap<>();
    List<Component> ownedComponents = claimComponents(st, toAsk, owned, awaited);
    long deadline = st.ticker.read() + TimeUnit.MILLISECONDS.toNanos(TIMEOUT_MS + WAIT_MARGIN_MS);
    boolean admitted = false;
    try {
      if (!ownedComponents.isEmpty()) {
        admitted = st.slots.tryAcquire() && admitDailyCall(st, clientId);
        if (!admitted) {
          ownedComponents.clear();
        }
      }
      if (ownedComponents.isEmpty() && awaited.isEmpty() && !anyCompleted(owned)) {
        return;
      }
      if (!ownedComponents.isEmpty() || !awaited.isEmpty()) {
        st.releaser.release();
      }
      if (!ownedComponents.isEmpty()) {
        callAndCache(st, ownedComponents, owned);
      }
      collect(owned, raws, 0);
      collect(awaited, raws, Math.max(0, deadline - st.ticker.read()));
    } finally {
      if (admitted) {
        st.slots.release();
      }
      for (Map.Entry<String, CompletableFuture<Map<String, Raw>>> e : owned.entrySet()) {
        e.getValue().complete(null);
        st.inFlight.remove(e.getKey(), e.getValue());
      }
    }
  }

  /**
   * Claims, in {@code inFlight}, every component nobody is asking about yet. A component another
   * request already claimed goes to {@code awaited}; a claimed one goes to {@code owned} and, unless
   * the cache already holds its answer (another request finished between our lookup and our
   * claim, which completes the future right here), to the returned list.
   *
   * @param st      the state
   * @param toAsk   the components that fit in one call
   * @param owned   receives the futures this request claimed, by component key
   * @param awaited receives the futures another request owns, by component key
   * @return the claimed components that still need the decision call, in {@code toAsk} order
   */
  private static List<Component> claimComponents(State st, List<Component> toAsk,
      Map<String, CompletableFuture<Map<String, Raw>>> owned,
      Map<String, CompletableFuture<Map<String, Raw>>> awaited) {
    List<Component> ownedComponents = new ArrayList<>();
    for (Component c : toAsk) {
      CompletableFuture<Map<String, Raw>> mine = new CompletableFuture<>();
      CompletableFuture<Map<String, Raw>> theirs = st.inFlight.putIfAbsent(c.key(), mine);
      if (theirs != null) {
        awaited.put(c.key(), theirs);
        continue;
      }
      owned.put(c.key(), mine);
      // Another request may have finished this very call between our lookup and our claim.
      Map<String, Raw> already = st.lookupComponent(c);
      if (!already.isEmpty()) {
        mine.complete(already);
      } else {
        ownedComponents.add(c);
      }
    }
    return ownedComponents;
  }

  /**
   * Whether any of the futures already holds an answer.
   *
   * @param futures the futures
   * @return {@code true} when at least one is complete
   */
  private static boolean anyCompleted(Map<String, CompletableFuture<Map<String, Raw>>> futures) {
    return futures.values().stream().anyMatch(CompletableFuture::isDone);
  }

  /**
   * Counts the daily call of the tenant; gives the concurrency slot back when the cap is reached.
   *
   * @param st       the state
   * @param clientId the tenant
   * @return {@code true} when the call may be made (the slot stays taken)
   */
  private static boolean admitDailyCall(State st, String clientId) {
    if (st.takeDailyCall(clientId)) {
      return true;
    }
    st.slots.release();
    return false;
  }

  /**
   * Makes the one decision call, validates the answer and caches the verdict of every rejection.
   * A gateway failure caches nothing and starts the back-off; any other failure (a bug of ours)
   * caches "no assessment" for {@link #ERROR_NO_ASSESSMENT_TTL}, so a deterministic bug costs one
   * attempt and not one per request.
   *
   * @param st         the state
   * @param components the components to ask, at most one call's worth
   * @param owned      the futures of the components, completed here
   */
  private static void callAndCache(State st, List<Component> components,
      Map<String, CompletableFuture<Map<String, Raw>>> owned) {
    Map<String, Map<String, Raw>> evaluated;
    Duration negativeTtl = NO_ASSESSMENT_TTL;
    try {
      Batch batch = new Batch(components);
      evaluated = batch.evaluate(st.decider.decide(batch.input(), batch.questions()));
    } catch (LlmDecisionException e) {
      st.backOff(e.isRetryable());
      log.warn("Bounce AI: decision call failed (retryable={})", e.isRetryable());
      return;
    } catch (RuntimeException e) {
      log.warn("Bounce AI: decision call not made ({})", e.getClass().getSimpleName());
      evaluated = Map.of();
      negativeTtl = ERROR_NO_ASSESSMENT_TTL;
    }
    for (Component c : components) {
      Map<String, Raw> verdict = evaluated.get(c.key());
      st.store(c, verdict, negativeTtl);
      owned.get(c.key()).complete(verdict != null ? verdict : noAssessment(c));
    }
  }

  /**
   * The "no assessment" answer of a component: every rejection maps to {@code null}.
   *
   * @param c the component
   * @return the map
   */
  private static Map<String, Raw> noAssessment(Component c) {
    Map<String, Raw> none = new HashMap<>();
    for (Question q : c.questions()) {
      none.put(q.rejection().id(), null);
    }
    return none;
  }

  /**
   * Reads the completed (or, with a wait, completing) futures.
   *
   * @param futures the futures by component key
   * @param raws    receives the raw answers by component key
   * @param waitNanos how long to wait in all for the futures not yet complete; 0 reads only what
   *                is already complete
   */
  private static void collect(Map<String, CompletableFuture<Map<String, Raw>>> futures,
      Map<String, Map<String, Raw>> raws, long waitNanos) {
    long end = System.nanoTime() + waitNanos;
    for (Map.Entry<String, CompletableFuture<Map<String, Raw>>> e : futures.entrySet()) {
      try {
        Map<String, Raw> answer = waitNanos > 0
            ? e.getValue().get(Math.max(0, end - System.nanoTime()), TimeUnit.NANOSECONDS)
            : e.getValue().getNow(null);
        if (answer != null) {
          raws.put(e.getKey(), answer);
        }
      } catch (InterruptedException ie) {
        Thread.currentThread().interrupt();
        return;
      } catch (Exception ex) {
        log.warn("Bounce AI: gave up waiting for a shared call ({})", ex.getClass().getSimpleName());
      }
    }
  }

  /**
   * The {@value #KEY_ASSESSMENTS} object: for every offered rejection of an assessed component,
   * the percentages of the candidates it is offered with, the {@code none} percentage and the
   * recommendation (only for a pair offered as ambiguous, and only an outflow it is offered with).
   * No DAL access.
   *
   * @param components the components
   * @param resolved   the verdicts
   * @param offered    the offered review groups by rejection id
   * @return the object, empty when nothing was assessed
   * @throws JSONException when it cannot be written
   */
  private static JSONObject render(List<Component> components,
      Map<String, Map<String, Assessment>> resolved, Map<String, JSONObject> offered)
      throws JSONException {
    JSONObject out = new JSONObject();
    for (Component c : components) {
      Map<String, Assessment> verdict = resolved.get(c.key());
      for (Question q : c.questions()) {
        JSONObject group = offered.get(q.rejection().id());
        Assessment a = verdict != null ? verdict.get(q.rejection().id()) : null;
        if (group != null && a != null) {
          JSONObject json = renderOne(group, a);
          if (json != null) {
            out.put(q.rejection().id(), json);
          }
        }
      }
    }
    return out;
  }

  /**
   * One rejection's entry.
   *
   * @param group the offered group of the rejection
   * @param a     its assessment
   * @return the entry, or {@code null} when none of its candidates was assessed
   * @throws JSONException when it cannot be written
   */
  private static JSONObject renderOne(JSONObject group, Assessment a) throws JSONException {
    Set<String> offeredIds = offeredOutflowIds(group);
    JSONObject probabilities = new JSONObject();
    for (Map.Entry<String, Integer> e : a.probabilities().entrySet()) {
      if (offeredIds.contains(e.getKey())) {
        probabilities.put(e.getKey(), e.getValue());
      }
    }
    if (probabilities.length() == 0) {
      return null;
    }
    boolean recommend = group.optBoolean(KEY_AMBIGUOUS, false)
        && a.recommendedLineId() != null && offeredIds.contains(a.recommendedLineId());
    return new JSONObject().put("probabilities", probabilities).put("none", a.none())
        .put("recommendedLineId", recommend ? a.recommendedLineId() : JSONObject.NULL);
  }

  /**
   * The outflows a group offers: its candidates, or its original when it is not ambiguous.
   *
   * @param group the offered group
   * @return the outflow line ids
   */
  private static Set<String> offeredOutflowIds(JSONObject group) {
    Set<String> ids = new HashSet<>();
    JSONObject original = group.optJSONObject(KEY_STATEMENT_LINE);
    if (original != null) {
      ids.add(original.optString(KEY_ID));
    }
    JSONArray candidates = group.optJSONArray(KEY_CANDIDATES);
    for (int i = 0; candidates != null && i < candidates.length(); i++) {
      JSONObject candidate = candidates.optJSONObject(i);
      JSONObject line = candidate != null ? candidate.optJSONObject(KEY_LINE) : null;
      if (line != null) {
        ids.add(line.optString(KEY_ID));
      }
    }
    return ids;
  }

  // ---------------------------------------------------------------------------
  // Cache readers: discardPair and the KPI
  // ---------------------------------------------------------------------------

  /**
   * The server-side control of {@code discardPair}, and of a {@code bouncePair} group of
   * {@code applySuggestions}, for an outflow the user took from the AI recommendation ({@code choiceSource: "ai"}): the pair must still be a detected pair and the
   * cache, read under the key of THIS rejection (its own question and candidates, rebuilt from the
   * current detection), must still recommend that outflow once the conflict check runs over the
   * siblings present now. A sibling that changed without touching this rejection's question does
   * not make it stale. Never calls the model. Without {@code choiceSource: "ai"} it does nothing.
   *
   * <p>A 409 means the lookup completed and disagreed. An internal failure is logged (class only)
   * and rethrown, so the action answers its generic server error instead of pretending the
   * suggestion is stale.</p>
   *
   * @param body      the request body of {@code discardPair}, or one {@code bouncePair} group of
   *                  {@code applySuggestions}; both carry the optional {@code choiceSource}
   * @param detection the action's detection, already read by the validation
   * @param pair      the validated pair
   * @return {@code null} to go on, or the 409 {@code BOUNCE_AI_SUGGESTION_STALE}
   * @throws Exception when the detection or the lookup fails
   */
  static NeoResponse checkAiChoice(JSONObject body, Detection detection, Validated pair)
      throws Exception {
    if (!CHOICE_SOURCE_AI.equals(body.optString(KEY_CHOICE_SOURCE, null))) {
      return null;
    }
    try {
      String outflow = pair.original().getId();
      String bounce = pair.bounce().getId();
      Assessment a = BankBounceKpiTelemetry.find(detection.pairs(), outflow, bounce) != null
          ? assessmentOf(detection, bounce) : null;
      return a != null && outflow.equals(a.recommendedLineId()) ? null
          : BankBounceErrors.conflict(BankBounceErrors.CODE_AI_SUGGESTION_STALE);
    } catch (Exception | LinkageError e) {
      log.warn("Bounce AI: could not check the AI choice ({})", e.getClass().getSimpleName());
      throw e;
    }
  }

  /**
   * The AI facts of a decision, for the KPI event. {@code null} (no {@code ai_*} property at all,
   * the event is the one of ETP-5581) unless the flag is on for the action (the detection exists,
   * honoured), the channel is the UI and the cache holds a valid assessment for that rejection.
   * Reads the cache only; never throws.
   *
   * @param detection the action's detection, or {@code null}
   * @param bounceId  the rejection the decision is about
   * @param chosenId  the outflow chosen, or {@code null} when none was
   * @param discarded whether the decision is a discard
   * @return the facts, or {@code null}
   */
  static Facts facts(Detection detection, String bounceId, String chosenId, boolean discarded) {
    if (detection == null || !detection.honoured() || bounceId == null
        || !BankBounceKpiTelemetry.CHANNEL_UI.equals(BankBounceKpiTelemetry.channel())) {
      return null;
    }
    try {
      Assessment a = assessmentOf(detection, bounceId);
      if (a == null) {
        return null;
      }
      boolean recommended = a.recommendedLineId() != null;
      return new Facts(true, chosenId != null ? a.probabilities().get(chosenId) : null,
          recommended, recommended && discarded && a.recommendedLineId().equals(chosenId));
    } catch (Exception | LinkageError e) {
      log.debug("Bounce AI: no facts for the KPI ({})", e.getClass().getSimpleName());
      return null;
    }
  }

  /**
   * The assessment of a rejection as it reads now: the raw answer cached under the key of its own
   * question, with the recommendation rule and the conflict check applied over the component the
   * current detection builds. The nominal components are built once per detection.
   *
   * @param detection the detection
   * @param bounceId  the rejection
   * @return the assessment, or {@code null} when there is none (not asked, expired, invalid)
   * @throws Exception when the detection cannot be read
   */
  private static Assessment assessmentOf(Detection detection, String bounceId) throws Exception {
    State st = STATE.get();
    for (Component c : nominalComponents(detection, st)) {
      if (c.bounceIds().contains(bounceId)) {
        Map<String, Raw> raws = new HashMap<>();
        for (Question q : c.questions()) {
          Entry entry = st.lookup(q.key());
          if (entry != null && entry.raw() != null) {
            raws.put(q.rejection().id(), entry.raw());
          }
        }
        return BankBounceAiQuestions.assess(c, raws).get(bounceId);
      }
    }
    return null;
  }

  /**
   * Every component of the detection's nominal pairs, built once per detection (the references
   * are one DAL read per line) and reused by every decision of the call.
   *
   * @param detection the detection
   * @param st        the state, for the model
   * @return the components
   * @throws Exception when the detection cannot be read
   */
  private static List<Component> nominalComponents(Detection detection, State st)
      throws Exception {
    List<Component> memo = detection.aiComponents();
    // The real Detection says "computed" even when it computed no component; a memo that merely
    // holds components also counts (a stubbed or mocked Detection answers an empty list by default).
    if (detection.aiComponentsComputed() || (memo != null && !memo.isEmpty())) {
      return memo;
    }
    List<Pair> nominal =
        new BankBounceSuggestions.Plan(detection.pairs(), Map.of(), true).offered(Set.of());
    Set<String> bounceIds = new HashSet<>();
    for (Pair p : nominal) {
      bounceIds.add(p.bounce().id());
    }
    List<Component> built = BankBounceAiQuestions.components(nominal, bounceIds,
        BankBounceAi::referenceById, st.decider.model(), BankBounceKpiTelemetry.accountId(null));
    detection.aiComponents(built);
    return built;
  }

  /**
   * The reference of a statement line read from the DAL.
   *
   * @param lineId the line id
   * @return the reference, empty when there is none
   */
  private static String referenceById(String lineId) {
    return referenceOf(OBDal.getInstance().get(FIN_BankStatementLine.class, lineId));
  }
}
