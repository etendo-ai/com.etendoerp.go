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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.OutputStream;
import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.core.LogEvent;
import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.MockedStatic;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBDal;
import org.openbravo.erpCommon.utility.OBMessageUtils;
import org.openbravo.model.ad.system.Client;
import org.openbravo.model.financialmgmt.payment.FIN_BankStatementLine;

import com.etendoerp.go.llm.DecisionAnswer;
import com.etendoerp.go.llm.DecisionInput;
import com.etendoerp.go.llm.DecisionQuestion;
import com.etendoerp.go.llm.DecisionResult;
import com.etendoerp.go.llm.LlmConfig;
import com.etendoerp.go.llm.LlmDecisionException;
import com.etendoerp.go.schemaforge.BankBounceDetector.Candidate;
import com.etendoerp.go.schemaforge.BankBounceDetector.Pair;
import com.etendoerp.go.schemaforge.BankBounceDetector.StatementLine;
import com.etendoerp.go.schemaforge.BankBouncePairService.Detection;
import com.etendoerp.go.usageevents.LogCapture;
import com.etendoerp.go.usageevents.UsageEvent;
import com.etendoerp.go.usageevents.UsageEventRecorder;
import com.etendoerp.go.usageevents.UsageEventTypes;
import com.google.common.base.Ticker;
import com.sun.net.httpserver.HttpServer;

/**
 * The stateful half of the AI second layer of the bank-rejection suggestions
 * ({@link BankBounceAi}): when the layer runs, the one decision call per miss, the cache, the
 * single flight, the back-off, the concurrency slots, the daily cap, what the response carries and
 * what it never changes, and the readers of the cache the KPI uses. The model, the connection
 * releaser and both clocks are the seams of {@link BankBounceAi#setSeams}, so no gateway or
 * database is involved; the last group wires the production decider to a local server.
 *
 * @covers com.etendoerp.go.schemaforge.BankBounceAi
 * @covers com.etendoerp.go.schemaforge.BankBounceSuggestions
 */
@DisplayName("Bounce AI — annotate, cache, back-off, caps and the cache readers")
class BankBounceAiTest {

  private static final LocalDate DAY = LocalDate.of(2026, 10, 1);
  private static final long T0 = 1_000_000_000_000L;
  private static final String NONE = "none";

  /** The model seam: counts its calls, records what it was asked and answers by a script. */
  static final class FakeDecider implements BankBounceAi.Decider {
    volatile boolean configured = true;
    volatile String model = "fake-model";
    final AtomicInteger calls = new AtomicInteger();
    final List<List<DecisionQuestion>> questions = new CopyOnWriteArrayList<>();
    final List<DecisionInput> inputs = new CopyOnWriteArrayList<>();
    volatile Runnable beforeAnswer = () -> {
    };
    volatile Function<List<DecisionQuestion>, DecisionResult> script = qs -> favoring(qs, 0, 0.9);

    @Override
    public boolean configured() {
      return configured;
    }

    @Override
    public String model() {
      return model;
    }

    @Override
    public DecisionResult decide(DecisionInput input, List<DecisionQuestion> qs) {
      calls.incrementAndGet();
      inputs.add(input);
      questions.add(qs);
      beforeAnswer.run();
      return script.apply(qs);
    }

    /** Every question answered with {@code p} on the option {@code index}, the rest equally. */
    static DecisionResult favoring(List<DecisionQuestion> qs, int index, double p) {
      List<DecisionAnswer> answers = new ArrayList<>();
      for (DecisionQuestion dq : qs) {
        int n = dq.getOptions().size();
        int favorite = index < 0 ? n + index : index;
        Map<String, Double> map = new LinkedHashMap<>();
        for (int i = 0; i < n; i++) {
          map.put(dq.getOptions().get(i).value(), i == favorite ? p : (1 - p) / (n - 1));
        }
        answers.add(new DecisionAnswer(dq.getName(), DecisionQuestion.Type.CHOICE, false, null,
            dq.getOptions().get(favorite).value(), null, map, 0.9));
      }
      return new DecisionResult(answers, "fake-model", 10, null);
    }

    /**
     * Question {@code i} answered with {@code p} on the option {@code favorites[i]} (the rest
     * equally), so each rejection of one call can be given its own recommendation.
     */
    static DecisionResult perQuestion(List<DecisionQuestion> qs, double p, int... favorites) {
      List<DecisionAnswer> answers = new ArrayList<>();
      for (int q = 0; q < qs.size(); q++) {
        DecisionQuestion dq = qs.get(q);
        int n = dq.getOptions().size();
        Map<String, Double> map = new LinkedHashMap<>();
        for (int i = 0; i < n; i++) {
          map.put(dq.getOptions().get(i).value(), i == favorites[q] ? p : (1 - p) / (n - 1));
        }
        answers.add(new DecisionAnswer(dq.getName(), DecisionQuestion.Type.CHOICE, false, null,
            dq.getOptions().get(favorites[q]).value(), null, map, 0.9));
      }
      return new DecisionResult(answers, "fake-model", 10, null);
    }

    /** Every question answered with the given probabilities (option order, none last). */
    static DecisionResult exactly(List<DecisionQuestion> qs, double... probabilities) {
      List<DecisionAnswer> answers = new ArrayList<>();
      for (DecisionQuestion dq : qs) {
        Map<String, Double> map = new LinkedHashMap<>();
        for (int i = 0; i < probabilities.length; i++) {
          map.put(dq.getOptions().get(i).value(), probabilities[i]);
        }
        answers.add(new DecisionAnswer(dq.getName(), DecisionQuestion.Type.CHOICE, false, null,
            dq.getOptions().get(0).value(), null, map, 0.9));
      }
      return new DecisionResult(answers, "fake-model", 10, null);
    }

    /** Every question refused. */
    static DecisionResult refused(List<DecisionQuestion> qs) {
      List<DecisionAnswer> answers = new ArrayList<>();
      for (DecisionQuestion dq : qs) {
        answers.add(new DecisionAnswer(dq.getName(), DecisionQuestion.Type.CHOICE, true, null,
            null, null, Map.of(), null));
      }
      return new DecisionResult(answers, "fake-model", 10, null);
    }
  }

  /** A wall clock the test moves. */
  static final class MutableClock extends Clock {
    private volatile Instant now;

    MutableClock(Instant now) {
      this.now = now;
    }

    void advance(Duration d) {
      now = now.plus(d);
    }

    @Override
    public ZoneId getZone() {
      return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
      return this;
    }

    @Override
    public Instant instant() {
      return now;
    }
  }

  private FakeDecider decider;
  private BankBounceAi.ConnectionReleaser releaser;
  private final AtomicLong nanos = new AtomicLong(T0);
  private final Ticker ticker = new Ticker() {
    @Override
    public long read() {
      return nanos.get();
    }
  };
  private MutableClock clock;

  @BeforeEach
  void setUp() {
    decider = new FakeDecider();
    releaser = mock(BankBounceAi.ConnectionReleaser.class);
    clock = new MutableClock(Instant.parse("2026-11-24T10:00:00Z"));
    BankBounceAi.setSeams(decider, releaser, ticker, clock);
  }

  @AfterEach
  void tearDown() {
    BankBounceAi.reset();
  }

  // ---------------------------------------------------------------------------
  // fixtures
  // ---------------------------------------------------------------------------

  private static StatementLine line(String id, int dayOffset, long lineNo, String amount,
      String description) {
    return new StatementLine(id, DAY.plusDays(dayOffset), lineNo, description, "ACME",
        new BigDecimal(amount), true);
  }

  private static Pair ambiguous(StatementLine bounce, StatementLine... candidates) {
    List<Candidate> list = new ArrayList<>();
    for (StatementLine c : candidates) {
      list.add(new Candidate(c, List.of("counterparty")));
    }
    return new Pair(null, bounce, list, List.of("counterparty"),
        BankBounceDetector.CONFIDENCE_REVIEW);
  }

  private static Pair plainReview(StatementLine original, StatementLine bounce) {
    return new Pair(original, bounce, Collections.emptyList(), List.of("counterparty"),
        BankBounceDetector.CONFIDENCE_REVIEW);
  }

  private static Pair highPair(StatementLine original, StatementLine bounce) {
    return new Pair(original, bounce, Collections.emptyList(),
        List.of(BankBounceDetector.SIGNAL_RETURN_TEXT), BankBounceDetector.CONFIDENCE_HIGH);
  }

  /** One ambiguous rejection {@code <p>RB} over {@code <p>A} (older) and {@code <p>B}. */
  private static Pair review(String prefix) {
    return ambiguous(line(prefix + "RB", 5, 10, "50", "devolucion " + prefix),
        line(prefix + "A", 0, 1, "-50", "transfer a " + prefix),
        line(prefix + "B", 1, 2, "-50", "transfer b " + prefix));
  }

  private static BankBounceSuggestions.Plan plan(Pair... pairs) {
    return new BankBounceSuggestions.Plan(List.of(pairs), Map.of(), true);
  }

  /** The {@code data} of an autoMatch answer for the plan, the way {@code buildAutoMatch} makes it. */
  private static JSONObject data(BankBounceSuggestions.Plan plan, String... takenLineIds)
      throws Exception {
    JSONArray groups = new JSONArray();
    for (String id : takenLineIds) {
      groups.put(new JSONObject().put("statementLine", new JSONObject().put("id", id))
          .put("operations", new JSONArray()));
    }
    JSONObject kpis = new JSONObject();
    BankBounceSuggestions.appendGroups(plan, groups, kpis);
    return new JSONObject().put("account", "ACC-1").put("kpis", kpis).put("groups", groups);
  }

  private static JSONObject annotated(BankBounceSuggestions.Plan plan, String... taken)
      throws Exception {
    JSONObject data = data(plan, taken);
    BankBounceAi.annotate(plan, data);
    return data;
  }

  private static JSONObject assessments(JSONObject data) throws Exception {
    return data.getJSONObject(BankBounceAi.KEY_ASSESSMENTS);
  }

  // ---------------------------------------------------------------------------
  // when the layer runs
  // ---------------------------------------------------------------------------

  @Nested
  @DisplayName("gate — when the layer does not run the answer is byte-identical to the one without it")
  class Gate {

    @Test
    @DisplayName("bank-bounce-suggestions off for the request: no call, no change")
    void flagOff() throws Exception {
      BankBounceSuggestions.Plan off = new BankBounceSuggestions.Plan(List.of(review("P1")),
          Map.of(), false);
      JSONObject data = new JSONObject().put("groups", new JSONArray());
      String before = data.toString();

      BankBounceAi.annotate(off, data);
      BankBounceAi.annotate(BankBounceSuggestions.Plan.NONE, data);
      BankBounceAi.annotate(null, data);

      assertEquals(before, data.toString());
      assertEquals(0, decider.calls.get());
      verify(releaser, never()).release();
    }

    @Test
    @DisplayName("an MCP call is never annotated")
    void mcpChannel() throws Exception {
      BankBounceSuggestions.Plan plan = plan(review("P1"));
      String before = data(plan).toString();
      String previous = NeoWriteChannel.enter(NeoWriteChannel.MCP);
      try {
        JSONObject data = data(plan);
        BankBounceAi.annotate(plan, data);
        assertEquals(before, data.toString());
      } finally {
        NeoWriteChannel.restore(previous);
      }
      assertEquals(0, decider.calls.get());
      verify(releaser, never()).release();
    }

    @Test
    @DisplayName("a REST batch operation and an MCP batch operation are never annotated")
    void batchScopes() throws Exception {
      BankBounceSuggestions.Plan plan = plan(review("P1"));
      String before = data(plan).toString();

      NeoBatchScope restBatch = NeoBatchScope.open(false);
      try {
        assertEquals(NeoWriteChannel.REST_BATCH, NeoWriteChannel.current());
        JSONObject data = data(plan);
        BankBounceAi.annotate(plan, data);
        assertEquals(before, data.toString());
      } finally {
        NeoBatchScope.close(restBatch);
      }

      String previous = NeoWriteChannel.enter(NeoWriteChannel.MCP);
      NeoBatchScope mcpBatch = NeoBatchScope.open(false);
      try {
        assertEquals(NeoWriteChannel.MCP_BATCH, NeoWriteChannel.current());
        JSONObject data = data(plan);
        BankBounceAi.annotate(plan, data);
        assertEquals(before, data.toString());
      } finally {
        NeoBatchScope.close(mcpBatch);
        NeoWriteChannel.restore(previous);
      }
      assertEquals(0, decider.calls.get());
    }

    @Test
    @DisplayName("only high pairs: the model is never asked")
    void onlyHighPairs() throws Exception {
      BankBounceSuggestions.Plan plan = plan(highPair(line("OH", 0, 1, "-90", "t"),
          line("BH", 1, 2, "90", "d")));

      JSONObject data = annotated(plan);

      assertFalse(data.has(BankBounceAi.KEY_ASSESSMENTS));
      assertEquals(0, decider.calls.get());
      verify(releaser, never()).release();
    }

    @Test
    @DisplayName("no pair at all: no field, no call")
    void noPairs() throws Exception {
      JSONObject data = annotated(plan());

      assertFalse(data.has(BankBounceAi.KEY_ASSESSMENTS));
      assertEquals(0, decider.calls.get());
    }

    @Test
    @DisplayName("a review pair that the response did not offer is not asked")
    void reviewPairNotOffered() throws Exception {
      // The transaction loop took both lines of the plain review pair: it is not offered.
      BankBounceSuggestions.Plan plan = plan(plainReview(line("OP", 0, 1, "-30", "t"),
          line("RP", 2, 2, "30", "d")));

      JSONObject data = annotated(plan, "OP");

      assertFalse(data.has(BankBounceAi.KEY_ASSESSMENTS));
      assertEquals(0, decider.calls.get());
    }

    @Test
    @DisplayName("no key configured: no call, and a cached answer is not served either")
    void notConfigured() throws Exception {
      BankBounceSuggestions.Plan plan = plan(review("P1"));
      assertTrue(annotated(plan).has(BankBounceAi.KEY_ASSESSMENTS));
      assertEquals(1, decider.calls.get());

      decider.configured = false;
      JSONObject second = annotated(plan);

      assertFalse(second.has(BankBounceAi.KEY_ASSESSMENTS));
      assertEquals(1, decider.calls.get());
    }

    @Test
    @DisplayName("a configuration that cannot be read is not a failure of the answer")
    void configurationThrows() throws Exception {
      BankBounceSuggestions.Plan plan = plan(review("P1"));
      BankBounceAi.setSeams(new BankBounceAi.Decider() {
        @Override
        public boolean configured() {
          throw new IllegalStateException("no properties");
        }

        @Override
        public String model() {
          return "m";
        }

        @Override
        public DecisionResult decide(DecisionInput input, List<DecisionQuestion> qs) {
          throw new AssertionError("must not be asked");
        }
      }, releaser, ticker, clock);

      JSONObject data = data(plan);
      String before = data.toString();
      BankBounceAi.annotate(plan, data);

      assertEquals(before, data.toString());
    }
  }

  // ---------------------------------------------------------------------------
  // one call per miss, the cache
  // ---------------------------------------------------------------------------

  @Nested
  @DisplayName("cache — one call on a miss, none on a hit")
  class Cache {

    @Test
    @DisplayName("a miss makes exactly one decide(); the second autoMatch is served from the cache")
    void missThenHit() throws Exception {
      BankBounceSuggestions.Plan plan = plan(review("P1"));

      JSONObject first = annotated(plan);
      JSONObject second = annotated(plan);

      assertEquals(1, decider.calls.get());
      verify(releaser, times(1)).release();
      assertEquals(first.toString(), second.toString());
      assertTrue(first.has(BankBounceAi.KEY_ASSESSMENTS));
    }

    @Test
    @DisplayName("the exact contract: {bounceAiAssessments:{<bounceLineId>:{probabilities, none, recommendedLineId}}}")
    void jsonContract() throws Exception {
      BankBounceSuggestions.Plan plan = plan(review("P1"));

      JSONObject data = annotated(plan);

      assertEquals(new TreeSet<>(List.of("account", "kpis", "groups", "bounceAiAssessments")),
          keys(data));
      JSONObject all = assessments(data);
      assertEquals(Set.of("P1RB"), keys(all));
      JSONObject entry = all.getJSONObject("P1RB");
      assertEquals(Set.of("probabilities", "none", "recommendedLineId"), keys(entry));
      JSONObject probabilities = entry.getJSONObject("probabilities");
      assertEquals(Set.of("P1A", "P1B"), keys(probabilities));
      assertEquals(90, probabilities.getInt("P1A"));
      assertEquals(5, probabilities.getInt("P1B"));
      assertEquals(5, entry.getInt("none"));
      assertEquals("P1A", entry.getString("recommendedLineId"));
      assertTrue(probabilities.get("P1A") instanceof Integer);
    }

    @Test
    @DisplayName("without a recommendation the field is present and null")
    void nullRecommendation() throws Exception {
      decider.script = qs -> FakeDecider.exactly(qs, 0.55, 0.05, 0.40);

      JSONObject entry = assessments(annotated(plan(review("P1")))).getJSONObject("P1RB");

      assertTrue(entry.has("recommendedLineId"));
      assertTrue(entry.isNull("recommendedLineId"));
      assertEquals(40, entry.getInt("none"));
    }

    @Test
    @DisplayName("the question asked is built from the nominal pair: aliases r1/o1, none last, one call")
    void whatTheModelIsAsked() throws Exception {
      annotated(plan(review("P1")));

      List<DecisionQuestion> asked = decider.questions.get(0);
      assertEquals(1, asked.size());
      assertEquals("r1", asked.get(0).getName());
      assertEquals(List.of("o1", "o2", NONE),
          asked.get(0).getOptions().stream().map(DecisionQuestion.Option::value).toList());
    }

    @Test
    @DisplayName("changing the text of a line, or new line ids, is a miss")
    void editedContentIsAMiss() throws Exception {
      annotated(plan(review("P1")));
      assertEquals(1, decider.calls.get());

      Pair edited = ambiguous(line("P1RB", 5, 10, "50", "devolucion EDITED"),
          line("P1A", 0, 1, "-50", "transfer a P1"), line("P1B", 1, 2, "-50", "transfer b P1"));
      annotated(plan(edited));
      assertEquals(2, decider.calls.get());

      annotated(plan(review("P2")));
      assertEquals(3, decider.calls.get());
    }

    @Test
    @DisplayName("the model and the tenant are part of the key: another one is a miss")
    void modelAndTenantAreAMiss() throws Exception {
      BankBounceSuggestions.Plan plan = plan(review("P1"));
      annotated(plan);

      decider.model = "another-model";
      annotated(plan);
      assertEquals(2, decider.calls.get());

      AtomicReference<String> tenant = new AtomicReference<>("CLIENT-A");
      try (MockedStatic<OBContext> ctx = mockTenant(tenant)) {
        annotated(plan);
        assertEquals(3, decider.calls.get());
        tenant.set("CLIENT-B");
        annotated(plan);
        assertEquals(4, decider.calls.get());
        tenant.set("CLIENT-A");
        annotated(plan);
        assertEquals(4, decider.calls.get(), "CLIENT-A's answer is still cached");
      }
    }

    @Test
    @DisplayName("an assessment lives 24 hours")
    void assessmentExpiresAfterADay() throws Exception {
      BankBounceSuggestions.Plan plan = plan(review("P1"));
      annotated(plan);

      nanos.addAndGet(Duration.ofHours(23).toNanos());
      annotated(plan);
      assertEquals(1, decider.calls.get());

      nanos.addAndGet(Duration.ofHours(2).toNanos());
      annotated(plan);
      assertEquals(2, decider.calls.get());
    }

    @Test
    @DisplayName("an invalid answer is remembered as no assessment for an hour, then asked again")
    void invalidAnswerIsCachedForAnHour() throws Exception {
      BankBounceSuggestions.Plan plan = plan(review("P1"));
      decider.script = FakeDecider::refused;

      assertFalse(annotated(plan).has(BankBounceAi.KEY_ASSESSMENTS));
      nanos.addAndGet(Duration.ofMinutes(59).toNanos());
      assertFalse(annotated(plan).has(BankBounceAi.KEY_ASSESSMENTS));
      assertEquals(1, decider.calls.get(), "no second call inside the hour");

      decider.script = qs -> FakeDecider.favoring(qs, 0, 0.9);
      nanos.addAndGet(Duration.ofMinutes(2).toNanos());
      assertTrue(annotated(plan).has(BankBounceAi.KEY_ASSESSMENTS));
      assertEquals(2, decider.calls.get());
    }

    @Test
    @DisplayName("an invalid answer does not pause the calls for another rejection")
    void invalidAnswerIsNotABackoff() throws Exception {
      decider.script = FakeDecider::refused;
      annotated(plan(review("P1")));

      decider.script = qs -> FakeDecider.favoring(qs, 0, 0.9);
      assertTrue(annotated(plan(review("P2"))).has(BankBounceAi.KEY_ASSESSMENTS));
      assertEquals(2, decider.calls.get());
    }

    @Test
    @DisplayName("an answer that fails the sum or the options is cached as no assessment, nothing partial")
    void malformedAnswer() throws Exception {
      decider.script = qs -> FakeDecider.exactly(qs, 0.5, 0.2, 0.2);

      JSONObject data = annotated(plan(review("P1")));

      assertFalse(data.has(BankBounceAi.KEY_ASSESSMENTS));
    }
  }

  // ---------------------------------------------------------------------------
  // what the response carries
  // ---------------------------------------------------------------------------

  @Nested
  @DisplayName("render — only what was offered, and nothing else of the answer changes")
  class Render {

    /** One rejection over A, B and C; the transaction loop took C. */
    private BankBounceSuggestions.Plan threeCandidates() {
      return plan(ambiguous(line("RB", 5, 10, "50", "devolucion"),
          line("A", 0, 1, "-50", "ta"), line("B", 1, 2, "-50", "tb"),
          line("C", 2, 3, "-50", "tc")));
    }

    @Test
    @DisplayName("nothing assessed: the answer has no field at all")
    void nothingAssessedNoField() throws Exception {
      decider.script = FakeDecider::refused;

      JSONObject data = annotated(plan(review("P1")));

      assertFalse(data.has(BankBounceAi.KEY_ASSESSMENTS));
    }

    @Test
    @DisplayName("an offered group with fewer candidates than the nominal one gets percentages only for the offered")
    void fewerCandidatesOffered() throws Exception {
      // Nominal: A, B, C. Offered: A, B (C taken). Answer: A 85, B 5, C 5, none 5.
      decider.script = qs -> FakeDecider.exactly(qs, 0.85, 0.05, 0.05, 0.05);
      BankBounceSuggestions.Plan plan = threeCandidates();

      JSONObject data = annotated(plan, "C");

      JSONObject entry = assessments(data).getJSONObject("RB");
      assertEquals(Set.of("A", "B"), keys(entry.getJSONObject("probabilities")));
      assertEquals(85, entry.getJSONObject("probabilities").getInt("A"));
      assertEquals(5, entry.getInt("none"));
      assertEquals("A", entry.getString("recommendedLineId"));
      // The model was asked about the three nominal outflows.
      assertEquals(4, decider.questions.get(0).get(0).getOptions().size());
    }

    @Test
    @DisplayName("no recommendation when the best candidate was dropped from the offer")
    void bestCandidateDropped() throws Exception {
      decider.script = qs -> FakeDecider.exactly(qs, 0.05, 0.05, 0.85, 0.05);

      JSONObject entry = assessments(annotated(threeCandidates(), "C")).getJSONObject("RB");

      assertEquals(Set.of("A", "B"), keys(entry.getJSONObject("probabilities")));
      assertTrue(entry.isNull("recommendedLineId"));
    }

    @Test
    @DisplayName("an ambiguous pair offered as a plain one (one candidate left) gets no recommendation")
    void ambiguousOfferedAsPlain() throws Exception {
      decider.script = qs -> FakeDecider.exactly(qs, 0.92, 0.03, 0.03, 0.02);
      BankBounceSuggestions.Plan plan = threeCandidates();

      JSONObject data = annotated(plan, "B", "C");

      JSONObject group = data.getJSONArray("groups").getJSONObject(2);
      assertFalse(group.getBoolean("ambiguous"), "offered as a plain pair on A");
      JSONObject entry = assessments(data).getJSONObject("RB");
      assertEquals(Set.of("A"), keys(entry.getJSONObject("probabilities")));
      assertEquals(92, entry.getJSONObject("probabilities").getInt("A"));
      assertTrue(entry.isNull("recommendedLineId"));
    }

    @Test
    @DisplayName("a plain review pair is assessed but never recommended")
    void plainReviewPair() throws Exception {
      decider.script = qs -> FakeDecider.exactly(qs, 1.0, 0.0);

      JSONObject data = annotated(plan(plainReview(line("OP", 0, 1, "-30", "t"),
          line("RP", 2, 2, "30", "d"))));

      JSONObject entry = assessments(data).getJSONObject("RP");
      assertEquals(100, entry.getJSONObject("probabilities").getInt("OP"));
      assertEquals(0, entry.getInt("none"));
      assertTrue(entry.isNull("recommendedLineId"));
    }

    @Test
    @DisplayName("groups, groupKey, confidence and the order of the candidates are untouched")
    void nothingElseChanges() throws Exception {
      BankBounceSuggestions.Plan plan = plan(review("P1"),
          highPair(line("OH", 0, 3, "-90", "t"), line("BH", 1, 4, "90", "d")));
      String before = data(plan, "T1").toString();

      JSONObject data = data(plan, "T1");
      BankBounceAi.annotate(plan, data);

      assertTrue(data.has(BankBounceAi.KEY_ASSESSMENTS));
      JSONObject without = new JSONObject(data.toString());
      without.remove(BankBounceAi.KEY_ASSESSMENTS);
      assertEquals(new JSONObject(before).toString(), without.toString());
      JSONArray groups = data.getJSONArray("groups");
      assertEquals("transaction", groups.getJSONObject(0).getString("kind"));
      JSONObject bounce = groups.getJSONObject(2);
      assertEquals("bounce-P1RB", bounce.getString("groupKey"));
      assertEquals("review", bounce.getString("confidence"));
      assertEquals("P1A",
          bounce.getJSONArray("candidates").getJSONObject(0).getJSONObject("line").getString("id"));
      assertEquals("P1B",
          bounce.getJSONArray("candidates").getJSONObject(1).getJSONObject("line").getString("id"));
      // The high pair is not assessed.
      assertEquals(Set.of("P1RB"), keys(assessments(data)));
    }

    @Test
    @DisplayName("a high pair next to a review pair: only the review one is asked")
    void highPairIsNotAsked() throws Exception {
      annotated(plan(highPair(line("OH", 0, 3, "-90", "t"), line("BH", 1, 4, "90", "d")),
          review("P1")));

      assertEquals(1, decider.calls.get());
      assertEquals(1, decider.questions.get(0).size());
    }

    @Test
    @DisplayName("two rejections the model recommends the same outflow for get no entry at all")
    void conflictingRecommendations() throws Exception {
      StatementLine a = line("A", 0, 1, "-50", "ta");
      StatementLine b = line("B", 1, 2, "-50", "tb");
      decider.script = qs -> FakeDecider.exactly(qs, 0.9, 0.05, 0.05);
      BankBounceSuggestions.Plan plan = plan(
          ambiguous(line("R1", 5, 10, "50", "d1"), a, b),
          ambiguous(line("R2", 6, 11, "50", "d2"), a, b));

      JSONObject data = annotated(plan);

      assertEquals(1, decider.calls.get());
      assertEquals(2, decider.questions.get(0).size());
      assertFalse(data.has(BankBounceAi.KEY_ASSESSMENTS),
          "the AI abstains on both rejections: no entry at all");
    }
  }

  // ---------------------------------------------------------------------------
  // the call: order, connection, single flight
  // ---------------------------------------------------------------------------

  @Nested
  @DisplayName("the call — the DAL connection is released first, one flight per key")
  class Call {

    private FIN_BankStatementLine dalLine(String id, long amount, String reference) {
      FIN_BankStatementLine line = mock(FIN_BankStatementLine.class);
      when(line.getId()).thenReturn(id);
      when(line.getCramount()).thenReturn(amount > 0 ? BigDecimal.valueOf(amount) : BigDecimal.ZERO);
      when(line.getDramount())
          .thenReturn(amount < 0 ? BigDecimal.valueOf(-amount) : BigDecimal.ZERO);
      when(line.getDescription()).thenReturn("desc " + id);
      when(line.getReferenceNo()).thenReturn(reference);
      when(line.getBpartnername()).thenReturn("Partner " + id);
      return line;
    }

    private int referenceReads(Iterable<FIN_BankStatementLine> lines) {
      int reads = 0;
      for (FIN_BankStatementLine l : lines) {
        reads += (int) org.mockito.Mockito.mockingDetails(l).getInvocations().stream()
            .filter(i -> "getReferenceNo".equals(i.getMethod().getName())).count();
      }
      return reads;
    }

    @Test
    @DisplayName("the connection is released BEFORE decide(), and nothing reads the DAL afterwards")
    void releasedBeforeDecideAndNoDalAfter() throws Exception {
      FIN_BankStatementLine rb = dalLine("RB", 50, "REF-RB");
      FIN_BankStatementLine a = dalLine("A", -50, "REF-A");
      FIN_BankStatementLine b = dalLine("B", -50, "REF-B");
      BankBounceSuggestions.Plan plan = new BankBounceSuggestions.Plan(
          List.of(ambiguous(line("RB", 5, 10, "50", "d"), line("A", 0, 1, "-50", "ta"),
              line("B", 1, 2, "-50", "tb"))),
          Map.of("RB", rb, "A", a, "B", b), true);
      List<FIN_BankStatementLine> lines = List.of(rb, a, b);
      BankBounceAi.Decider spyDecider = spy(decider);
      AtomicInteger readsAtRelease = new AtomicInteger(-1);
      doAnswer(inv -> {
        readsAtRelease.set(referenceReads(lines));
        return null;
      }).when(releaser).release();
      BankBounceAi.setSeams(spyDecider, releaser, ticker, clock);

      try (MockedStatic<OBDal> dal = mockStatic(OBDal.class)) {
        JSONObject data = data(plan);
        dal.clearInvocations();
        BankBounceAi.annotate(plan, data);

        InOrder order = inOrder(releaser, spyDecider);
        order.verify(releaser).release();
        order.verify(spyDecider).decide(any(DecisionInput.class), anyList());
        assertEquals(referenceReads(lines), readsAtRelease.get(),
            "no line was read after the connection was released");
        assertTrue(readsAtRelease.get() > 0, "the references were read before it");
        dal.verifyNoInteractions();
        assertTrue(data.has(BankBounceAi.KEY_ASSESSMENTS));
      }
    }

    @Test
    @DisplayName("a reference of the DAL line is part of the question and the key")
    void referenceReachesTheKey() throws Exception {
      BankBounceSuggestions.Plan plan1 = new BankBounceSuggestions.Plan(List.of(review("P1")),
          Map.of("P1A", dalLine("P1A", -50, "REF-1")), true);
      BankBounceSuggestions.Plan plan2 = new BankBounceSuggestions.Plan(List.of(review("P1")),
          Map.of("P1A", dalLine("P1A", -50, "REF-2")), true);
      try (MockedStatic<OBDal> dal = mockStatic(OBDal.class)) {
        annotated(plan1);
        annotated(plan1);
        assertEquals(1, decider.calls.get());
        annotated(plan2);
        assertEquals(2, decider.calls.get());
      }
    }

    @Test
    @DisplayName("a cache hit does not release the connection")
    void hitDoesNotRelease() throws Exception {
      BankBounceSuggestions.Plan plan = plan(review("P1"));
      annotated(plan);
      org.mockito.Mockito.clearInvocations(releaser);

      annotated(plan);

      verify(releaser, never()).release();
    }

    @Test
    @DisplayName("two concurrent automatch of the same content share one call")
    void singleFlight() throws Exception {
      BankBounceSuggestions.Plan plan = plan(review("P1"));
      CountDownLatch gate = new CountDownLatch(1);
      decider.beforeAnswer = () -> await(gate);
      ExecutorService pool = Executors.newFixedThreadPool(2);
      try {
        Future<JSONObject> first = pool.submit(() -> annotated(plan));
        waitUntil(() -> decider.calls.get() == 1);
        Future<JSONObject> second = pool.submit(() -> annotated(plan));
        // The second request has missed, found the flight of the first and released its
        // connection to wait.
        verify(releaser, timeout(5000).times(2)).release();
        gate.countDown();

        assertTrue(first.get(10, TimeUnit.SECONDS).has(BankBounceAi.KEY_ASSESSMENTS));
        assertTrue(second.get(10, TimeUnit.SECONDS).has(BankBounceAi.KEY_ASSESSMENTS));
        assertEquals(1, decider.calls.get());
      } finally {
        gate.countDown();
        pool.shutdownNow();
      }
    }

    @Test
    @DisplayName("a call another request finished between our miss and our claim is not made again")
    void lostRaceDoesNotAskTwice() throws Exception {
      BankBounceSuggestions.Plan plan = plan(review("P1"));
      AtomicBoolean rival = new AtomicBoolean();

      try (MockedStatic<BankBounceAiQuestions> questions =
          mockStatic(BankBounceAiQuestions.class, CALLS_REAL_METHODS)) {
        // pack() runs after this request's cache miss and before it claims the flight: a whole
        // rival request (miss, call, store, release of its flight) happens in that gap.
        questions.when(() -> BankBounceAiQuestions.pack(any())).thenAnswer(inv -> {
          if (rival.compareAndSet(false, true)) {
            assertTrue(annotated(plan).has(BankBounceAi.KEY_ASSESSMENTS));
          }
          return inv.callRealMethod();
        });

        JSONObject data = annotated(plan);

        assertTrue(rival.get(), "the rival request ran");
        assertEquals(1, decider.calls.get(), "the claim re-reads the cache: no second model call");
        assertEquals(90, assessments(data).getJSONObject("P1RB").getJSONObject("probabilities")
            .getInt("P1A"));
        verify(releaser, times(1)).release();
      }
    }

    @Test
    @DisplayName("waiting on several calls of other requests shares ONE deadline, not one wait each")
    void sharedDeadlineWhenWaitingOnSeveralFlights() throws Exception {
      BankBounceSuggestions.Plan plan = plan(review("P1"), review("P2"));
      CountDownLatch gate = new CountDownLatch(1);
      decider.beforeAnswer = () -> await(gate);
      AtomicInteger releases = new AtomicInteger();
      // The owner releases first; the waiter's release lands between its deadline being fixed and
      // its wait starting: the fake ticker then says 8.5 s of the 10 s deadline are gone.
      doAnswer(inv -> {
        if (releases.incrementAndGet() == 2) {
          nanos.addAndGet(TimeUnit.MILLISECONDS.toNanos(8500));
        }
        return null;
      }).when(releaser).release();
      ExecutorService pool = Executors.newFixedThreadPool(2);
      try {
        Future<JSONObject> owner = pool.submit(() -> annotated(plan));
        waitUntil(() -> decider.calls.get() == 1);

        long started = System.nanoTime();
        JSONObject waiter = annotated(plan);
        long waitedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);

        assertEquals(2, releases.get(), "the waiter released its connection to wait");
        assertFalse(waiter.has(BankBounceAi.KEY_ASSESSMENTS), "the call never came back in time");
        assertTrue(waitedMs >= 1200, "it waited for what was left of the deadline: " + waitedMs);
        assertTrue(waitedMs < 2600, "two flights, one deadline of about 1.5 s: " + waitedMs);
        assertEquals(1, decider.calls.get(), "the waiter never made a call of its own");
        gate.countDown();
        assertEquals(2, assessments(owner.get(10, TimeUnit.SECONDS)).length());
      } finally {
        gate.countDown();
        pool.shutdownNow();
      }
    }

    @Test
    @DisplayName("a call is never made with every concurrency slot taken: the answer goes out without the field")
    void slotsExhausted() throws Exception {
      CountDownLatch gate = new CountDownLatch(1);
      decider.beforeAnswer = () -> await(gate);
      ExecutorService pool = Executors.newFixedThreadPool(BankBounceAi.MAX_CONCURRENT_CALLS);
      try {
        List<Future<JSONObject>> running = new ArrayList<>();
        for (int i = 0; i < BankBounceAi.MAX_CONCURRENT_CALLS; i++) {
          BankBounceSuggestions.Plan plan = plan(review("S" + i));
          running.add(pool.submit(() -> annotated(plan)));
        }
        waitUntil(() -> decider.calls.get() == BankBounceAi.MAX_CONCURRENT_CALLS);

        JSONObject fifth = annotated(plan(review("S-FIFTH")));

        assertFalse(fifth.has(BankBounceAi.KEY_ASSESSMENTS));
        assertEquals(BankBounceAi.MAX_CONCURRENT_CALLS, decider.calls.get());
        verify(releaser, times(BankBounceAi.MAX_CONCURRENT_CALLS)).release();

        gate.countDown();
        for (Future<JSONObject> f : running) {
          assertTrue(f.get(10, TimeUnit.SECONDS).has(BankBounceAi.KEY_ASSESSMENTS));
        }
        // The slots are given back: a new request is asked.
        assertTrue(annotated(plan(review("S-AFTER"))).has(BankBounceAi.KEY_ASSESSMENTS));
      } finally {
        gate.countDown();
        pool.shutdownNow();
      }
    }

    @Test
    @DisplayName("a slot is given back when the call fails")
    void slotReleasedOnFailure() throws Exception {
      decider.script = qs -> {
        throw new IllegalStateException("boom");
      };
      for (int i = 0; i < BankBounceAi.MAX_CONCURRENT_CALLS + 2; i++) {
        annotated(plan(review("F" + i)));
      }

      assertEquals(BankBounceAi.MAX_CONCURRENT_CALLS + 2, decider.calls.get());
    }

    @Test
    @DisplayName("one request makes one call of at most 20 questions: the component that does not fit waits")
    void oneCallPerRequestWithAtMostTwentyQuestions() throws Exception {
      List<Pair> pairs = new ArrayList<>();
      pairs.addAll(chain("X", 15));
      pairs.addAll(chain("Y", 10));
      BankBounceSuggestions.Plan plan = new BankBounceSuggestions.Plan(pairs, Map.of(), true);

      JSONObject data = annotated(plan);

      assertEquals(1, decider.calls.get());
      assertEquals(15, decider.questions.get(0).size());
      assertEquals(15, assessments(data).length());
      assertFalse(assessments(data).has("YRB0"), "the second component is asked another time");
      // The next request asks the one left.
      JSONObject next = annotated(plan);
      assertEquals(2, decider.calls.get());
      assertEquals(10, decider.questions.get(1).size());
      assertEquals(25, assessments(next).length());
    }

    private List<Pair> chain(String prefix, int n) {
      List<Pair> pairs = new ArrayList<>();
      for (int i = 0; i < n; i++) {
        pairs.add(ambiguous(line(prefix + "RB" + i, 20 + i, 100 + i, "50", "d" + prefix + i),
            line(prefix + "O" + i, i, 200 + i, "-50", "o" + prefix + i),
            line(prefix + "O" + (i + 1), i + 1, 200 + i + 1, "-50", "o" + prefix + (i + 1))));
      }
      return pairs;
    }
  }

  // ---------------------------------------------------------------------------
  // failures
  // ---------------------------------------------------------------------------

  @Nested
  @DisplayName("failures — back-off, caps, and a log that says nothing about the data")
  class Failures {

    @Test
    @DisplayName("a retryable failure pauses every call for 5 minutes")
    void retryableBackoff() throws Exception {
      decider.script = qs -> {
        throw new LlmDecisionException("429 rate limited", true);
      };
      BankBounceSuggestions.Plan plan = plan(review("P1"));

      assertFalse(annotated(plan).has(BankBounceAi.KEY_ASSESSMENTS));
      assertEquals(1, decider.calls.get());

      nanos.addAndGet(Duration.ofMinutes(4).plusSeconds(59).toNanos());
      annotated(plan);
      annotated(plan(review("P2")));
      assertEquals(1, decider.calls.get(), "inside the window no call, whatever the content");

      nanos.addAndGet(Duration.ofSeconds(2).toNanos());
      annotated(plan);
      assertEquals(2, decider.calls.get());
    }

    @Test
    @DisplayName("any other failure pauses the calls for 30 minutes")
    void nonRetryableBackoff() throws Exception {
      decider.script = qs -> {
        throw new LlmDecisionException("400 bad request", false);
      };
      BankBounceSuggestions.Plan plan = plan(review("P1"));

      annotated(plan);
      assertEquals(1, decider.calls.get());

      nanos.addAndGet(Duration.ofMinutes(29).plusSeconds(59).toNanos());
      annotated(plan);
      assertEquals(1, decider.calls.get());

      nanos.addAndGet(Duration.ofSeconds(2).toNanos());
      annotated(plan);
      assertEquals(2, decider.calls.get());
    }

    @Test
    @DisplayName("a cached answer is still served during a back-off")
    void cachedAnswersStillServedDuringBackoff() throws Exception {
      BankBounceSuggestions.Plan cached = plan(review("P1"));
      assertTrue(annotated(cached).has(BankBounceAi.KEY_ASSESSMENTS));
      decider.script = qs -> {
        throw new LlmDecisionException("503", true);
      };
      annotated(plan(review("P2")));

      assertTrue(annotated(cached).has(BankBounceAi.KEY_ASSESSMENTS));
      assertEquals(2, decider.calls.get());
    }

    @Test
    @DisplayName("an unexpected exception leaves the answer as it was, starts no back-off and is remembered briefly")
    void unexpectedExceptionLeavesNoField() throws Exception {
      decider.script = qs -> {
        throw new IllegalStateException("gateway exploded");
      };
      BankBounceSuggestions.Plan plan = plan(review("P1"));

      JSONObject data = annotated(plan);

      assertFalse(data.has(BankBounceAi.KEY_ASSESSMENTS));
      decider.script = qs -> FakeDecider.favoring(qs, 0, 0.9);
      assertFalse(annotated(plan).has(BankBounceAi.KEY_ASSESSMENTS),
          "a deterministic failure costs one attempt, not one per request");
      assertEquals(1, decider.calls.get());
    }

    @Test
    @DisplayName("an error of our own side is remembered 5 minutes (no back-off), then the rejection is asked again")
    void errorNoAssessmentLastsFiveMinutes() throws Exception {
      decider.script = qs -> {
        throw new IllegalStateException("a bug of ours");
      };
      BankBounceSuggestions.Plan plan = plan(review("P1"));

      assertFalse(annotated(plan).has(BankBounceAi.KEY_ASSESSMENTS));
      assertFalse(annotated(plan).has(BankBounceAi.KEY_ASSESSMENTS));
      assertEquals(1, decider.calls.get());
      annotated(plan(review("P2")));
      assertEquals(2, decider.calls.get(), "another rejection is not paused: it is no back-off");

      nanos.addAndGet(Duration.ofMinutes(5).minusSeconds(1).toNanos());
      annotated(plan);
      assertEquals(2, decider.calls.get(), "still remembered just before 5 minutes");

      decider.script = qs -> FakeDecider.favoring(qs, 0, 0.9);
      nanos.addAndGet(Duration.ofSeconds(2).toNanos());
      assertTrue(annotated(plan).has(BankBounceAi.KEY_ASSESSMENTS), "asked again after 5 minutes");
      assertEquals(3, decider.calls.get());
      assertTrue(BankBounceAi.ERROR_NO_ASSESSMENT_TTL.compareTo(BankBounceAi.NO_ASSESSMENT_TTL) < 0,
          "shorter than the hour of an invalid answer");
    }

    @Test
    @DisplayName("a decision that fails logs its class and flag, never the message, the prompt or an id")
    void failureLogsNoMessage() throws Exception {
      String secret = "SECRET-PROMPT-TEXT sk-live-123 P1RB";
      decider.script = qs -> {
        throw new LlmDecisionException(secret, true, new RuntimeException(secret));
      };
      try (LogCapture logs = LogCapture.of(BankBounceAi.class)) {
        annotated(plan(review("P1")));

        List<LogEvent> warnings = logs.events(Level.WARN);
        assertFalse(warnings.isEmpty());
        for (LogEvent e : warnings) {
          assertFalse(e.getMessage().getFormattedMessage().contains("SECRET"));
          assertFalse(e.getMessage().getFormattedMessage().contains("P1RB"));
          assertNull(e.getThrown(), "no stack trace, it would carry the message");
        }
        assertTrue(warnings.get(0).getMessage().getFormattedMessage().contains("retryable=true"));
      }
    }

    @Test
    @DisplayName("an unexpected exception logs only its class")
    void unexpectedExceptionLogsOnlyItsClass() throws Exception {
      decider.script = qs -> {
        throw new IllegalStateException("SECRET-PROMPT-TEXT");
      };
      try (LogCapture logs = LogCapture.of(BankBounceAi.class)) {
        annotated(plan(review("P1")));

        List<String> warnings = logs.messages(Level.WARN);
        assertEquals(1, warnings.size());
        assertTrue(warnings.get(0).contains("IllegalStateException"));
        assertFalse(warnings.get(0).contains("SECRET"));
      }
    }

    @Test
    @DisplayName("200 calls per tenant per day: the 201st gets no assessment, another tenant and the next day do")
    void dailyCap() throws Exception {
      AtomicReference<String> tenant = new AtomicReference<>("CLIENT-A");
      try (MockedStatic<OBContext> ctx = mockTenant(tenant)) {
        for (int i = 0; i < BankBounceAi.DAILY_CALL_CAP; i++) {
          assertTrue(annotated(plan(review("D" + i))).has(BankBounceAi.KEY_ASSESSMENTS));
        }
        assertEquals(BankBounceAi.DAILY_CALL_CAP, decider.calls.get());

        JSONObject over = annotated(plan(review("D-OVER")));
        assertFalse(over.has(BankBounceAi.KEY_ASSESSMENTS));
        assertEquals(BankBounceAi.DAILY_CALL_CAP, decider.calls.get());

        tenant.set("CLIENT-B");
        assertTrue(annotated(plan(review("D-B"))).has(BankBounceAi.KEY_ASSESSMENTS));
        assertEquals(BankBounceAi.DAILY_CALL_CAP + 1, decider.calls.get());

        tenant.set("CLIENT-A");
        clock.advance(Duration.ofDays(1));
        assertTrue(annotated(plan(review("D-NEXT-DAY"))).has(BankBounceAi.KEY_ASSESSMENTS));
        assertEquals(BankBounceAi.DAILY_CALL_CAP + 2, decider.calls.get());
      }
    }

    @Test
    @DisplayName("a cache hit does not count against the daily cap")
    void hitsAreNotCounted() throws Exception {
      BankBounceSuggestions.Plan plan = plan(review("P1"));
      for (int i = 0; i < BankBounceAi.DAILY_CALL_CAP + 10; i++) {
        assertTrue(annotated(plan).has(BankBounceAi.KEY_ASSESSMENTS));
      }

      assertEquals(1, decider.calls.get());
    }
  }

  // ---------------------------------------------------------------------------
  // the cache readers: the KPI facts
  // ---------------------------------------------------------------------------

  @Nested
  @DisplayName("facts — what the KPI reads from the cache, never calling the model")
  class Facts {

    private final Pair pair = review("P1");
    private MockedStatic<OBDal> dalStatic;
    private Detection detection;

    @BeforeEach
    void stubDal() throws Exception {
      dalStatic = mockStatic(OBDal.class);
      dalStatic.when(OBDal::getInstance).thenReturn(mock(OBDal.class));
      detection = detection(true, pair);
    }

    @AfterEach
    void closeDal() {
      dalStatic.close();
    }

    private Detection detection(boolean honoured, Pair... pairs) throws Exception {
      Detection d = mock(Detection.class);
      when(d.honoured()).thenReturn(honoured);
      when(d.pairs()).thenReturn(List.of(pairs));
      return d;
    }

    private void cacheIt() throws Exception {
      annotated(plan(pair));
    }

    @Test
    @DisplayName("the nominal components are built once per detection, not once per decision")
    void componentsAreBuiltOncePerDetection() throws Exception {
      cacheIt();
      AtomicReference<List<BankBounceAiQuestions.Component>> memo = new AtomicReference<>();
      Detection d = mock(Detection.class);
      when(d.honoured()).thenReturn(true);
      when(d.pairs()).thenReturn(List.of(pair));
      when(d.aiComponents()).thenAnswer(inv -> memo.get());
      when(d.aiComponentsComputed()).thenAnswer(inv -> memo.get() != null);
      doAnswer(inv -> {
        memo.set(inv.getArgument(0));
        return null;
      }).when(d).aiComponents(any());

      try (MockedStatic<BankBounceAiQuestions> questions =
          mockStatic(BankBounceAiQuestions.class, CALLS_REAL_METHODS)) {
        assertNotNull(BankBounceAi.facts(d, "P1RB", "P1A", true));
        assertNotNull(BankBounceAi.facts(d, "P1RB", "P1B", true));
        assertNotNull(BankBounceAi.facts(d, "P1RB", null, false));
        assertNotNull(BankBounceAi.facts(d, "P1RB", "P1A", false));

        questions.verify(() -> BankBounceAiQuestions.components(any(), any(), any(), any(), any()),
            times(1));
        // The reference of a line is one DAL read each: a second detection reads them again.
        BankBounceAi.facts(detection, "P1RB", "P1A", true);
        questions.verify(() -> BankBounceAiQuestions.components(any(), any(), any(), any(), any()),
            times(2));
      }
      verify(d, times(1)).aiComponents(any());
    }

    @Test
    @DisplayName("a real detection with no review pair computes its (empty) components once, not once per decision")
    void emptyComponentsAreComputedOncePerRealDetection() throws Exception {
      Pair onlyHigh = highPair(line("OH", 0, 1, "-90", "t"), line("BH", 1, 2, "90", "d"));
      Detection real = org.mockito.Mockito.spy(new Detection(mock(ReconciliationHandler.class),
          mock(org.openbravo.model.financialmgmt.payment.FIN_FinancialAccount.class), true));
      doReturn(List.of(onlyHigh)).when(real).pairs();

      try (MockedStatic<BankBounceAiQuestions> questions =
          mockStatic(BankBounceAiQuestions.class, CALLS_REAL_METHODS)) {
        assertNull(BankBounceAi.facts(real, "BH", "OH", true));
        assertNull(BankBounceAi.facts(real, "BH", "OH", true));
        assertNull(BankBounceAi.facts(real, "BH", null, false));

        assertTrue(real.aiComponentsComputed());
        assertTrue(real.aiComponents().isEmpty());
        verify(real, times(1)).pairs();
        questions.verify(() -> BankBounceAiQuestions.components(any(), any(), any(), any(), any()),
            times(1));
      }
      assertEquals(0, decider.calls.get());
    }

    @Test
    @DisplayName("no key configured or never asked: no facts, the event stays the ETP-5581 one")
    void notAssessed() {
      BankBounceAi.Facts facts = BankBounceAi.facts(detection, "P1RB", "P1A", true);

      assertNull(facts);
      assertEquals(0, decider.calls.get());
    }

    @Test
    @DisplayName("the recommended outflow discarded: accepted, with its percentage")
    void recommendationAccepted() throws Exception {
      cacheIt();

      BankBounceAi.Facts facts = BankBounceAi.facts(detection, "P1RB", "P1A", true);

      assertEquals(new BankBounceAi.Facts(true, 90, true, true), facts);
      assertEquals(1, decider.calls.get(), "reading the facts never calls the model");
    }

    @Test
    @DisplayName("another outflow discarded: recommended but not accepted, with the percentage of the chosen one")
    void anotherOutflowChosen() throws Exception {
      cacheIt();

      assertEquals(new BankBounceAi.Facts(true, 5, true, false),
          BankBounceAi.facts(detection, "P1RB", "P1B", true));
    }

    @Test
    @DisplayName("no outflow chosen (a dismissal, unchecked, sent to the panel): no probability, not accepted")
    void noOutflowChosen() throws Exception {
      cacheIt();

      assertEquals(new BankBounceAi.Facts(true, null, true, false),
          BankBounceAi.facts(detection, "P1RB", null, false));
    }

    @Test
    @DisplayName("the recommended outflow but the decision is not a discard: not accepted")
    void notADiscard() throws Exception {
      cacheIt();

      assertEquals(new BankBounceAi.Facts(true, 90, true, false),
          BankBounceAi.facts(detection, "P1RB", "P1A", false));
    }

    @Test
    @DisplayName("assessed without a recommendation: recommended false, accepted false")
    void assessedWithoutRecommendation() throws Exception {
      decider.script = qs -> FakeDecider.exactly(qs, 0.55, 0.05, 0.40);
      cacheIt();

      assertEquals(new BankBounceAi.Facts(true, 55, false, false),
          BankBounceAi.facts(detection, "P1RB", "P1A", true));
    }

    @Test
    @DisplayName("an invalid answer cached as no assessment reads as not assessed")
    void invalidAnswerReadsAsNotAssessed() throws Exception {
      decider.script = FakeDecider::refused;
      cacheIt();

      assertNull(BankBounceAi.facts(detection, "P1RB", "P1A", true));
    }

    @Test
    @DisplayName("no detection, not honoured, no bounce id or the MCP channel: no facts at all")
    void gates() throws Exception {
      cacheIt();

      assertNull(BankBounceAi.facts(null, "P1RB", "P1A", true));
      assertNull(BankBounceAi.facts(detection(false, pair), "P1RB", "P1A", true));
      assertNull(BankBounceAi.facts(detection, null, "P1A", true));
      String previous = NeoWriteChannel.enter(NeoWriteChannel.MCP);
      try {
        assertNull(BankBounceAi.facts(detection, "P1RB", "P1A", true));
      } finally {
        NeoWriteChannel.restore(previous);
      }
    }

    @Test
    @DisplayName("a detection that cannot be read gives no facts, not an error")
    void unreadableDetection() throws Exception {
      Detection broken = mock(Detection.class);
      when(broken.honoured()).thenReturn(true);
      when(broken.pairs()).thenThrow(new IllegalStateException("db down"));

      assertNull(BankBounceAi.facts(broken, "P1RB", "P1A", true));
    }

    @Test
    @DisplayName("a rejection the detection does not know has no assessment")
    void unknownRejection() throws Exception {
      cacheIt();

      assertNull(BankBounceAi.facts(detection, "UNKNOWN", null, false));
    }
  }

  // ---------------------------------------------------------------------------
  // the server-side control of discardPair
  // ---------------------------------------------------------------------------

  @Nested
  @DisplayName("checkAiChoice — the pair must still be detected and the cache still recommend the outflow")
  class CheckAiChoice {

    private final Pair pair = review("P1");
    private MockedStatic<OBDal> dalStatic;
    private MockedStatic<OBMessageUtils> messages;
    private Detection detection;

    @BeforeEach
    void stubDal() throws Exception {
      dalStatic = mockStatic(OBDal.class);
      dalStatic.when(OBDal::getInstance).thenReturn(mock(OBDal.class));
      // No AD_MESSAGE text in a unit test: the coded refusal falls back to the key.
      messages = mockStatic(OBMessageUtils.class);
      detection = mock(Detection.class);
      when(detection.honoured()).thenReturn(true);
      when(detection.pairs()).thenReturn(List.of(pair));
    }

    @AfterEach
    void closeDal() {
      messages.close();
      dalStatic.close();
    }

    private BankBouncePairService.Validated validated(String outflow, String bounce) {
      FIN_BankStatementLine original = mock(FIN_BankStatementLine.class);
      when(original.getId()).thenReturn(outflow);
      FIN_BankStatementLine bounceLine = mock(FIN_BankStatementLine.class);
      when(bounceLine.getId()).thenReturn(bounce);
      return new BankBouncePairService.Validated(original, bounceLine, "counterparty", "review",
          null);
    }

    private JSONObject body(String source) throws Exception {
      JSONObject body = new JSONObject().put("statementLineId", "x");
      if (source != null) {
        body.put(BankBounceAi.KEY_CHOICE_SOURCE, source);
      }
      return body;
    }

    @Test
    @DisplayName("the recommended outflow of an assessed rejection goes on (null)")
    void recommendedGoesOn() throws Exception {
      annotated(plan(pair));

      assertNull(BankBounceAi.checkAiChoice(body("ai"), detection, validated("P1A", "P1RB")));
    }

    @Test
    @DisplayName("without choiceSource ai nothing is read: the answer is null whatever the cache holds")
    void notAiIsNotChecked() throws Exception {
      Detection unreadable = mock(Detection.class);
      when(unreadable.pairs()).thenThrow(new IllegalStateException("must not be read"));

      assertNull(BankBounceAi.checkAiChoice(body(null), unreadable, validated("P1B", "P1RB")));
      assertNull(BankBounceAi.checkAiChoice(body("user"), unreadable, validated("P1B", "P1RB")));
      assertNull(BankBounceAi.checkAiChoice(body("AI"), unreadable, validated("P1B", "P1RB")));
    }

    @Test
    @DisplayName("an outflow the cache does not recommend, or nothing cached: 409 stale")
    void notRecommendedIsStale() throws Exception {
      assertEquals(409, BankBounceAi.checkAiChoice(body("ai"), detection,
          validated("P1A", "P1RB")).getHttpStatus(), "nothing cached");
      annotated(plan(pair));

      NeoResponse response =
          BankBounceAi.checkAiChoice(body("ai"), detection, validated("P1B", "P1RB"));

      assertEquals(409, response.getHttpStatus());
      assertEquals(BankBounceErrors.CODE_AI_SUGGESTION_STALE,
          response.getBody().getString("code"));
    }

    @Test
    @DisplayName("a pair that is not among the detected ones: stale, and the cache of another rejection is not used")
    void notDetectedIsStale() throws Exception {
      annotated(plan(pair));

      assertEquals(409, BankBounceAi.checkAiChoice(body("ai"), detection,
          validated("P1A", "OTHER-BOUNCE")).getHttpStatus());
      assertEquals(409, BankBounceAi.checkAiChoice(body("ai"), detection,
          validated("NOT-A-CANDIDATE", "P1RB")).getHttpStatus());
    }

    @Test
    @DisplayName("a recommendation that expired is stale")
    void expiredIsStale() throws Exception {
      annotated(plan(pair));
      nanos.addAndGet(Duration.ofHours(25).toNanos());

      assertEquals(409, BankBounceAi.checkAiChoice(body("ai"), detection,
          validated("P1A", "P1RB")).getHttpStatus());
    }

    @Test
    @DisplayName("an unreadable detection is an internal error, never reported as stale")
    void unreadableDetectionIsAnInternalError() throws Exception {
      annotated(plan(pair));
      Detection broken = mock(Detection.class);
      when(broken.pairs()).thenThrow(new IllegalStateException("db down"));

      assertThrows(IllegalStateException.class, () -> BankBounceAi.checkAiChoice(body("ai"),
          broken, validated("P1A", "P1RB")));
    }
  }

  // ---------------------------------------------------------------------------
  // the cache is per rejection: a sibling leaving does not stale the others
  // ---------------------------------------------------------------------------

  @Nested
  @DisplayName("per-rejection cache — a sibling that is discarded or edited leaves the others' assessment alone")
  class Siblings {

    private MockedStatic<OBDal> dalStatic;
    private MockedStatic<OBMessageUtils> messages;

    @BeforeEach
    void stubDal() {
      dalStatic = mockStatic(OBDal.class);
      dalStatic.when(OBDal::getInstance).thenReturn(mock(OBDal.class));
      messages = mockStatic(OBMessageUtils.class);
    }

    @AfterEach
    void closeDal() {
      messages.close();
      dalStatic.close();
    }

    private StatementLine o1() {
      return line("O1", 0, 1, "-50", "transfer 1");
    }

    private StatementLine o2() {
      return line("O2", 1, 2, "-50", "transfer 2");
    }

    private StatementLine o3(String text) {
      return line("O3", 2, 3, "-50", text);
    }

    /** Rejection A over O1 and O2: its options are [O1, O2, none]. */
    private Pair a() {
      return ambiguous(line("AR", 5, 10, "50", "devolucion a"), o1(), o2());
    }

    /** Rejection B over O2 and O3: its options are [O2, O3, none]. O2 is shared with A. */
    private Pair b() {
      return ambiguous(line("BR", 6, 11, "50", "devolucion b"), o2(), o3("transfer 3"));
    }

    private Detection detection(Pair... pairs) throws Exception {
      Detection d = mock(Detection.class);
      when(d.honoured()).thenReturn(true);
      when(d.pairs()).thenReturn(List.of(pairs));
      return d;
    }

    private BankBouncePairService.Validated validated(String outflow, String bounce) {
      FIN_BankStatementLine original = mock(FIN_BankStatementLine.class);
      when(original.getId()).thenReturn(outflow);
      FIN_BankStatementLine bounceLine = mock(FIN_BankStatementLine.class);
      when(bounceLine.getId()).thenReturn(bounce);
      return new BankBouncePairService.Validated(original, bounceLine, "counterparty", "review",
          null);
    }

    private NeoResponse aiChoice(Detection detection, String outflow, String bounce)
        throws Exception {
      return BankBounceAi.checkAiChoice(
          new JSONObject().put("statementLineId", "x").put(BankBounceAi.KEY_CHOICE_SOURCE, "ai"),
          detection, validated(outflow, bounce));
    }

    /** One call answers A with option {@code favoriteOfA} and B with {@code favoriteOfB}. */
    private void model(int favoriteOfA, int favoriteOfB) {
      decider.script = qs -> FakeDecider.perQuestion(qs, 0.9, favoriteOfA, favoriteOfB);
    }

    private String recommended(JSONObject data, String bounce) throws Exception {
      JSONObject entry = assessments(data).getJSONObject(bounce);
      return entry.isNull("recommendedLineId") ? null : entry.getString("recommendedLineId");
    }

    @Test
    @DisplayName("two rejections recommended on distinct outflows: after A is discarded B's AI choice is not stale and nothing is asked again")
    void discardingASiblingKeepsTheOthersChoiceValid() throws Exception {
      model(0, 1);
      JSONObject first = annotated(plan(a(), b()));
      assertEquals(1, decider.calls.get(), "one call for the component");
      assertEquals("O1", recommended(first, "AR"));
      assertEquals("O3", recommended(first, "BR"));
      assertNull(aiChoice(detection(a(), b()), "O3", "BR"));

      // A (with O1) is discarded: the next detection no longer lists it. B is untouched.
      assertNull(aiChoice(detection(b()), "O3", "BR"), "no 409 for B");
      JSONObject next = annotated(plan(b()));

      assertEquals(1, decider.calls.get(), "B is a cache hit of its own: the model is not asked again");
      assertEquals("O3", recommended(next, "BR"));
      assertFalse(assessments(next).has("AR"));
      assertEquals(2, assessments(next).getJSONObject("BR").getJSONObject("probabilities")
          .length(), "the two candidates of B");
    }

    @Test
    @DisplayName("an assessment the AI abstained on because of a conflict reappears once the sibling is gone")
    void aConflictClearedRecommendationReappears() throws Exception {
      model(1, 0); // A -> O2 and B -> O2: the same outflow twice
      JSONObject first = annotated(plan(a(), b()));
      assertFalse(first.has(BankBounceAi.KEY_ASSESSMENTS), "the AI abstains on both");
      assertEquals(409, aiChoice(detection(a(), b()), "O2", "BR").getHttpStatus());

      assertNull(aiChoice(detection(b()), "O2", "BR"), "B alone: O2 is recommended and valid");
      JSONObject next = annotated(plan(b()));

      assertEquals("O2", recommended(next, "BR"));
      assertEquals(1, decider.calls.get(), "recomputed on read from the cached raw answer");
    }

    @Test
    @DisplayName("a partial conflict: the response carries only the non-conflicting rejection, and the abstaining ones are refused and have no facts")
    void partialConflictKeepsOnlyTheOthers() throws Exception {
      Pair c = ambiguous(line("CR", 7, 12, "50", "devolucion c"), o3("transfer 3"),
          line("O4", 3, 4, "-50", "transfer 4"));
      // A -> O2 and B -> O2 conflict; C is alone on O4.
      decider.script = qs -> FakeDecider.perQuestion(qs, 0.9, 1, 0, 1);

      JSONObject data = annotated(plan(a(), b(), c));

      assertEquals(1, decider.calls.get());
      assertEquals(Set.of("CR"), keys(assessments(data)), "A and B abstain: no entry at all");
      assertEquals("O4", recommended(data, "CR"));
      Detection all = detection(a(), b(), c);
      assertEquals(409, aiChoice(all, "O2", "AR").getHttpStatus());
      assertEquals(409, aiChoice(all, "O2", "BR").getHttpStatus());
      assertNull(aiChoice(all, "O4", "CR"));
      assertNull(BankBounceAi.facts(all, "AR", "O2", true), "no ai_* for an abstention");
      assertNull(BankBounceAi.facts(all, "BR", "O2", true));
      assertEquals(new BankBounceAi.Facts(true, 90, true, true),
          BankBounceAi.facts(all, "CR", "O4", true));
    }

    @Test
    @DisplayName("an abstaining rejection: no entry, 409, no facts; once the sibling is gone entry, recommendation, choice and facts are back, from the same cache")
    void abstentionEndsWithTheConflict() throws Exception {
      model(1, 0); // A -> O2 and B -> O2
      Detection both = detection(a(), b());

      JSONObject first = annotated(plan(a(), b()));
      JSONObject again = annotated(plan(a(), b()));

      assertFalse(first.has(BankBounceAi.KEY_ASSESSMENTS));
      assertFalse(again.has(BankBounceAi.KEY_ASSESSMENTS), "still abstaining");
      assertEquals(1, decider.calls.get(), "the raw answers stay cached: no new model call");
      assertEquals(409, aiChoice(both, "O2", "BR").getHttpStatus());
      assertNull(BankBounceAi.facts(both, "BR", "O2", true));

      Detection alone = detection(b());
      JSONObject later = annotated(plan(b()));

      assertEquals("O2", recommended(later, "BR"));
      assertEquals(90, assessments(later).getJSONObject("BR").getJSONObject("probabilities")
          .getInt("O2"));
      assertNull(aiChoice(alone, "O2", "BR"));
      assertEquals(new BankBounceAi.Facts(true, 90, true, true),
          BankBounceAi.facts(alone, "BR", "O2", true));
      assertEquals(1, decider.calls.get());
    }

    @Test
    @DisplayName("a change to the rejection's OWN candidates (text, new or removed outflow) makes it stale and a miss")
    void ownChangeIsStaleAndAMiss() throws Exception {
      model(0, 1);
      annotated(plan(a(), b()));
      assertEquals(1, decider.calls.get());
      Pair edited = ambiguous(line("BR", 6, 11, "50", "devolucion b"), o2(), o3("transfer 3 fixed"));
      Pair added = ambiguous(line("BR", 6, 11, "50", "devolucion b"), o2(), o3("transfer 3"),
          line("O4", 3, 4, "-50", "transfer 4"));
      Pair removed = ambiguous(line("BR", 6, 11, "50", "devolucion b"), o2());
      Pair rejectionEdited = ambiguous(line("BR", 6, 11, "50", "devolucion b2"), o2(),
          o3("transfer 3"));

      for (Pair changed : new Pair[] { edited, added, removed, rejectionEdited }) {
        assertEquals(409, aiChoice(detection(a(), changed), "O3", "BR").getHttpStatus());
      }
      annotated(plan(a(), edited));

      assertEquals(2, decider.calls.get(), "B's own change is a miss: the component is asked again");
    }

    @Test
    @DisplayName("a change to a SIBLING's own candidates leaves B's choice valid and the cache of B in use")
    void siblingOwnChangeDoesNotStaleTheOther() throws Exception {
      model(0, 1);
      annotated(plan(a(), b()));
      Pair aEdited = ambiguous(line("AR", 5, 10, "50", "devolucion a"),
          line("O1", 0, 1, "-50", "transfer 1 fixed"), o2());

      assertNull(aiChoice(detection(aEdited, b()), "O3", "BR"));
      assertEquals(1, decider.calls.get());
    }
  }

  // ---------------------------------------------------------------------------
  // the production wiring
  // ---------------------------------------------------------------------------

  @Nested
  @DisplayName("production decider — the usage target, the timeout and the order against a local gateway")
  class ProductionWiring {

    private HttpServer server;
    private final List<String> events = java.util.Collections.synchronizedList(new ArrayList<>());
    private final AtomicInteger requests = new AtomicInteger();
    /** The {@code x-litellm-response-cost} the fake gateway answers with; none when null. */
    private volatile String costHeader;

    @BeforeEach
    void startServer() throws Exception {
      BankBounceAi.reset();
      resetRuntimeDecider();
      server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
      server.createContext("/", exchange -> {
        requests.incrementAndGet();
        events.add("http");
        JSONObject request = null;
        try {
          request = new JSONObject(
              new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
          JSONArray questions = request.getJSONArray("questions");
          JSONArray answers = new JSONArray();
          for (int i = 0; i < questions.length(); i++) {
            JSONObject q = questions.getJSONObject(i);
            JSONArray choices = q.getJSONArray("choices");
            JSONArray probabilities = new JSONArray();
            for (int c = 0; c < choices.length(); c++) {
              probabilities.put(new JSONObject()
                  .put("value", choices.getJSONObject(c).getString("value"))
                  .put("probability", c == 0 ? 0.9 : 0.1 / (choices.length() - 1)));
            }
            answers.put(new JSONObject().put("type", "choice").put("name", q.getString("name"))
                .put("choice", choices.getJSONObject(0).getString("value"))
                .put("probabilities", probabilities).put("confidence", 0.9));
          }
          byte[] bytes = new JSONObject().put("model", "etendo-decisions").put("answers", answers)
              .put("usage", new JSONObject().put("input_tokens", 12)).toString()
              .getBytes(StandardCharsets.UTF_8);
          if (costHeader != null) {
            exchange.getResponseHeaders().add("x-litellm-response-cost", costHeader);
          }
          exchange.sendResponseHeaders(200, bytes.length);
          try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
          }
        } catch (Exception e) {
          exchange.sendResponseHeaders(500, -1);
        }
      });
      server.start();
    }

    @AfterEach
    void stopServer() throws Exception {
      server.stop(0);
      resetRuntimeDecider();
    }

    private void resetRuntimeDecider() throws Exception {
      Field f = BankBounceAi.class.getDeclaredField("runtimeDecider");
      f.setAccessible(true);
      f.set(null, null);
    }

    private LlmConfig gateway() {
      return spy(new LlmConfig("http://127.0.0.1:" + server.getAddress().getPort(), "sk-test",
          null, 30000));
    }

    @Test
    @DisplayName("the usage event of the call has target bank-bounce-suggestions; the connection is released before the request")
    void usageTargetAndReleaseOrder() throws Exception {
      LlmConfig config = gateway();
      OBDal dal = mock(OBDal.class);
      doAnswer(inv -> events.add("flush")).when(dal).flush();
      doAnswer(inv -> events.add("commitAndClose")).when(dal).commitAndClose();
      BankBounceSuggestions.Plan plan = plan(review("P1"));

      try (MockedStatic<LlmConfig> llm = mockStatic(LlmConfig.class);
          MockedStatic<OBDal> dalStatic = mockStatic(OBDal.class);
          MockedStatic<UsageEventRecorder> usage = mockStatic(UsageEventRecorder.class)) {
        llm.when(LlmConfig::fromRuntime).thenReturn(config);
        dalStatic.when(OBDal::getInstance).thenReturn(dal);

        JSONObject data = annotated(plan);

        assertTrue(data.has(BankBounceAi.KEY_ASSESSMENTS));
        assertEquals(90, assessments(data).getJSONObject("P1RB").getJSONObject("probabilities")
            .getInt("P1A"));
        assertEquals(List.of("flush", "commitAndClose", "http"), events);
        verify(config, atLeastOnce()).withTimeout(BankBounceAi.TIMEOUT_MS);
        ArgumentCaptor<UsageEvent> event = ArgumentCaptor.forClass(UsageEvent.class);
        usage.verify(() -> UsageEventRecorder.submit(event.capture()));
        assertEquals("bank-bounce-suggestions", event.getValue().target());
        assertEquals(BankBounceAi.TARGET, event.getValue().target());
        assertEquals(UsageEventTypes.AI_DECISION_EXECUTE, event.getValue().eventType());
        assertEquals(UsageEvent.OUTCOME_OK, event.getValue().outcome());
      }
    }

    @Test
    @DisplayName("only high pairs: no request reaches the gateway and no usage event is recorded")
    void onlyHighPairsCostNothing() throws Exception {
      BankBounceSuggestions.Plan plan = plan(highPair(line("OH", 0, 1, "-90", "t"),
          line("BH", 1, 2, "90", "d")));

      try (MockedStatic<LlmConfig> llm = mockStatic(LlmConfig.class);
          MockedStatic<UsageEventRecorder> usage = mockStatic(UsageEventRecorder.class)) {
        llm.when(LlmConfig::fromRuntime).thenReturn(gateway());

        JSONObject data = annotated(plan);

        assertFalse(data.has(BankBounceAi.KEY_ASSESSMENTS));
        assertEquals(0, requests.get());
        usage.verifyNoInteractions();
      }
    }

    @Test
    @DisplayName("a repeated automatch is a cache hit: one request and one usage event in all")
    void cacheHitCostsNothing() throws Exception {
      BankBounceSuggestions.Plan plan = plan(review("P1"));

      try (MockedStatic<LlmConfig> llm = mockStatic(LlmConfig.class);
          MockedStatic<OBDal> dalStatic = mockStatic(OBDal.class);
          MockedStatic<UsageEventRecorder> usage = mockStatic(UsageEventRecorder.class)) {
        llm.when(LlmConfig::fromRuntime).thenReturn(gateway());
        dalStatic.when(OBDal::getInstance).thenReturn(mock(OBDal.class));

        annotated(plan);
        annotated(plan);

        assertEquals(1, requests.get());
        usage.verify(() -> UsageEventRecorder.submit(any(UsageEvent.class)), times(1));
      }
    }

    @Test
    @DisplayName("the usage event carries the cost the gateway reported in x-litellm-response-cost")
    void usageEventCarriesTheGatewayCost() throws Exception {
      costHeader = "0.0000401";

      try (MockedStatic<LlmConfig> llm = mockStatic(LlmConfig.class);
          MockedStatic<OBDal> dalStatic = mockStatic(OBDal.class);
          MockedStatic<UsageEventRecorder> usage = mockStatic(UsageEventRecorder.class)) {
        llm.when(LlmConfig::fromRuntime).thenReturn(gateway());
        dalStatic.when(OBDal::getInstance).thenReturn(mock(OBDal.class));

        assertTrue(annotated(plan(review("P1"))).has(BankBounceAi.KEY_ASSESSMENTS));

        ArgumentCaptor<UsageEvent> event = ArgumentCaptor.forClass(UsageEvent.class);
        usage.verify(() -> UsageEventRecorder.submit(event.capture()));
        assertEquals(BankBounceAi.TARGET, event.getValue().target());
        assertEquals(new BigDecimal("0.0000401"), event.getValue().costUsd(),
            "what billing sums as the tenant's AI spend");
      }
    }

    @Test
    @DisplayName("a gateway that reports no cost leaves the usage event unpriced, not free")
    void noCostHeaderIsUnpriced() throws Exception {
      costHeader = null;

      try (MockedStatic<LlmConfig> llm = mockStatic(LlmConfig.class);
          MockedStatic<OBDal> dalStatic = mockStatic(OBDal.class);
          MockedStatic<UsageEventRecorder> usage = mockStatic(UsageEventRecorder.class)) {
        llm.when(LlmConfig::fromRuntime).thenReturn(gateway());
        dalStatic.when(OBDal::getInstance).thenReturn(mock(OBDal.class));

        annotated(plan(review("P1")));

        ArgumentCaptor<UsageEvent> event = ArgumentCaptor.forClass(UsageEvent.class);
        usage.verify(() -> UsageEventRecorder.submit(event.capture()));
        assertNull(event.getValue().costUsd());
      }
    }

    @Test
    @DisplayName("the configuration is read on every call: a key added later takes effect without a restart")
    void aKeyAddedLaterIsPickedUp() throws Exception {
      LlmConfig noKey =
          new LlmConfig("http://127.0.0.1:" + server.getAddress().getPort(), null, null, 1000);

      try (MockedStatic<LlmConfig> llm = mockStatic(LlmConfig.class);
          MockedStatic<OBDal> dalStatic = mockStatic(OBDal.class);
          MockedStatic<UsageEventRecorder> usage = mockStatic(UsageEventRecorder.class)) {
        dalStatic.when(OBDal::getInstance).thenReturn(mock(OBDal.class));
        llm.when(LlmConfig::fromRuntime).thenReturn(noKey);
        assertFalse(annotated(plan(review("P1"))).has(BankBounceAi.KEY_ASSESSMENTS));
        assertEquals(0, requests.get());

        llm.when(LlmConfig::fromRuntime).thenReturn(gateway());
        assertTrue(annotated(plan(review("P1"))).has(BankBounceAi.KEY_ASSESSMENTS));
        assertEquals(1, requests.get());

        llm.when(LlmConfig::fromRuntime).thenReturn(noKey);
        assertFalse(annotated(plan(review("P2"))).has(BankBounceAi.KEY_ASSESSMENTS),
            "a key that is removed stops the calls");
        assertEquals(1, requests.get());
      }
    }

    @Test
    @DisplayName("the decider is rebuilt only when the gateway, the model or the configured state change")
    void deciderIsRebuiltOnlyOnAConfigChange() throws Exception {
      String url = "http://127.0.0.1:" + server.getAddress().getPort();

      try (MockedStatic<LlmConfig> llm = mockStatic(LlmConfig.class);
          MockedStatic<OBDal> dalStatic = mockStatic(OBDal.class);
          MockedStatic<UsageEventRecorder> usage = mockStatic(UsageEventRecorder.class)) {
        dalStatic.when(OBDal::getInstance).thenReturn(mock(OBDal.class));
        llm.when(LlmConfig::fromRuntime).thenReturn(new LlmConfig(url, "sk-test", null, 30000));

        annotated(plan(review("P1")));
        Object first = currentLlmDecider();
        annotated(plan(review("P2")));
        assertNotNull(first);
        assertSame(first, currentLlmDecider(), "same configuration: the same decider (and client)");

        llm.when(LlmConfig::fromRuntime).thenReturn(new LlmConfig(url, "sk-test", "other-model", 30000));
        annotated(plan(review("P3")));
        assertNotSame(first, currentLlmDecider(), "another model: a new decider");
        assertEquals(3, requests.get());
      }
    }

    /** The {@code LlmDecider} the runtime decider holds now (null before the first call). */
    private Object currentLlmDecider() throws Exception {
      Field holder = BankBounceAi.class.getDeclaredField("runtimeDecider");
      holder.setAccessible(true);
      Object runtime = holder.get(null);
      Field decider = runtime.getClass().getDeclaredField("decider");
      decider.setAccessible(true);
      return decider.get(runtime);
    }

    @Test
    @DisplayName("without a configured key nothing is sent")
    void withoutKeyNothingIsSent() throws Exception {
      try (MockedStatic<LlmConfig> llm = mockStatic(LlmConfig.class);
          MockedStatic<UsageEventRecorder> usage = mockStatic(UsageEventRecorder.class)) {
        llm.when(LlmConfig::fromRuntime).thenReturn(
            new LlmConfig("http://127.0.0.1:" + server.getAddress().getPort(), null, null, 1000));

        assertFalse(annotated(plan(review("P1"))).has(BankBounceAi.KEY_ASSESSMENTS));

        assertEquals(0, requests.get());
        usage.verifyNoInteractions();
      }
    }
  }

  // ---------------------------------------------------------------------------
  // helpers
  // ---------------------------------------------------------------------------

  private static void await(CountDownLatch gate) {
    try {
      if (!gate.await(15, TimeUnit.SECONDS)) {
        throw new AssertionError("the gate never opened");
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }

  private static void waitUntil(java.util.function.BooleanSupplier condition) throws Exception {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
    while (!condition.getAsBoolean()) {
      if (System.nanoTime() > deadline) {
        throw new AssertionError("condition not reached");
      }
      Thread.sleep(5);
    }
  }

  private static Set<String> keys(JSONObject json) {
    Set<String> out = new TreeSet<>();
    for (Iterator<?> it = json.keys(); it.hasNext();) {
      out.add((String) it.next());
    }
    return out;
  }

  private static MockedStatic<OBContext> mockTenant(AtomicReference<String> tenant) {
    MockedStatic<OBContext> ctxStatic = mockStatic(OBContext.class);
    OBContext ctx = mock(OBContext.class);
    Client client = mock(Client.class);
    when(client.getId()).thenAnswer(inv -> tenant.get());
    when(ctx.getCurrentClient()).thenReturn(client);
    ctxStatic.when(OBContext::getOBContext).thenReturn(ctx);
    return ctxStatic;
  }
}
