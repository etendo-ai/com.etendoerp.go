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

package com.etendoerp.go.portal;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.hibernate.Session;
import org.hibernate.query.NativeQuery;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Answers;
import org.mockito.MockedStatic;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.core.SessionHandler;
import org.openbravo.dal.service.OBDal;

/**
 * The portal session KPIs (ETP-6209): the fixed vocabularies and universe rule as pure checks, and
 * the session rows and one-shot stamps against a recorded native-query session (every SQL the
 * tracker sends, its parameters and the rows each answers), with {@link PortalSessionEvents}
 * mocked so what is emitted is observable.
 *
 * @covers com.etendoerp.go.portal.PortalSessionTracker
 */
public class PortalSessionTrackerTest {

  private static final String RECORD = "INV-1";
  private static final Instant NOW = Instant.parse("2026-11-25T10:00:00Z");
  private static final String LINK_ID = "LINK-1";
  private static final String ORIGIN_ORDER = "O-1";

  private MockedStatic<OBDal> obDalStatic;
  private MockedStatic<OBContext> obContext;
  private MockedStatic<SessionHandler> handlerStatic;
  private MockedStatic<PortalSessionEvents> events;
  private OBDal obDal;
  private SessionHandler sessionHandler;
  private PortalSessionTracker tracker;

  /** Every native query sent, in order. */
  private final List<Recorded> queries = new ArrayList<>();
  /** Rows the resume SELECT answers. */
  private List<Object> selectRows = Collections.emptyList();
  /** Rows the {@code last_seen} UPDATE changes. */
  private int lastSeenRows = 1;
  /** Rows each stamp UPDATE changes, by column (1 when absent). */
  private final Map<String, Integer> stampRows = new HashMap<>();
  /** When set, every native query fails. */
  private boolean failing;

  @Before
  public void setUp() {
    obDal = mock(OBDal.class);
    sessionHandler = mock(SessionHandler.class);
    Session session = mock(Session.class);
    when(session.createNativeQuery(anyString())).thenAnswer(inv -> query(inv.getArgument(0)));
    when(obDal.getSession()).thenReturn(session);
    obDalStatic = mockStatic(OBDal.class);
    obDalStatic.when(OBDal::getInstance).thenReturn(obDal);
    handlerStatic = mockStatic(SessionHandler.class);
    handlerStatic.when(SessionHandler::getInstance).thenReturn(sessionHandler);
    obContext = mockStatic(OBContext.class);
    events = mockStatic(PortalSessionEvents.class);
    tracker = new PortalSessionTracker(Clock.fixed(NOW, ZoneOffset.UTC));
  }

  @After
  public void tearDown() {
    events.close();
    obContext.close();
    handlerStatic.close();
    obDalStatic.close();
  }

  // ── pure rules ──────────────────────────────────────────────────────────────

  @Test
  public void anOpenOriginOutsideTheVocabularyIsUnknown() {
    // Columns: raw ?origin=, normalized.
    String[][] cases = { { "home", "home" }, { " Related ", "related" }, { "LINK", "link" },
        { "refresh", "refresh" }, { "action", "action" }, { "email", "unknown" },
        { "", "unknown" }, { null, "unknown" } };
    for (String[] c : cases) {
      assertEquals(String.valueOf(c[0]), c[1], PortalSessionTracker.openOrigin(c[0]));
    }
  }

  @Test
  public void aPdfIsADownloadOnlyWhenThePageSaysSo() {
    assertEquals("download", PortalSessionTracker.pdfPurpose("download"));
    assertEquals("download", PortalSessionTracker.pdfPurpose(" DOWNLOAD "));
    for (String raw : new String[] { "preview", "", null, "save" }) {
      assertEquals(String.valueOf(raw), "preview", PortalSessionTracker.pdfPurpose(raw));
    }
  }

  @Test
  public void aClickNeedsAKnownLinkAndAKnownPlacement() {
    assertTrue(PortalSessionTracker.isClick("learnMore", "header"));
    assertTrue(PortalSessionTracker.isClick("startFree", "rail"));
    assertFalse(PortalSessionTracker.isClick("learnmore", "header"));
    assertFalse(PortalSessionTracker.isClick("startFree", "footer"));
    assertFalse("null never reaches Set.of", PortalSessionTracker.isClick(null, "mobile"));
    assertFalse(PortalSessionTracker.isClick("learnMore", null));
  }

  @Test
  public void onlyASendOrRenewalLinkWithAnOriginDocumentIsInTheUniverse() {
    assertTrue(PortalSessionTracker.inUniverse(link(PortalLink.ORIGIN_SEND, RECORD)));
    assertTrue(PortalSessionTracker.inUniverse(link(PortalLink.ORIGIN_RENEWAL, RECORD)));
    assertFalse(PortalSessionTracker.inUniverse(link(PortalLink.ORIGIN_DECISION, RECORD)));
    assertFalse(PortalSessionTracker.inUniverse(link(PortalLink.ORIGIN_SHARED, RECORD)));
    assertFalse(PortalSessionTracker.inUniverse(link(PortalLink.ORIGIN_SEND, null)));
  }

  @Test
  public void theOriginDocumentIsTheLinksOwnKindAndRecord() {
    PortalSessionTracker.Visit visit = new PortalSessionTracker.Visit("S", true, "C", true,
        PortalDocumentKind.INVOICE, RECORD, "invoice");

    assertTrue(visit.isOriginDocument(PortalDocumentKind.INVOICE, RECORD));
    assertFalse(visit.isOriginDocument(PortalDocumentKind.ORDER, RECORD));
    assertFalse(visit.isOriginDocument(PortalDocumentKind.INVOICE, "INV-2"));
    assertFalse(visit.isOriginDocument(null, RECORD));
  }

  // ── track / resume ──────────────────────────────────────────────────────────

  /**
   * A request mints a session unless it presents a well-formed id the SELECT finds live for this
   * link. Unknown, another link's, idle and over-cap ids are all "no row" for that SELECT; which
   * of them it excludes is pinned by its parameters in
   * {@link #theResumeQueryIsBoundToTheLinkTheIdleWindowAndTheCap}.
   */
  @Test
  public void trackMintsUnlessAWellFormedIdIsLiveForTheLink() {
    String live = sessionId();
    // Columns: presented X-Portal-Session, rows the SELECT answers, resumed (else minted).
    Object[][] cases = {
        { null, 1, false },
        { "  ", 1, false },
        { "not-a-session", 1, false },
        { live.substring(1), 1, false },
        { live, 0, false },
        { live, 1, true },
        { " " + live.toLowerCase() + " ", 1, true },
    };
    PortalLink link = link(PortalLink.ORIGIN_SEND, RECORD);
    for (Object[] c : cases) {
      reset();
      selectRows = (Integer) c[1] == 1 ? Collections.singletonList(live) : Collections.emptyList();
      boolean resumed = (Boolean) c[2];
      String label = Arrays.toString(c);

      PortalSessionTracker.Visit visit = tracker.track((String) c[0], link);

      assertEquals(label, !resumed, visit.isMinted());
      if (resumed) {
        assertEquals(label, live, visit.getId());
        assertNull(label, find("INSERT"));
        events.verify(() -> PortalSessionEvents.opened(any(), any()), never());
      } else {
        assertTrue(label, visit.getId().matches("[0-9A-F]{32}"));
        assertNotEquals(label, live, visit.getId());
        Recorded insert = find("INSERT");
        assertEquals(label, visit.getId(), insert.params.get("id"));
        assertEquals(label, "Y", insert.params.get("universe"));
        events.verify(() -> PortalSessionEvents.opened(link, visit));
      }
      // A malformed id never reaches the database.
      boolean wellFormed = c[0] != null && ((String) c[0]).trim().length() == 32;
      assertEquals(label, wellFormed, find("SELECT") != null);
    }
  }

  @Test
  public void theResumeQueryIsBoundToTheLinkTheIdleWindowAndTheCap() {
    selectRows = Collections.singletonList("x");

    tracker.track(sessionId(), link(PortalLink.ORIGIN_SEND, RECORD));

    Recorded select = find("SELECT");
    assertTrue(select.sql, select.sql.contains("etgo_portal_link_id = :link"));
    assertTrue(select.sql, select.sql.contains("last_seen > :idle"));
    assertTrue(select.sql, select.sql.contains("created > :cap"));
    assertEquals(LINK_ID, select.params.get("link"));
    assertEquals(Timestamp.from(NOW.minus(Duration.ofMinutes(30))), select.params.get("idle"));
    assertEquals(Timestamp.from(NOW.minus(Duration.ofHours(12))), select.params.get("cap"));
  }

  /** {@code last_seen} slides only when it is a minute old, and only a real change commits. */
  @Test
  public void lastSeenIsWrittenAtMostOnceAMinute() {
    for (int touched : new int[] { 0, 1 }) {
      reset();
      selectRows = Collections.singletonList("x");
      lastSeenRows = touched;

      PortalSessionTracker.Visit visit = tracker.track(sessionId(),
          link(PortalLink.ORIGIN_SEND, RECORD));

      assertFalse(visit.isMinted());
      Recorded update = find("SET last_seen");
      assertTrue(update.sql, update.sql.contains("last_seen < :throttle"));
      assertEquals(Timestamp.from(NOW.minus(Duration.ofMinutes(1))),
          update.params.get("throttle"));
      verify(sessionHandler, times(touched)).commitAndStart();
    }
  }

  @Test
  public void resumeNeverMintsAndAnswersNullWithoutALiveSession() {
    PortalLink link = link(PortalLink.ORIGIN_SEND, RECORD);
    String live = sessionId();
    // Columns: presented X-Portal-Session, rows the SELECT answers, resumed.
    Object[][] cases = { { null, 1, false }, { "nope", 1, false }, { live, 0, false },
        { live, 1, true } };
    for (Object[] c : cases) {
      reset();
      selectRows = (Integer) c[1] == 1 ? Collections.singletonList(live) : Collections.emptyList();

      PortalSessionTracker.Visit visit = tracker.resume((String) c[0], link);

      String label = Arrays.toString(c);
      if ((Boolean) c[2]) {
        assertEquals(label, live, visit.getId());
        assertFalse(label, visit.isMinted());
      } else {
        assertNull(label, visit);
      }
      assertNull(label, find("INSERT"));
    }
    events.verify(() -> PortalSessionEvents.opened(any(), any()), never());
    assertNull(tracker.resume(live, null));
  }

  // ── stamps ──────────────────────────────────────────────────────────────────

  /**
   * Rule v1: a document other than the origin opened from home or the search, or as a related
   * document. The origin itself, a list, the link, a refresh, an action or an unknown origin never
   * stamp; every user open (not a re-read) is reported.
   */
  @Test
  public void documentOpenedStampsOnlyANonOriginDocumentFromHomeSearchOrRelated() {
    // Columns: ?origin=, kind, record, stamped column (null: none), reported as an open.
    Object[][] cases = {
        { "home", PortalDocumentKind.ORDER, "O-2", "home_doc_at", true },
        { "search", PortalDocumentKind.QUOTATION, ORIGIN_ORDER, "home_doc_at", true },
        { "related", PortalDocumentKind.ORDER, "O-2", "related_doc_at", true },
        { "home", PortalDocumentKind.ORDER, ORIGIN_ORDER, null, true },
        { "search", PortalDocumentKind.ORDER, ORIGIN_ORDER, null, true },
        { "related", PortalDocumentKind.ORDER, ORIGIN_ORDER, null, true },
        { "list", PortalDocumentKind.ORDER, "O-2", null, true },
        { "link", PortalDocumentKind.ORDER, "O-2", null, true },
        { "refresh", PortalDocumentKind.ORDER, "O-2", null, false },
        { "action", PortalDocumentKind.ORDER, "O-2", null, false },
        { "email", PortalDocumentKind.ORDER, "O-2", null, false },
        { null, PortalDocumentKind.ORDER, "O-2", null, false },
    };
    for (Object[] c : cases) {
      reset();
      // A fresh session per case: the once-per-session-and-document memory is node-wide.
      PortalSessionTracker.Visit visit = orderVisit();
      String label = Arrays.toString(c);

      tracker.documentOpened(visit, (PortalDocumentKind) c[1], (String) c[2], (String) c[0]);

      String column = (String) c[3];
      if (column == null) {
        assertTrue(label + " " + queries, queries.isEmpty());
      } else {
        assertTrue(label, find("SET " + column) != null);
      }
      events.verify(() -> PortalSessionEvents.documentOpened(eq(visit), anyString(), anyString(),
          anyBoolean()), times((Boolean) c[4] ? 1 : 0));
    }
  }

  /**
   * The session engages on its first value interaction only: a stamp that changed no row is no
   * interaction, and an engaged stamp that changed no row (already engaged) emits nothing.
   */
  @Test
  public void aSessionEngagesOnlyOnItsFirstValueInteraction() {
    // Columns: rows home_doc_at changes, rows engaged_at changes, value interaction emitted,
    // engaged_at attempted, engaged emitted.
    Object[][] cases = {
        { 1, 1, true, true, true },
        { 1, 0, true, true, false },
        { 0, 1, false, false, false },
    };
    for (Object[] c : cases) {
      reset();
      stampRows.put("home_doc_at", (Integer) c[0]);
      stampRows.put("engaged_at", (Integer) c[1]);
      PortalSessionTracker.Visit visit = orderVisit();
      String label = Arrays.toString(c);

      tracker.documentOpened(visit, PortalDocumentKind.ORDER, "O-2", "home");

      events.verify(() -> PortalSessionEvents.valueInteraction(visit, "home_document", "order"),
          times((Boolean) c[2] ? 1 : 0));
      assertEquals(label, c[3], find("SET engaged_at") != null);
      events.verify(() -> PortalSessionEvents.sessionEngaged(visit, "home_document", "v1"),
          times((Boolean) c[4] ? 1 : 0));
    }
  }

  @Test
  public void aDecisionRequestStampsTheDecisionOnce() {
    for (int changed : new int[] { 1, 0 }) {
      reset();
      stampRows.put("decision_at", changed);
      PortalSessionTracker.Visit visit = orderVisit();

      tracker.decisionRequested(visit);

      assertTrue(find("SET decision_at") != null);
      events.verify(() -> PortalSessionEvents.valueInteraction(visit, "quotation_decision",
          "quotation"), times(changed));
    }
    reset();
    tracker.decisionRequested(null);
    assertTrue(queries.isEmpty());
  }

  @Test
  public void aPreviewIsNotTrackedAndADownloadIsNeverAValueInteraction() {
    PortalSessionTracker.Visit preview = orderVisit();
    tracker.pdfServed(preview, PortalDocumentKind.ORDER, "O-2", "preview", "detail");
    assertTrue(queries.toString(), queries.isEmpty());
    events.verifyNoInteractions();

    // Columns: ?origin= of the download, origin the event carries.
    String[][] cases = { { "list", "list" }, { "detail", "detail" }, { "footer", "unknown" },
        { null, "unknown" } };
    for (String[] c : cases) {
      reset();
      PortalSessionTracker.Visit visit = orderVisit();

      tracker.pdfServed(visit, PortalDocumentKind.ORDER, ORIGIN_ORDER, "download", c[0]);

      assertTrue(find("SET downloaded_at") != null);
      assertNull(find("SET engaged_at"));
      events.verify(() -> PortalSessionEvents.pdfDownloaded(visit, "order", true, c[1]));
      events.verify(() -> PortalSessionEvents.valueInteraction(any(), any(), any()), never());
      events.verify(() -> PortalSessionEvents.sessionEngaged(any(), any(), any()), never());
    }
  }

  @Test
  public void anEtendoClickIsReportedOncePerSessionAndReference() {
    // Columns: reference, its stamp column, rows that stamp changes, reported.
    Object[][] cases = {
        { "learnMore", "learn_more_click_at", 1, true },
        { "learnMore", "learn_more_click_at", 0, false },
        { "startFree", "start_free_click_at", 1, true },
        { "startFree", "start_free_click_at", 0, false },
    };
    for (Object[] c : cases) {
      reset();
      stampRows.put((String) c[1], (Integer) c[2]);
      PortalSessionTracker.Visit visit = orderVisit();

      tracker.etendoClicked(visit, (String) c[0], "rail");

      assertTrue(Arrays.toString(c), find("SET " + c[1]) != null);
      assertTrue("the session's first click of any is stamped too",
          find("SET etendo_click_at") != null);
      events.verify(() -> PortalSessionEvents.etendoClicked(visit, (String) c[0], "rail"),
          times((Boolean) c[3] ? 1 : 0));
    }
    reset();
    tracker.etendoClicked(orderVisit(), "learnMore", "footer");
    assertTrue("an unknown placement touches nothing", queries.isEmpty());
  }

  /** A tracking failure never reaches the request: it answers, rolls back and emits nothing. */
  @Test
  public void aFailureInsideTheTrackerNeverThrowsAndRollsBack() {
    failing = true;
    PortalLink link = link(PortalLink.ORIGIN_SEND, RECORD);
    PortalSessionTracker.Visit visit = orderVisit();

    assertNull(tracker.track(null, link));
    assertNull(tracker.resume(sessionId(), link));
    tracker.documentOpened(visit, PortalDocumentKind.ORDER, "O-2", "home");
    tracker.decisionRequested(visit);
    tracker.pdfServed(visit, PortalDocumentKind.ORDER, "O-2", "download", "list");
    tracker.etendoClicked(visit, "learnMore", "header");

    verify(obDal, times(6)).rollbackAndClose();
    events.verify(() -> PortalSessionEvents.opened(any(), any()), never());
    events.verify(() -> PortalSessionEvents.valueInteraction(any(), any(), any()), never());
    events.verify(() -> PortalSessionEvents.etendoClicked(any(), any(), any()), never());
    obContext.verify(OBContext::restorePreviousMode, times(2));
  }

  // ── fixtures ────────────────────────────────────────────────────────────────

  private void reset() {
    queries.clear();
    selectRows = Collections.emptyList();
    lastSeenRows = 1;
    stampRows.clear();
    events.clearInvocations();
    clearInvocations(sessionHandler);
  }

  /** A session whose origin document is the order {@value #ORIGIN_ORDER}. */
  private static PortalSessionTracker.Visit orderVisit() {
    return new PortalSessionTracker.Visit(sessionId(), false, "CLIENT-1", true,
        PortalDocumentKind.ORDER, ORIGIN_ORDER, "order");
  }

  private static String sessionId() {
    return UUID.randomUUID().toString().replace("-", "").toUpperCase();
  }

  private Recorded find(String fragment) {
    return queries.stream().filter(q -> q.sql.contains(fragment)).findFirst().orElse(null);
  }

  /**
   * A native query that records its SQL and parameters and answers from the test's fields. Built
   * with a default answer, so nothing is stubbed while the tracker runs.
   */
  @SuppressWarnings("rawtypes")
  private NativeQuery query(String sql) {
    if (failing) {
      throw new IllegalStateException("database down");
    }
    Recorded recorded = new Recorded(sql);
    queries.add(recorded);
    return mock(NativeQuery.class, invocation -> {
      String method = invocation.getMethod().getName();
      if ("setParameter".equals(method) && invocation.getArguments().length == 2
          && invocation.getArgument(0) instanceof String) {
        recorded.params.put(invocation.getArgument(0), invocation.getArgument(1));
      }
      if ("list".equals(method)) {
        return selectRows;
      }
      if ("executeUpdate".equals(method)) {
        return rowsFor(sql);
      }
      if (invocation.getMethod().getReturnType().isInstance(invocation.getMock())) {
        return invocation.getMock();
      }
      return Answers.RETURNS_DEFAULTS.answer(invocation);
    });
  }

  private int rowsFor(String sql) {
    if (sql.startsWith("INSERT")) {
      return 1;
    }
    if (sql.contains("SET last_seen")) {
      return lastSeenRows;
    }
    String column = sql.substring(sql.indexOf("SET ") + 4, sql.indexOf(" = now()"));
    return stampRows.getOrDefault(column, 1);
  }

  private static final class Recorded {
    private final String sql;
    private final Map<String, Object> params = new HashMap<>();

    Recorded(String sql) {
      this.sql = sql;
    }

    @Override
    public String toString() {
      return sql;
    }
  }

  private static PortalLink link(String origin, String recordId) {
    return new PortalLink(LINK_ID, "CLIENT-1", "Acme", "ORG-1", "BP-1", "Customer SL",
        PortalDocumentKind.INVOICE, recordId, origin, null, LINK_ID, "sales-invoice-send",
        "a@customer.test", null, "es_ES", new Date(0L), new Date(1L), true, false);
  }
}
