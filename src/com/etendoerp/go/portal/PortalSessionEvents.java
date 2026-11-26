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

import java.util.LinkedHashMap;
import java.util.Map;

import com.etendoerp.go.schemaforge.telemetry.NeoTelemetryEvents;

/**
 * The portal session KPI events (ETP-6209), built from a {@link PortalSessionTracker.Visit} and
 * emitted through {@link PortalKpiTelemetry} (after the stamp's own commit, never throwing).
 *
 * <p>Every event carries {@code account_id} (the tenant), {@code portal_session} (a keyed
 * pseudonym of the session id) and {@code in_universe}; the rest are fixed vocabularies. Never a
 * record id, an address, a name or the session id itself.</p>
 */
final class PortalSessionEvents {

  private static final String KEY_DOC_TYPE = "doc_type";
  private static final String KEY_ORIGIN = "origin";

  private PortalSessionEvents() {
  }

  /** {@code backend_portal_opened}: the session started. */
  static void opened(PortalLink link, PortalSessionTracker.Visit visit) {
    PortalKpiTelemetry.portalOpened(link, PortalSessionTracker.pseudonym(visit.getId()),
        visit.isInUniverse());
  }

  /** {@code backend_portal_document_opened}: a user open, once per session and document. */
  static void documentOpened(PortalSessionTracker.Visit visit, String origin, String docType,
      boolean originDocument) {
    Map<String, Object> properties = base(visit);
    properties.put(KEY_ORIGIN, origin);
    properties.put(KEY_DOC_TYPE, docType);
    properties.put("is_origin_doc", originDocument);
    emit(NeoTelemetryEvents.BACKEND_PORTAL_DOCUMENT_OPENED, visit, properties);
  }

  /** {@code backend_portal_value_interaction}: the first of its type in the session. */
  static void valueInteraction(PortalSessionTracker.Visit visit, String interaction,
      String docType) {
    Map<String, Object> properties = base(visit);
    properties.put("interaction", interaction);
    properties.put(KEY_DOC_TYPE, docType);
    emit(NeoTelemetryEvents.BACKEND_PORTAL_VALUE_INTERACTION, visit, properties);
  }

  /** {@code backend_portal_session_engaged}: the session's first value interaction. */
  static void sessionEngaged(PortalSessionTracker.Visit visit, String firstInteraction,
      String rule) {
    Map<String, Object> properties = base(visit);
    properties.put("first_interaction", firstInteraction);
    if (visit.getEntryDocType() != null) {
      properties.put("entry_doc_type", visit.getEntryDocType());
    }
    properties.put("rule", rule);
    emit(NeoTelemetryEvents.BACKEND_PORTAL_SESSION_ENGAGED, visit, properties);
  }

  /** {@code backend_portal_pdf_downloaded}: a download on purpose, once per session and document. */
  static void pdfDownloaded(PortalSessionTracker.Visit visit, String docType,
      boolean originDocument, String origin) {
    Map<String, Object> properties = base(visit);
    properties.put(KEY_DOC_TYPE, docType);
    properties.put("is_origin_doc", originDocument);
    properties.put(KEY_ORIGIN, origin);
    emit(NeoTelemetryEvents.BACKEND_PORTAL_PDF_DOWNLOADED, visit, properties);
  }

  /** {@code backend_portal_etendo_click}: once per session and etendo.ai link. */
  static void etendoClicked(PortalSessionTracker.Visit visit, String reference,
      String location) {
    Map<String, Object> properties = base(visit);
    properties.put("reference", reference);
    properties.put("location", location);
    emit(NeoTelemetryEvents.BACKEND_PORTAL_ETENDO_CLICK, visit, properties);
  }

  private static Map<String, Object> base(PortalSessionTracker.Visit visit) {
    Map<String, Object> properties = new LinkedHashMap<>();
    String pseudonym = PortalSessionTracker.pseudonym(visit.getId());
    if (pseudonym != null) {
      properties.put(PortalKpiTelemetry.KEY_PORTAL_SESSION, pseudonym);
    }
    properties.put(PortalKpiTelemetry.KEY_IN_UNIVERSE, visit.isInUniverse());
    return properties;
  }

  private static void emit(String event, PortalSessionTracker.Visit visit,
      Map<String, Object> properties) {
    PortalKpiTelemetry.emitForTenant(event, properties, visit.getClientId());
  }
}
