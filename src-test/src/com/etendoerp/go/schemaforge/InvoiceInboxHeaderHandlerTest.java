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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.lang.reflect.Modifier;
import java.util.Set;

import javax.enterprise.context.ApplicationScoped;
import javax.inject.Named;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.openbravo.erpCommon.utility.OBMessageUtils;

/**
 * The feature-flag gate and the {@code discard}, {@code review}, {@code apply}, {@code mark-reviewed} and {@code confirm} dispatch of the invoice inbox header customization.
 *
 * @covers com.etendoerp.go.schemaforge.InvoiceInboxHeaderHandler
 */
class InvoiceInboxHeaderHandlerTest {

  private MockedStatic<InvoiceInboxFlag> flag;
  private MockedStatic<InvoiceInboxDiscard> discard;
  private MockedStatic<InvoiceInboxReview> review;
  private MockedStatic<InvoiceInboxApply> apply;
  private MockedStatic<InvoiceInboxMarkReviewed> markReviewed;
  private MockedStatic<InvoiceInboxConfirm> confirm;
  private MockedStatic<OBMessageUtils> messages;
  private final InvoiceInboxHeaderHandler handler = new InvoiceInboxHeaderHandler();

  @BeforeEach
  void openStaticMocks() {
    flag = mockStatic(InvoiceInboxFlag.class);
    discard = mockStatic(InvoiceInboxDiscard.class);
    review = mockStatic(InvoiceInboxReview.class);
    apply = mockStatic(InvoiceInboxApply.class);
    markReviewed = mockStatic(InvoiceInboxMarkReviewed.class);
    confirm = mockStatic(InvoiceInboxConfirm.class);
    // Unstubbed messageBD answers null: the English default is used.
    messages = mockStatic(OBMessageUtils.class);
  }

  @AfterEach
  void closeStaticMocks() {
    messages.close();
    confirm.close();
    markReviewed.close();
    apply.close();
    review.close();
    discard.close();
    flag.close();
  }

  private static NeoContext ctx(String method, NeoEndpointType type, String id, String field) {
    return NeoContext.builder().specName("invoice-inbox").entityName("header")
        .httpMethod(method).endpointType(type).recordId(id).fieldName(field).build();
  }

  private void flag(boolean on) {
    flag.when(InvoiceInboxFlag::isEnabled).thenReturn(on);
  }

  private static void assertDisabled(NeoResponse response) throws Exception {
    NeoResponse expected = InvoiceInboxUploadEndpoint.featureDisabled();
    assertNotNull(response);
    assertEquals(403, response.getHttpStatus());
    assertEquals("feature_disabled", response.getBody().getJSONObject("error").getString("code"));
    assertEquals(expected.getBody().toString(), response.getBody().toString());
  }

  // ---------------------------------------------------------------- flag off

  @Test
  void flagOffRefusesTheListRead() throws Exception {
    flag(false);

    assertDisabled(handler.handle(ctx("GET", NeoEndpointType.CRUD, null, null)));
  }

  @Test
  void flagOffRefusesTheReadById() throws Exception {
    flag(false);

    assertDisabled(handler.handle(ctx("GET", NeoEndpointType.CRUD, "ID-1", null)));
  }

  @Test
  void flagOffRefusesTheCrudWrites() throws Exception {
    flag(false);

    assertDisabled(handler.handle(ctx("POST", NeoEndpointType.CRUD, null, null)));
    assertDisabled(handler.handle(ctx("PUT", NeoEndpointType.CRUD, "ID-1", null)));
    assertDisabled(handler.handle(ctx("DELETE", NeoEndpointType.CRUD, "ID-1", null)));
  }

  @Test
  void flagOffRefusesTheDiscardActionAndNeverRunsIt() throws Exception {
    flag(false);

    assertDisabled(handler.handle(ctx("POST", NeoEndpointType.ACTION, "ID-1", "discard")));

    discard.verifyNoInteractions();
  }

  // ---------------------------------------------------------------- flag on

  @Test
  void flagOnLetsReadAndCrudContinueToTheDefaultHandling() {
    flag(true);

    assertNull(handler.handle(ctx("GET", NeoEndpointType.CRUD, null, null)));
    assertNull(handler.handle(ctx("GET", NeoEndpointType.CRUD, "ID-1", null)));
    assertNull(handler.handle(ctx("POST", NeoEndpointType.CRUD, null, null)));
    discard.verifyNoInteractions();
  }

  @Test
  void flagOnLetsAnotherActionContinue() {
    flag(true);

    assertNull(handler.handle(ctx("POST", NeoEndpointType.ACTION, "ID-1", "something-else")));
    assertNull(handler.handle(ctx("POST", NeoEndpointType.ACTION, "ID-1", null)));
    discard.verifyNoInteractions();
  }

  @Test
  void flagOnPostDiscardDelegatesWithTheRecordIdAndReturnsItsAnswer() {
    flag(true);
    NeoResponse answer = NeoResponse.error(409, "x");
    discard.when(() -> InvoiceInboxDiscard.discard("ID-1")).thenReturn(answer);

    NeoResponse response = handler.handle(ctx("POST", NeoEndpointType.ACTION, "ID-1", "discard"));

    assertSame(answer, response);
    discard.verify(() -> InvoiceInboxDiscard.discard("ID-1"));
  }

  @Test
  void aGetOnDiscardIs405AndDoesNotDiscard() {
    flag(true);

    NeoResponse response = handler.handle(ctx("GET", NeoEndpointType.ACTION, "ID-1", "discard"));

    assertEquals(405, response.getHttpStatus());
    discard.verifyNoInteractions();
  }

  // ---------------------------------------------------------------- review

  @Test
  void flagOffRefusesTheReviewBeforeAnythingElseAndNeverRunsIt() throws Exception {
    flag(false);

    assertDisabled(handler.handle(ctx("GET", NeoEndpointType.ACTION, "ID-1", "review")));
    assertDisabled(handler.handle(ctx("POST", NeoEndpointType.ACTION, "ID-1", "review")));

    review.verify(() -> InvoiceInboxReview.review(org.mockito.ArgumentMatchers.anyString()),
        never());
  }

  @Test
  void flagOnGetReviewDelegatesWithTheRecordIdAndReturnsItsAnswer() {
    flag(true);
    NeoResponse answer = NeoResponse.ok(new org.codehaus.jettison.json.JSONObject());
    review.when(() -> InvoiceInboxReview.review("ID-1")).thenReturn(answer);

    NeoResponse response = handler.handle(ctx("GET", NeoEndpointType.ACTION, "ID-1", "review"));

    assertSame(answer, response);
    review.verify(() -> InvoiceInboxReview.review("ID-1"));
    discard.verifyNoInteractions();
  }

  @Test
  void aPostOnReviewIs405AndNeitherReviewsNorDiscards() {
    flag(true);

    NeoResponse response = handler.handle(ctx("POST", NeoEndpointType.ACTION, "ID-1", "review"));

    assertEquals(405, response.getHttpStatus());
    review.verify(() -> InvoiceInboxReview.review(org.mockito.ArgumentMatchers.anyString()),
        never());
    discard.verifyNoInteractions();
  }

  @Test
  void aPostOnDiscardNeverTouchesTheReview() {
    flag(true);
    discard.when(() -> InvoiceInboxDiscard.discard("ID-1")).thenReturn(NeoResponse.error(409, "x"));

    handler.handle(ctx("POST", NeoEndpointType.ACTION, "ID-1", "discard"));

    review.verifyNoInteractions();
  }

  // ---------------------------------------------------------------- apply and mark-reviewed (ETP-6050)

  private static NeoContext post(String field, org.codehaus.jettison.json.JSONObject body) {
    return NeoContext.builder().specName("invoice-inbox").entityName("header")
        .httpMethod("POST").endpointType(NeoEndpointType.ACTION).recordId("ID-1")
        .fieldName(field).requestBody(body).build();
  }

  @Test
  void flagOffRefusesApplyAndMarkReviewedBeforeAnythingElse() throws Exception {
    flag(false);

    assertDisabled(handler.handle(post("apply", new org.codehaus.jettison.json.JSONObject())));
    assertDisabled(handler.handle(post("mark-reviewed",
        new org.codehaus.jettison.json.JSONObject())));

    apply.verifyNoInteractions();
    markReviewed.verifyNoInteractions();
  }

  @Test
  void aPostOnApplyDelegatesWithTheRecordIdAndTheBodyAndReturnsItsAnswer() throws Exception {
    flag(true);
    org.codehaus.jettison.json.JSONObject body = new org.codehaus.jettison.json.JSONObject()
        .put("updated", "T");
    NeoResponse answer = NeoResponse.ok(new org.codehaus.jettison.json.JSONObject());
    apply.when(() -> InvoiceInboxApply.apply("ID-1", body)).thenReturn(answer);

    assertSame(answer, handler.handle(post("apply", body)));

    apply.verify(() -> InvoiceInboxApply.apply("ID-1", body));
    markReviewed.verifyNoInteractions();
    discard.verifyNoInteractions();
    review.verifyNoInteractions();
  }

  @Test
  void aPostOnMarkReviewedDelegatesWithTheRecordIdAndTheBodyAndReturnsItsAnswer()
      throws Exception {
    flag(true);
    org.codehaus.jettison.json.JSONObject body = new org.codehaus.jettison.json.JSONObject()
        .put("updated", "T");
    NeoResponse answer = NeoResponse.ok(new org.codehaus.jettison.json.JSONObject());
    markReviewed.when(() -> InvoiceInboxMarkReviewed.markReviewed("ID-1", body))
        .thenReturn(answer);

    assertSame(answer, handler.handle(post("mark-reviewed", body)));

    markReviewed.verify(() -> InvoiceInboxMarkReviewed.markReviewed("ID-1", body));
    apply.verifyNoInteractions();
    discard.verifyNoInteractions();
    review.verifyNoInteractions();
  }

  @Test
  void applyAndMarkReviewedAreRefusedWithA405ForEveryVerbButPost() {
    flag(true);
    for (String method : new String[] { "GET", "PUT", "PATCH", "DELETE" }) {
      for (String action : new String[] { "apply", "mark-reviewed" }) {
        NeoResponse response = handler.handle(
            ctx(method, NeoEndpointType.ACTION, "ID-1", action));

        assertEquals(405, response.getHttpStatus(), method + " " + action);
      }
    }

    apply.verifyNoInteractions();
    markReviewed.verifyNoInteractions();
  }

  @Test
  void flagOnApplyAndMarkReviewedOnACrudEndpointContinueToTheDefaultHandling() {
    flag(true);

    assertNull(handler.handle(ctx("POST", NeoEndpointType.CRUD, "ID-1", "apply")));
    assertNull(handler.handle(ctx("POST", NeoEndpointType.CRUD, "ID-1", "mark-reviewed")));
    apply.verifyNoInteractions();
    markReviewed.verifyNoInteractions();
  }

  // ---------------------------------------------------------------- confirm

  @Test
  void flagOffRefusesConfirmAndNeverRunsIt() throws Exception {
    flag(false);

    assertDisabled(handler.handle(post("confirm", new org.codehaus.jettison.json.JSONObject())));

    confirm.verifyNoInteractions();
  }

  @Test
  void aPostOnConfirmDelegatesWithTheRecordIdAndTheBodyAndReturnsItsAnswer() throws Exception {
    flag(true);
    org.codehaus.jettison.json.JSONObject body = new org.codehaus.jettison.json.JSONObject()
        .put("updated", "T");
    NeoResponse answer = NeoResponse.ok(new org.codehaus.jettison.json.JSONObject());
    confirm.when(() -> InvoiceInboxConfirm.confirm("ID-1", body)).thenReturn(answer);

    assertSame(answer, handler.handle(post("confirm", body)));

    confirm.verify(() -> InvoiceInboxConfirm.confirm("ID-1", body));
    apply.verifyNoInteractions();
    markReviewed.verifyNoInteractions();
    discard.verifyNoInteractions();
    review.verifyNoInteractions();
  }

  @Test
  void confirmIsRefusedWithA405ForEveryVerbButPost() {
    flag(true);
    for (String method : new String[] { "GET", "PUT", "PATCH", "DELETE" }) {
      NeoResponse response = handler.handle(ctx(method, NeoEndpointType.ACTION, "ID-1", "confirm"));

      assertEquals(405, response.getHttpStatus(), method);
    }

    confirm.verifyNoInteractions();
  }

  @Test
  void confirmOnACrudEndpointContinuesToTheDefaultHandling() {
    flag(true);

    assertNull(handler.handle(ctx("POST", NeoEndpointType.CRUD, "ID-1", "confirm")));
    confirm.verifyNoInteractions();
  }

  // ---------------------------------------------------------------- contract

  @Test
  void itServesActionsAndHidesTheInboxActionsFromAgents() {
    assertTrue(handler.servesActions());
    assertEquals(Set.of("discard", "review", "apply", "mark-reviewed", "confirm",
        "currencyOptions"), handler.agentExcludedActions());
    assertEquals(6,handler.agentExcludedActions().size());
  }

  @Test
  void itIsBoundByAnnotationAndNotByNamedOrANormalScope() {
    Class<InvoiceInboxHeaderHandler> type = InvoiceInboxHeaderHandler.class;
    NeoExtension binding = type.getAnnotation(NeoExtension.class);

    assertNotNull(binding);
    assertEquals("invoice-inbox", binding.spec());
    assertEquals("header", binding.entity());
    assertNull(type.getAnnotation(Named.class));
    assertNull(type.getAnnotation(ApplicationScoped.class));
    assertTrue(Modifier.isPublic(type.getModifiers()));
  }

  // ---------------------------------------------------------------- currencyOptions (ETP-6051)

  private static final String SESSION_ORG = "SESSION-ORG";
  private static final String SESSION_CLIENT = "SESSION-CLIENT";

  /** One currencyOptions run through the handler, with the DAL, tenant fence and session mocked. */
  private final class OptionsRun {
    final org.openbravo.dal.service.OBDal dal = org.mockito.Mockito.mock(
        org.openbravo.dal.service.OBDal.class);
    final java.sql.PreparedStatement ps = org.mockito.Mockito.mock(java.sql.PreparedStatement.class);
    boolean visible = true;
    com.etendoerp.go.schemaforge.data.IncomingDocument row;

    OptionsRun(java.sql.Date storedDate) throws Exception {
      org.openbravo.model.common.enterprise.Organization rowOrg = org.mockito.Mockito.mock(
          org.openbravo.model.common.enterprise.Organization.class);
      org.mockito.Mockito.when(rowOrg.getId()).thenReturn("ROW-ORG");
      org.openbravo.model.ad.system.Client client = org.mockito.Mockito.mock(
          org.openbravo.model.ad.system.Client.class);
      org.mockito.Mockito.when(client.getId()).thenReturn("ROW-CLIENT");
      row = org.mockito.Mockito.mock(com.etendoerp.go.schemaforge.data.IncomingDocument.class);
      org.mockito.Mockito.when(row.get("organization")).thenReturn(rowOrg);
      org.mockito.Mockito.when(row.get("client")).thenReturn(client);
      org.mockito.Mockito.when(row.get(
          com.etendoerp.go.schemaforge.data.IncomingDocument.PROPERTY_INVOICEDATE))
          .thenReturn(storedDate);
      org.mockito.Mockito.when(dal.get(
          com.etendoerp.go.schemaforge.data.IncomingDocument.ENTITY_NAME, "ROW-1")).thenReturn(row);
      java.sql.ResultSet empty = org.mockito.Mockito.mock(java.sql.ResultSet.class);
      org.mockito.Mockito.when(empty.next()).thenReturn(false);
      org.mockito.Mockito.when(ps.executeQuery()).thenReturn(empty);
      java.sql.Connection conn = org.mockito.Mockito.mock(java.sql.Connection.class);
      org.mockito.Mockito.when(conn.prepareStatement(org.mockito.ArgumentMatchers.anyString()))
          .thenReturn(ps);
      org.mockito.Mockito.when(dal.getConnection()).thenReturn(conn);
    }

    NeoResponse run(String id, java.util.Map<String, String> query) {
      org.openbravo.dal.core.OBContext session = org.mockito.Mockito.mock(
          org.openbravo.dal.core.OBContext.class);
      org.openbravo.model.common.enterprise.Organization sessionOrg = org.mockito.Mockito.mock(
          org.openbravo.model.common.enterprise.Organization.class);
      org.mockito.Mockito.when(sessionOrg.getId()).thenReturn(SESSION_ORG);
      org.openbravo.model.ad.system.Client sessionClient = org.mockito.Mockito.mock(
          org.openbravo.model.ad.system.Client.class);
      org.mockito.Mockito.when(sessionClient.getId()).thenReturn(SESSION_CLIENT);
      org.mockito.Mockito.when(session.getCurrentOrganization()).thenReturn(sessionOrg);
      org.mockito.Mockito.when(session.getCurrentClient()).thenReturn(sessionClient);
      NeoContext context = NeoContext.builder().specName("invoice-inbox").entityName("header")
          .httpMethod("GET").endpointType(NeoEndpointType.ACTION).recordId(id)
          .fieldName("currencyOptions").queryParams(query).build();
      try (MockedStatic<org.openbravo.dal.core.OBContext> obContext = mockStatic(
          org.openbravo.dal.core.OBContext.class);
          MockedStatic<org.openbravo.dal.service.OBDal> obDal = mockStatic(
              org.openbravo.dal.service.OBDal.class);
          MockedStatic<TenantOwnership> tenant = mockStatic(TenantOwnership.class);
          MockedStatic<org.openbravo.erpCommon.utility.OBCurrencyUtils> currencies = mockStatic(
              org.openbravo.erpCommon.utility.OBCurrencyUtils.class)) {
        obContext.when(org.openbravo.dal.core.OBContext::getOBContext).thenReturn(session);
        obDal.when(org.openbravo.dal.service.OBDal::getInstance).thenReturn(dal);
        tenant.when(() -> TenantOwnership.isVisibleToCurrentTenant(
            org.mockito.ArgumentMatchers.any())).thenReturn(visible);
        currencies.when(() -> org.openbravo.erpCommon.utility.OBCurrencyUtils.getOrgCurrency(
            org.mockito.ArgumentMatchers.anyString())).thenReturn("EUR-ID");
        return handler.handle(context);
      }
    }
  }

  @Test
  void flagOffRefusesCurrencyOptions() throws Exception {
    flag(false);

    assertDisabled(handler.handle(ctx("GET", NeoEndpointType.ACTION, "ID-1", "currencyOptions")));
  }

  @Test
  void currencyOptionsIsScopedByTheRowsOrganizationAndClientNotTheSessions() throws Exception {
    flag(true);
    OptionsRun run = new OptionsRun(java.sql.Date.valueOf("2026-03-15"));

    NeoResponse response = run.run("ROW-1", null);

    assertEquals(200, response.getHttpStatus());
    org.codehaus.jettison.json.JSONArray data = response.getBody().getJSONObject("response")
        .getJSONArray("data");
    assertTrue(data.getJSONObject(0).getBoolean("isOrgCurrency"));
    verify(run.ps, org.mockito.Mockito.atLeastOnce()).setString(
        org.mockito.ArgumentMatchers.anyInt(), org.mockito.ArgumentMatchers.eq("ROW-CLIENT"));
    verify(run.ps, never()).setString(org.mockito.ArgumentMatchers.anyInt(),
        org.mockito.ArgumentMatchers.eq(SESSION_CLIENT));
    verify(run.ps, never()).setString(org.mockito.ArgumentMatchers.anyInt(),
        org.mockito.ArgumentMatchers.eq(SESSION_ORG));
  }

  @Test
  void currencyOptionsUsesTheStoredInvoiceDateAndTheDateParameterOverridesIt() throws Exception {
    flag(true);
    OptionsRun stored = new OptionsRun(java.sql.Date.valueOf("2026-03-15"));
    assertEquals(200, stored.run("ROW-1", null).getHttpStatus());
    verify(stored.ps, org.mockito.Mockito.atLeastOnce()).setDate(
        org.mockito.ArgumentMatchers.anyInt(),
        org.mockito.ArgumentMatchers.eq(java.sql.Date.valueOf("2026-03-15")));

    OptionsRun override = new OptionsRun(java.sql.Date.valueOf("2026-03-15"));
    assertEquals(200, override.run("ROW-1", java.util.Map.of("date", "2026-06-30")).getHttpStatus());
    verify(override.ps, org.mockito.Mockito.atLeastOnce()).setDate(
        org.mockito.ArgumentMatchers.anyInt(),
        org.mockito.ArgumentMatchers.eq(java.sql.Date.valueOf("2026-06-30")));
    verify(override.ps, never()).setDate(org.mockito.ArgumentMatchers.anyInt(),
        org.mockito.ArgumentMatchers.eq(java.sql.Date.valueOf("2026-03-15")));
  }

  @Test
  void currencyOptionsOfAnotherTenantsRowOrAMissingOneIs404() throws Exception {
    flag(true);
    OptionsRun foreign = new OptionsRun(java.sql.Date.valueOf("2026-03-15"));
    foreign.visible = false;
    assertEquals(404, foreign.run("ROW-1", null).getHttpStatus());

    OptionsRun missing = new OptionsRun(java.sql.Date.valueOf("2026-03-15"));
    assertEquals(404, missing.run("ROW-404", null).getHttpStatus());
  }

  @Test
  void currencyOptionsWithABadDateIs400() throws Exception {
    flag(true);
    OptionsRun run = new OptionsRun(java.sql.Date.valueOf("2026-03-15"));

    assertEquals(400, run.run("ROW-1", java.util.Map.of("date", "15/03/2026")).getHttpStatus());
  }

  @Test
  void currencyOptionsOnAnyVerbButGetIs405() {
    flag(true);

    assertEquals(405, handler.handle(ctx("POST", NeoEndpointType.ACTION, "ID-1",
        "currencyOptions")).getHttpStatus());
  }
}
