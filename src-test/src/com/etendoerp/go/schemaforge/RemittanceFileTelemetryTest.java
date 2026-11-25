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

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.openbravo.module.remittance.Remittance;

/**
 * Unit tests for {@link RemittanceFileTelemetry}, {@code backend_remittance_file_generated}, sent
 * on every download of the SEPA file (ETP-6247: the file is never stored): the properties in their
 * stable order (account, direction AR/AP, distinct invoices, channel, flag), {@code role_groups}
 * only when there are groups, and the event never throwing into the action.
 * {@code NeoTelemetryServiceTest} specifies how the service sanitizes them.
 *
 * <p>A class of its own: {@code RemittanceFileTelemetry} is a new production class.
 *
 * @covers com.etendoerp.go.schemaforge.RemittanceFileTelemetry
 */
class RemittanceFileTelemetryTest {

  @Test
  void thePropertiesOfACollectionCarryTheRoleGroups() {
    Map<String, Object> properties = RemittanceFileTelemetry.properties("CLIENT-1", true, 7,
        "ui", List.of("finance"));

    assertEquals(List.of("account_id", "direction", "invoice_count", "channel", "flag_enabled",
        "role_groups"), List.copyOf(properties.keySet()));
    assertEquals("CLIENT-1", properties.get("account_id"));
    assertEquals("AR", properties.get("direction"));
    assertEquals(7, properties.get("invoice_count"));
    assertEquals("ui", properties.get("channel"));
    assertEquals(true, properties.get("flag_enabled"));
    assertEquals(List.of("finance"), properties.get("role_groups"));
    assertFalse(properties.containsKey("is_regeneration"), "there is nothing to regenerate");
  }

  @Test
  void aPaymentIsApAndAnEmptyRoleGroupsIsLeftOff() {
    Map<String, Object> properties = RemittanceFileTelemetry.properties("CLIENT-1", false, 0,
        "mcp", List.of());

    assertEquals("AP", properties.get("direction"));
    assertFalse(properties.containsKey("role_groups"));
    assertFalse(RemittanceFileTelemetry.properties("C", true, 1, "ui", null)
        .containsKey("role_groups"));
  }

  /** Telemetry is best effort: a remittance that cannot be read never fails the download. */
  @Test
  void fileDownloadedNeverThrows() {
    Remittance broken = Mockito.mock(Remittance.class);
    Mockito.when(broken.getClient()).thenThrow(new IllegalStateException("detached"));

    assertDoesNotThrow(() -> RemittanceFileTelemetry.fileDownloaded(broken, true, 1));
  }
}
