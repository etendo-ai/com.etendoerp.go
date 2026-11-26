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
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;

import java.util.Map;
import java.util.function.Supplier;
import java.util.stream.Stream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.MockedStatic;
import org.openbravo.model.ad.access.Role;

import com.etendoerp.go.schemaforge.handlers.NotPostedDocumentsHandler;
import com.etendoerp.go.schemaforge.util.GoFeature;
import com.etendoerp.go.schemaforge.util.GoFeatureAccess;
import com.etendoerp.go.schemaforge.util.NeoAccessHelper;
import com.etendoerp.go.schemaforge.util.RoleAccessMatrix;

/**
 * Wiring of the handler gates to {@link GoFeatureAccess} (ETP-4802): each gate reaches the shared
 * check with its own feature, driven by a real GO Access row (not the legacy path), so a wiring
 * regression (a handler asking for the wrong feature, or going back to an inline window check)
 * fails here. The matrix of decisions itself is in {@code GoFeatureAccessTest}.
 *
 * @covers com.etendoerp.go.schemaforge.InventoryStockReportHandler
 * @covers com.etendoerp.go.schemaforge.handlers.NotPostedDocumentsHandler
 * @covers com.etendoerp.go.schemaforge.BalanceSheetReportHandler
 * @covers com.etendoerp.go.schemaforge.GeneralLedgerReportHandler
 * @covers com.etendoerp.go.schemaforge.JournalEntriesReportHandler
 * @covers com.etendoerp.go.schemaforge.ProfitLossReportHandler
 * @covers com.etendoerp.go.schemaforge.TrialBalanceReportHandler
 */
class GoFeatureGateHandlersTest {

  private MockedStatic<NeoAccessHelper> accessMock;
  private MockedStatic<GoFeatureAccess> goAccess;
  private Role role;

  @BeforeEach
  void setUp() {
    role = mock(Role.class);
    accessMock = mockStatic(NeoAccessHelper.class);
    accessMock.when(NeoAccessHelper::resolveCurrentRole).thenReturn(role);
    accessMock.when(() -> NeoAccessHelper.isWriteMethod(anyString())).thenCallRealMethod();
    // every classic grant is denied: only a GO Access row can open a gate
    goAccess = mockStatic(GoFeatureAccess.class, CALLS_REAL_METHODS);
  }

  @AfterEach
  void tearDown() {
    goAccess.close();
    accessMock.close();
  }

  static Stream<Object[]> gates() {
    return Stream.of(
        gate("InventoryStockReportHandler", GoFeature.INVENTORY_STOCK_REPORT,
            InventoryStockReportHandler::new),
        gate("NotPostedDocumentsHandler", GoFeature.NOT_POSTED_DOCUMENTS,
            NotPostedDocumentsHandler::new),
        gate("BalanceSheetReportHandler", GoFeature.FINANCIAL_REPORTS,
            BalanceSheetReportHandler::new),
        gate("GeneralLedgerReportHandler", GoFeature.FINANCIAL_REPORTS,
            GeneralLedgerReportHandler::new),
        gate("JournalEntriesReportHandler", GoFeature.FINANCIAL_REPORTS,
            JournalEntriesReportHandler::new),
        gate("ProfitLossReportHandler", GoFeature.FINANCIAL_REPORTS, ProfitLossReportHandler::new),
        gate("TrialBalanceReportHandler", GoFeature.FINANCIAL_REPORTS,
            TrialBalanceReportHandler::new));
  }

  private static Object[] gate(String name, GoFeature feature, Supplier<NeoHandler> handler) {
    return new Object[] { name, feature, handler };
  }

  @ParameterizedTest(name = "{0}: a GO Access row opens the gate")
  @MethodSource("gates")
  void aGoAccessRowOfTheHandlersFeatureGrantsAccess(String name, GoFeature feature,
      Supplier<NeoHandler> handler) {
    goAccess.when(() -> GoFeatureAccess.grantedTiers(role))
        .thenReturn(Map.of(feature.id, RoleAccessMatrix.READ_ONLY));

    assertTrue(handler.get().isAccessibleForCurrentRole(), name);
  }

  @ParameterizedTest(name = "{0}: a row of another feature does not open the gate")
  @MethodSource("gates")
  void aRowOfAnotherFeatureDoesNotGrantAccess(String name, GoFeature feature,
      Supplier<NeoHandler> handler) {
    GoFeature other = feature == GoFeature.SMART_SCAN ? GoFeature.FISCAL_MODELS
        : GoFeature.SMART_SCAN;
    goAccess.when(() -> GoFeatureAccess.grantedTiers(role))
        .thenReturn(Map.of(other.id, RoleAccessMatrix.FULL));

    assertFalse(handler.get().isAccessibleForCurrentRole(), name);
  }

  @ParameterizedTest(name = "{0}: no row and no classic grant is refused with 403")
  @MethodSource("gates")
  void noRowAndNoLegacyGrantIsRefusedWith403(String name, GoFeature feature,
      Supplier<NeoHandler> handler) {
    goAccess.when(() -> GoFeatureAccess.grantedTiers(role)).thenReturn(Map.of());
    NeoContext context = NeoContext.builder().specName("report").entityName("report")
        .httpMethod("POST").build();

    NeoResponse response = handler.get().handle(context);

    assertEquals(403, response.getHttpStatus(), name);
  }

  @ParameterizedTest(name = "{0}: with a row the request gets past the gate")
  @MethodSource("gates")
  void withARowTheRequestIsNotRefusedByTheGate(String name, GoFeature feature,
      Supplier<NeoHandler> handler) {
    goAccess.when(() -> GoFeatureAccess.grantedTiers(role))
        .thenReturn(Map.of(feature.id, RoleAccessMatrix.FULL));
    NeoContext context = NeoContext.builder().specName("report").entityName("report")
        .httpMethod("PUT").build();

    NeoResponse response;
    try {
      response = handler.get().handle(context);
    } catch (RuntimeException e) {
      // got past the gate and failed later for lack of a database: not a 403
      return;
    }

    // a handler may answer nothing for an unsupported method; only a 403 means the gate refused
    assertTrue(response == null || response.getHttpStatus() != 403, name);
  }
}
