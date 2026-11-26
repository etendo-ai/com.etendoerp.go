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

import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.core.SessionHandler;
import org.openbravo.dal.service.OBDal;

import com.etendoerp.go.schemaforge.email.DocumentDownloadTokenService;

/**
 * The customer portal sessions behind the "sessions with a value interaction" KPI (ETP-6209,
 * design {@code ETP-6209-kpi-design.md}).
 *
 * <h2>Session model</h2>
 * <ul>
 *   <li><b>One row per session in {@code ETGO_PORTAL_SESSION}</b>, so two Tomcats overlapping
 *       during a deploy never count one session twice, every "once per session" is an atomic
 *       {@code UPDATE … WHERE x IS NULL}, and the KPI can be recomputed by SQL.</li>
 *   <li><b>Started by the first token-validated request of a tab</b>: a request with no
 *       {@code X-Portal-Session}, or one that is unknown, of another link, idle for
 *       {@value #IDLE_MINUTES} minutes or older than {@value #CAP_HOURS} hours, mints a new
 *       session. The id goes back in the {@code X-Portal-Session} response header (and in
 *       {@code /me}'s {@code sessionId}). It is a random 128-bit value that grants nothing — the
 *       portal token stays the only credential — and it never travels in a URL.</li>
 *   <li><b>Universe</b>: a session entered from a {@code SEND} or {@code RENEWAL} link that has an
 *       origin document. Decision landings and contact-shared links are recorded, out of it.</li>
 * </ul>
 *
 * <h2>Value interactions (rule v1)</h2>
 * (a) a document other than the origin opened from Home or the search, (b) a related document
 * other than the origin, (c) a quotation decision request sent in the session. A list open is
 * recorded ({@code backend_portal_document_opened}) but never counted; returning to the origin
 * document never counts. The first value interaction of a session engages it
 * ({@code backend_portal_session_engaged}).
 *
 * <p><b>Never in the way.</b> Every method answers and logs instead of throwing; a tracking failure
 * leaves the request exactly as it would have been. Events leave through
 * {@link PortalKpiTelemetry} after the stamp's own commit, with the tenant as {@code account_id}
 * and the session only as {@code portal_session}, a keyed pseudonym of its id.</p>
 */
public class PortalSessionTracker {

  private static final Logger log = LogManager.getLogger(PortalSessionTracker.class);

  /** Request and response header carrying the session id. */
  public static final String HEADER_SESSION = "X-Portal-Session";
  /** A session ends after this long without a request. */
  static final long IDLE_MINUTES = 30;
  /** A session never lasts longer than this, however active. */
  static final long CAP_HOURS = 12;
  /** {@code LAST_SEEN} is written at most this often. */
  private static final Duration LAST_SEEN_THROTTLE = Duration.ofMinutes(1);
  /** Signing purpose of the {@code portal_session} pseudonym: valid for nothing else. */
  static final String SESSION_PURPOSE = "kpi-portal-session:v1";
  /** The rule version the engaged event carries, so a later rule never mixes with this one. */
  static final String RULE = "v1";

  private static final String OPEN_HOME = "home";
  private static final String OPEN_SEARCH = "search";
  private static final String OPEN_RELATED = "related";
  /** Document-open origins the page may send ({@code ?origin=}); anything else is unknown. */
  public static final List<String> OPEN_ORIGINS = List.of("link", OPEN_HOME, OPEN_SEARCH, "list",
      OPEN_RELATED, "refresh", "action");
  /** Origins that are a user opening a document (refresh and action are re-reads). */
  private static final Set<String> COUNTED_OPENS = Set.of("link", OPEN_HOME, OPEN_SEARCH, "list",
      OPEN_RELATED);
  static final String ORIGIN_UNKNOWN = "unknown";
  /** {@code ?purpose=} of a {@code /pdf} request: the detail's preview, or a real download. */
  public static final String PURPOSE_PREVIEW = "preview";
  public static final String PURPOSE_DOWNLOAD = "download";
  /** {@code ?origin=} of a {@code /pdf} download. */
  static final Set<String> PDF_ORIGINS = Set.of("detail", "list");
  /** The etendo.ai link the customer clicked, and where it was. */
  public static final Set<String> CLICK_REFERENCES = Set.of("learnMore", "startFree");
  public static final Set<String> CLICK_LOCATIONS = Set.of("header", "mobile", "rail");

  static final String INTERACTION_HOME = "home_document";
  static final String INTERACTION_RELATED = "related_document";
  static final String INTERACTION_DECISION = "quotation_decision";

  private static final Pattern SESSION_ID = Pattern.compile("[0-9A-F]{32}");
  private static final SecureRandom RANDOM = new SecureRandom();
  private static final int SEEN_CAPACITY = 20_000;
  /** (session, document) pairs already reported, this node only: a reload must not repeat them. */
  private static final Set<String> SEEN = Collections.synchronizedSet(
      Collections.newSetFromMap(new LinkedHashMap<String, Boolean>(1024, 0.75f, false) {
        private static final long serialVersionUID = 1L;

        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Boolean> eldest) {
          return size() > SEEN_CAPACITY;
        }
      }));

  /** The one-shot stamps of a session row: only these column names ever reach the SQL. */
  enum Stamp {
    HOME_DOC("home_doc_at"), RELATED_DOC("related_doc_at"), DECISION("decision_at"),
    ENGAGED("engaged_at"), DOWNLOADED("downloaded_at"), ETENDO_CLICK("etendo_click_at"),
    LEARN_MORE_CLICK("learn_more_click_at"), START_FREE_CLICK("start_free_click_at");

    private final String column;

    Stamp(String column) {
      this.column = column;
    }
  }

  /** The session a request runs in: who it is for and what it was entered with. */
  public static final class Visit {
    private final String id;
    private final boolean minted;
    private final String clientId;
    private final boolean inUniverse;
    private final PortalDocumentKind originKind;
    private final String originRecordId;
    private final String entryDocType;

    Visit(String id, boolean minted, String clientId, boolean inUniverse,
        PortalDocumentKind originKind, String originRecordId, String entryDocType) {
      this.id = id;
      this.minted = minted;
      this.clientId = clientId;
      this.inUniverse = inUniverse;
      this.originKind = originKind;
      this.originRecordId = originRecordId;
      this.entryDocType = entryDocType;
    }

    /** @return the session id, for the {@code X-Portal-Session} response header */
    public String getId() {
      return id;
    }

    /** @return whether this request started the session */
    public boolean isMinted() {
      return minted;
    }

    String getClientId() {
      return clientId;
    }

    boolean isInUniverse() {
      return inUniverse;
    }

    String getEntryDocType() {
      return entryDocType;
    }

    /** Whether a document is the one the session's link was emailed for: decided here only. */
    boolean isOriginDocument(PortalDocumentKind kind, String recordId) {
      return kind != null && kind == originKind && recordId != null
          && recordId.equals(originRecordId);
    }
  }

  private final Clock clock;

  /** The tracker on the system clock. */
  public PortalSessionTracker() {
    this(Clock.systemUTC());
  }

  PortalSessionTracker(Clock clock) {
    this.clock = clock;
  }

  /**
   * Resumes the request's session, or mints one, for a valid link. A minted session emits
   * {@code backend_portal_opened} once. Never throws.
   *
   * @param presentedId the {@code X-Portal-Session} the page sent, or {@code null}
   * @param link the validated link
   * @return the session, or {@code null} when it could not be tracked
   */
  public Visit track(String presentedId, PortalLink link) {
    if (link == null) {
      return null;
    }
    OBContext.setAdminMode(true);
    try {
      Instant now = clock.instant();
      String id = StringUtils.upperCase(StringUtils.trimToNull(presentedId));
      if (id != null && SESSION_ID.matcher(id).matches() && resume(id, link, now)) {
        return visitOf(id, false, link);
      }
      String minted = mint(link, now);
      Visit visit = visitOf(minted, true, link);
      PortalSessionEvents.opened(link, visit);
      return visit;
    } catch (RuntimeException e) {
      log.debug("Portal session could not be tracked: {}", e.getMessage());
      discard();
      return null;
    } finally {
      OBContext.restorePreviousMode();
    }
  }

  /**
   * Resumes the presented session of this link, never minting one: for an event that must not
   * start a session (the etendo.ai click). Never throws.
   *
   * @param presentedId the {@code X-Portal-Session} the page sent, or {@code null}
   * @param link the validated link
   * @return the live session, or {@code null} when there is none
   */
  public Visit resume(String presentedId, PortalLink link) {
    if (link == null) {
      return null;
    }
    OBContext.setAdminMode(true);
    try {
      String id = StringUtils.upperCase(StringUtils.trimToNull(presentedId));
      return id != null && SESSION_ID.matcher(id).matches() && resume(id, link, clock.instant())
          ? visitOf(id, false, link) : null;
    } catch (RuntimeException e) {
      log.debug("Portal session could not be resumed: {}", e.getMessage());
      discard();
      return null;
    } finally {
      OBContext.restorePreviousMode();
    }
  }

  /**
   * A document's detail was answered (200). Reports a user open once per session and document,
   * and stamps the value interaction its origin earns. Never throws.
   *
   * @param visit the session, or {@code null}
   * @param kind the document's kind
   * @param recordId the document
   * @param originParam the page's {@code ?origin=}
   */
  public void documentOpened(Visit visit, PortalDocumentKind kind, String recordId,
      String originParam) {
    if (visit == null || kind == null || recordId == null) {
      return;
    }
    try {
      String origin = openOrigin(originParam);
      if (!COUNTED_OPENS.contains(origin)) {
        return;
      }
      boolean originDoc = visit.isOriginDocument(kind, recordId);
      String docType = docType(kind, recordId);
      if (SEEN.add("open:" + visit.getId() + ":" + recordId)) {
        PortalSessionEvents.documentOpened(visit, origin, docType, originDoc);
      }
      if (originDoc) {
        return;
      }
      if (OPEN_HOME.equals(origin) || OPEN_SEARCH.equals(origin)) {
        interaction(visit, Stamp.HOME_DOC, INTERACTION_HOME, docType);
      } else if (OPEN_RELATED.equals(origin)) {
        interaction(visit, Stamp.RELATED_DOC, INTERACTION_RELATED, docType);
      }
    } catch (RuntimeException e) {
      log.debug("Portal document open could not be tracked: {}", e.getMessage());
      discard();
    }
  }

  /**
   * A quotation decision request's confirmation email left (the request is the session's; its
   * confirmation, later and from the email, is not). Never throws.
   *
   * @param visit the session, or {@code null}
   */
  public void decisionRequested(Visit visit) {
    if (visit == null) {
      return;
    }
    try {
      interaction(visit, Stamp.DECISION, INTERACTION_DECISION,
          PortalKpiTelemetry.typeOf(PortalDocumentKind.QUOTATION));
    } catch (RuntimeException e) {
      log.debug("Portal decision request could not be tracked: {}", e.getMessage());
      discard();
    }
  }

  /**
   * A document's PDF was served. Only a download on purpose is tracked — the detail's automatic
   * preview is not — and never as a value interaction. Never throws.
   *
   * @param visit the session, or {@code null}
   * @param kind the document's kind
   * @param recordId the document
   * @param purpose the normalized purpose ({@link #pdfPurpose})
   * @param originParam the page's {@code ?origin=} of the download ({@code detail|list})
   */
  public void pdfServed(Visit visit, PortalDocumentKind kind, String recordId, String purpose,
      String originParam) {
    if (visit == null || kind == null || recordId == null || !PURPOSE_DOWNLOAD.equals(purpose)) {
      return;
    }
    try {
      stamp(visit.getId(), Stamp.DOWNLOADED);
      if (SEEN.add("pdf:" + visit.getId() + ":" + recordId)) {
        String origin = originParam != null && PDF_ORIGINS.contains(originParam) ? originParam
            : ORIGIN_UNKNOWN;
        PortalSessionEvents.pdfDownloaded(visit, docType(kind, recordId),
            visit.isOriginDocument(kind, recordId), origin);
      }
    } catch (RuntimeException e) {
      log.debug("Portal PDF download could not be tracked: {}", e.getMessage());
      discard();
    }
  }

  /**
   * The customer clicked an etendo.ai link in the portal: once per session and link reference,
   * plus the session's first click of any. The caller validated both values. Never throws.
   *
   * @param visit the session, or {@code null}
   * @param reference one of {@link #CLICK_REFERENCES}
   * @param location one of {@link #CLICK_LOCATIONS}
   */
  public void etendoClicked(Visit visit, String reference, String location) {
    if (visit == null || !isClick(reference, location)) {
      return;
    }
    try {
      boolean first = stamp(visit.getId(), "learnMore".equals(reference)
          ? Stamp.LEARN_MORE_CLICK : Stamp.START_FREE_CLICK);
      stamp(visit.getId(), Stamp.ETENDO_CLICK);
      if (first) {
        PortalSessionEvents.etendoClicked(visit, reference, location);
      }
    } catch (RuntimeException e) {
      log.debug("Portal etendo.ai click could not be tracked: {}", e.getMessage());
      discard();
    }
  }

  /**
   * Whether a click names a known etendo.ai link and placement ({@code Set.of} rejects a
   * {@code null} lookup, so both are checked first).
   *
   * @param reference the link
   * @param location where it was
   * @return {@code true} when both are in their vocabulary
   */
  public static boolean isClick(String reference, String location) {
    return reference != null && location != null && CLICK_REFERENCES.contains(reference)
        && CLICK_LOCATIONS.contains(location);
  }

  /**
   * The page's {@code ?origin=} of a document open, from its fixed vocabulary.
   *
   * @param raw the parameter
   * @return one of {@link #OPEN_ORIGINS}, or {@value #ORIGIN_UNKNOWN}
   */
  static String openOrigin(String raw) {
    String origin = StringUtils.lowerCase(StringUtils.trimToNull(raw), Locale.ROOT);
    return origin != null && OPEN_ORIGINS.contains(origin) ? origin : ORIGIN_UNKNOWN;
  }

  /**
   * The page's {@code ?purpose=} of a {@code /pdf} request: {@code download} only when said so,
   * else {@code preview} (an older page sends none).
   *
   * @param raw the parameter
   * @return {@value #PURPOSE_DOWNLOAD} or {@value #PURPOSE_PREVIEW}
   */
  public static String pdfPurpose(String raw) {
    return PURPOSE_DOWNLOAD.equalsIgnoreCase(StringUtils.trimToEmpty(raw)) ? PURPOSE_DOWNLOAD
        : PURPOSE_PREVIEW;
  }

  /** Stamps the interaction once per session and, the session's first, engages it. */
  private void interaction(Visit visit, Stamp stamp, String interaction, String docType) {
    if (!stamp(visit.getId(), stamp)) {
      return;
    }
    PortalSessionEvents.valueInteraction(visit, interaction, docType);
    if (stamp(visit.getId(), Stamp.ENGAGED)) {
      PortalSessionEvents.sessionEngaged(visit, interaction, RULE);
    }
  }

  // ─── Rows ────────────────────────────────────────────────────────────────

  /** Resumes a live session of this link; slides its idle window at most once a minute. */
  private boolean resume(String id, PortalLink link, Instant now) {
    List<?> rows = OBDal.getInstance().getSession().createNativeQuery(
        "SELECT etgo_portal_session_id FROM etgo_portal_session"
            + " WHERE etgo_portal_session_id = :id AND etgo_portal_link_id = :link"
            + " AND isactive = 'Y' AND last_seen > :idle AND created > :cap")
        .setParameter("id", id).setParameter("link", link.getId())
        .setParameter("idle", Timestamp.from(now.minus(Duration.ofMinutes(IDLE_MINUTES))))
        .setParameter("cap", Timestamp.from(now.minus(Duration.ofHours(CAP_HOURS))))
        .list();
    if (rows.isEmpty()) {
      return false;
    }
    int touched = OBDal.getInstance().getSession().createNativeQuery(
        "UPDATE etgo_portal_session SET last_seen = :now, updated = :now"
            + " WHERE etgo_portal_session_id = :id AND last_seen < :throttle")
        .setParameter("now", Timestamp.from(now)).setParameter("id", id)
        .setParameter("throttle", Timestamp.from(now.minus(LAST_SEEN_THROTTLE)))
        .executeUpdate();
    if (touched > 0) {
      SessionHandler.getInstance().commitAndStart();
    }
    return true;
  }

  /** Inserts a new session of this link, committed at once (its events follow). */
  private String mint(PortalLink link, Instant now) {
    String id = newId();
    Timestamp at = Timestamp.from(now);
    OBDal.getInstance().getSession().createNativeQuery("INSERT INTO etgo_portal_session"
        + " (etgo_portal_session_id, ad_client_id, ad_org_id, isactive, created, createdby,"
        + " updated, updatedby, etgo_portal_link_id, root_link_id, entry_origin, origin_kind,"
        + " origin_record_id, in_universe, last_seen) VALUES (:id, :client, :org, 'Y', :at, '0',"
        + " :at, '0', :link, :root, :origin, :kind, :record, :universe, :at)")
        .setParameter("id", id)
        .setParameter("client", link.getClientId())
        .setParameter("org", StringUtils.defaultIfBlank(link.getOrganizationId(), "0"))
        .setParameter("at", at)
        .setParameter("link", link.getId())
        .setParameter("root", StringUtils.defaultIfBlank(link.getRootLinkId(), link.getId()))
        .setParameter("origin", link.getOrigin())
        .setParameter("kind", link.getDocumentKind() == null ? null
            : link.getDocumentKind().name())
        .setParameter("record", link.getRecordId())
        .setParameter("universe", inUniverse(link) ? "Y" : "N")
        .executeUpdate();
    SessionHandler.getInstance().commitAndStart();
    return id;
  }

  /**
   * Sets a one-shot stamp when it is still empty, committed at once. Concurrent requests are
   * serialized by the row lock, so exactly one of them changes the row.
   *
   * @return whether this call set it
   */
  private static boolean stamp(String sessionId, Stamp stamp) {
    int updated = OBDal.getInstance().getSession().createNativeQuery(
        "UPDATE etgo_portal_session SET " + stamp.column + " = now(), updated = now()"
            + " WHERE etgo_portal_session_id = :id AND " + stamp.column + " IS NULL")
        .setParameter("id", sessionId).executeUpdate();
    SessionHandler.getInstance().commitAndStart();
    return updated > 0;
  }

  /** In the KPI's universe: entered from an email (send or renewal) with an origin document. */
  static boolean inUniverse(PortalLink link) {
    return (PortalLink.ORIGIN_SEND.equals(link.getOrigin())
        || PortalLink.ORIGIN_RENEWAL.equals(link.getOrigin()))
        && StringUtils.isNotBlank(link.getRecordId()) && link.getDocumentKind() != null;
  }

  private static Visit visitOf(String id, boolean minted, PortalLink link) {
    PortalDocumentKind kind = link.getDocumentKind();
    return new Visit(id, minted, link.getClientId(), inUniverse(link), kind, link.getRecordId(),
        kind == null ? null : docType(kind, link.getRecordId()));
  }

  /** {@code quotation}, {@code order}, {@code shipment}, {@code invoice} or {@code credit_note}. */
  private static String docType(PortalDocumentKind kind, String recordId) {
    return kind.copyKey(recordId).toLowerCase(Locale.ROOT);
  }

  /**
   * The {@code portal_session} pseudonym: a keyed HMAC of the session id, so events of one session
   * group together and nothing else can be learned from it.
   *
   * @param sessionId the session id
   * @return the pseudonym, or {@code null} when no signing secret is configured
   */
  static String pseudonym(String sessionId) {
    return DocumentDownloadTokenService.signDetached(SESSION_PURPOSE, sessionId).orElse(null);
  }

  private static String newId() {
    byte[] bytes = new byte[16];
    RANDOM.nextBytes(bytes);
    StringBuilder hex = new StringBuilder(32);
    for (byte b : bytes) {
      hex.append(String.format("%02X", b));
    }
    return hex.toString();
  }

  private static void discard() {
    try {
      OBDal.getInstance().rollbackAndClose();
    } catch (RuntimeException e) {
      log.debug("Portal session tracking: rollback failed: {}", e.getMessage());
    }
  }
}
