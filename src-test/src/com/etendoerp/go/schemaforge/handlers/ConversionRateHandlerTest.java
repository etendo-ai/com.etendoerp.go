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
package com.etendoerp.go.schemaforge.handlers;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDateTime;
import java.time.ZoneId;

import org.codehaus.jettison.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.ad.system.Client;
import org.openbravo.model.common.currency.ConversionRate;

import com.etendoerp.go.schemaforge.CurrencyConversionV2Flag;
import com.etendoerp.go.schemaforge.NeoContext;
import com.etendoerp.go.schemaforge.NeoEndpointType;
import com.etendoerp.go.schemaforge.NeoResponse;

/**
 * Unit tests for {@link ConversionRateHandler#refusalForReadOnlyTarget}: the delete of a row the
 * tenant can read but not delete — a System rate — refused by the CRUD guard before the
 * customization runs (ETP-6365). A downloaded one is worded as the synced lock (409
 * {@code ETGO_ConvRateSyncedLocked}); any other keeps the guard's 404 ({@code null}). The store is
 * a mock; OBContext/OBDal are mocked statically so the message text falls back to the catalogue.
 *
 * <p>Also the {@code currency-conversion-v2} gate (seam {@code featureEnabled()}): off, the write
 * actions, their preview and a delete answer 403 {@code feature_disabled} before the store is read
 * and the MCP only sees the read actions; reads are never gated. The flag's own per-account
 * evaluation is covered by {@code AccountTargetedFlagTest}.
 *
 * <p>Also the overlap refusal (QA CP-13): its instants travel structured, with the server zone, and
 * its text names that zone. And the agent hint of the currency refusals: their text names the
 * window's buttons, the hint names the action to call.
 *
 * @covers com.etendoerp.go.schemaforge.handlers.ConversionRateHandler
 * @covers com.etendoerp.go.schemaforge.handlers.ConversionRateRefusal
 */
public class ConversionRateHandlerTest {

  private static final String ID = "rate-1";

  private MockedStatic<OBContext> obContext;
  private MockedStatic<OBDal> obDal;
  private ConversionRateStore store;
  private ConversionRateHandler handler;

  @Before
  public void setUp() {
    obContext = Mockito.mockStatic(OBContext.class);
    obDal = Mockito.mockStatic(OBDal.class);
    store = mock(ConversionRateStore.class);
    handler = spy(new ConversionRateHandler());
    doReturn(store).when(handler).store();
    doReturn(true).when(handler).featureEnabled();
  }

  @After
  public void tearDown() {
    obDal.close();
    obContext.close();
  }

  private static NeoContext delete(String recordId) {
    return NeoContext.builder().httpMethod("DELETE").recordId(recordId).build();
  }

  private ConversionRate systemRow(boolean synced) {
    Client system = mock(Client.class);
    when(system.getId()).thenReturn("0");
    ConversionRate row = mock(ConversionRate.class);
    when(row.getClient()).thenReturn(system);
    when(store.readableRate(ID)).thenReturn(row);
    when(store.isSynced(row)).thenReturn(synced);
    when(store.isOwn(row)).thenReturn(false);
    return row;
  }

  @Test
  public void aDownloadedSystemRateIsAnsweredWithTheSyncedLock() throws Exception {
    systemRow(true);

    NeoResponse refusal = handler.refusalForReadOnlyTarget(delete(ID));

    assertEquals(409, refusal.getHttpStatus());
    assertEquals(ConversionRateRefusal.SYNCED_LOCKED, refusal.getBody().getJSONObject("error")
        .getJSONArray("messageKeys").getString(0));
    // Admin mode is entered and left; resolving the refusal's message enters it once more.
    obContext.verify(() -> OBContext.setAdminMode(true), atLeastOnce());
    obContext.verify(OBContext::restorePreviousMode, atLeastOnce());
  }

  @Test
  public void aManualSystemRateKeepsTheGuardsNotFound() {
    systemRow(false);

    assertNull(handler.refusalForReadOnlyTarget(delete(ID)));
    obContext.verify(OBContext::restorePreviousMode);
  }

  @Test
  public void noRecordIdAsksNothing() {
    assertNull(handler.refusalForReadOnlyTarget(delete("  ")));
    assertNull(handler.refusalForReadOnlyTarget(null));
    verify(store, never()).readableRate(Mockito.anyString());
  }

  // ── currency-conversion-v2 gate (ETP-6365) ─────────────────────────────────

  private static NeoContext action(String name) {
    return NeoContext.builder().endpointType(NeoEndpointType.ACTION).fieldName(name)
        .httpMethod("POST").recordId("new").build();
  }

  private static void assertFeatureDisabled(NeoResponse response) throws Exception {
    assertEquals(403, response.getHttpStatus());
    assertEquals(CurrencyConversionV2Flag.CODE_FEATURE_DISABLED,
        response.getBody().getJSONObject("error").getString("code"));
    assertEquals(CurrencyConversionV2Flag.MSG_DISABLED,
        response.getBody().getJSONObject("error").getJSONArray("messageKeys").getString(0));
  }

  @Test
  public void flagOffRefusesEveryWriteBeforeReadingTheStore() throws Exception {
    doReturn(false).when(handler).featureEnabled();

    for (String name : new String[] { ConversionRateWriteActions.CREATE_CURRENCY,
        ConversionRateWriteActions.CREATE_RATE, ConversionRateWriteActions.UPDATE_RATE,
        ConversionRateActions.PREVIEW_MANUAL_RATE }) {
      assertFeatureDisabled(handler.handle(action(name)));
    }
    assertFeatureDisabled(handler.handle(delete(ID)));
    assertFeatureDisabled(handler.refusalForReadOnlyTarget(delete(ID)));
    verify(store, never()).readableRate(Mockito.anyString());
  }

  @Test
  public void flagOffKeepsTheReadsAndDeclaresOnlyThemToTheMcp() {
    doReturn(false).when(handler).featureEnabled();

    assertNull(handler.handle(NeoContext.builder().httpMethod("GET").build()));
    assertTrue(handler.actionContracts().containsKey(ConversionRateActions.SERVER_CLOCK));
    assertTrue(handler.actionContracts().containsKey(ConversionRateActions.CURRENT_RATES));
    assertFalse(handler.actionContracts().containsKey(ConversionRateWriteActions.CREATE_RATE));
    assertFalse(handler.actionContracts().containsKey(ConversionRateWriteActions.UPDATE_RATE));
    assertFalse(handler.actionContracts().containsKey(ConversionRateActions.PREVIEW_MANUAL_RATE));
  }

  @Test
  public void flagOnDeclaresEveryAction() {
    assertEquals(ConversionRateActions.CONTRACTS, handler.actionContracts());
  }

  // ── overlap refusal: instants structured, zone named (QA CP-13) ────────────

  @Test
  public void anOverlapCarriesItsInstantsAndTheServerZone() throws Exception {
    ConversionRateRules.Range closed = new ConversionRateRules.Range("f", "i",
        LocalDateTime.of(2026, 11, 26, 12, 0), LocalDateTime.of(2026, 11, 27, 8, 29), false);

    NeoResponse response = ConversionRateRefusal.overlapOf("EUR", "CHF", closed,
        LocalDateTime.of(2026, 11, 27, 8, 30), ZoneId.of("UTC")).toResponse();

    JSONObject error = response.getBody().getJSONObject("error");
    JSONObject details = error.getJSONObject("details");
    assertEquals(409, response.getHttpStatus());
    assertEquals("validFromDate", error.getString("field"));
    assertEquals("2026-11-26T12:00:00", details.getString("conflictFrom"));
    assertEquals("2026-11-27T08:29:00", details.getString("conflictTo"));
    assertFalse(details.getBoolean("conflictOpen"));
    assertEquals("2026-11-27T08:30:00", details.getString("suggestedFrom"));
    assertEquals("UTC", details.getString("timeZone"));
    assertTrue(error.getString("message"), error.getString("message").contains("(UTC)"));
  }

  @Test
  public void anOverlapWithAnOpenRangeHasNoEndAndKeepsTheFieldOnACopy() throws Exception {
    ConversionRateRules.Range open = new ConversionRateRules.Range("f", "i",
        LocalDateTime.of(2026, 11, 26, 12, 0), null, false);

    ConversionRateRefusal refusal = ConversionRateRefusal.overlapOf("EUR", "CHF", open,
        LocalDateTime.of(2026, 11, 26, 12, 0), ZoneId.of("America/Argentina/Cordoba"))
        .on("validFromDate");

    JSONObject details = refusal.detailsJson();
    assertTrue(details.isNull("conflictTo"));
    assertTrue(details.getBoolean("conflictOpen"));
    assertEquals("America/Argentina/Cordoba", details.getString("timeZone"));
    assertNull(ConversionRateRefusal.badRequest(ConversionRateRefusal.INVALID_RATE).detailsJson());
  }

  // ── agent hint: the action to call, not the window's button ────────────────

  @Test
  public void theCurrencyRefusalsHintTheActionToCall() throws Exception {
    Object[][] cases = {
        { ConversionRateRefusal.CURRENCY_HAS_RATES, ConversionRateWriteActions.CREATE_RATE },
        { ConversionRateRefusal.CURRENCY_NO_RATES, ConversionRateWriteActions.CREATE_CURRENCY },
    };
    for (Object[] c : cases) {
      ConversionRateRefusal refusal = ConversionRateRefusal.conflict((String) c[0], "CHF")
          .on("toCurrency");

      JSONObject error = refusal.toResponse().getBody().getJSONObject("error");
      String hint = error.getString("hint");
      assertTrue(hint, hint.contains((String) c[1]));
      assertTrue(hint, hint.contains("CHF"));
      assertEquals(c[0], error.getJSONArray("messageKeys").getString(0));
      assertEquals(hint, refusal.hint());
    }
  }

  @Test
  public void aRefusalThatNamesNoButtonHasNoHint() throws Exception {
    ConversionRateRefusal refusal =
        ConversionRateRefusal.badRequest(ConversionRateRefusal.INVALID_RATE);

    assertNull(refusal.hint());
    assertFalse(refusal.toResponse().getBody().getJSONObject("error").has("hint"));
  }
}
