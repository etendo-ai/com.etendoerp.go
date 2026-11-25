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
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.openbravo.model.ad.system.Client;
import org.openbravo.module.remittance.Remittance;

import com.etendoerp.go.schemaforge.telemetry.NeoTelemetryEvents;
import com.etendoerp.go.schemaforge.telemetry.NeoTelemetryIdentity;
import com.etendoerp.go.schemaforge.telemetry.NeoTelemetryService;

/**
 * Unit tests for {@link RemittanceFileTelemetry}, {@code backend_remittance_file_generated}, sent
 * on every download of the SEPA file (ETP-6247: the file is never stored): the properties in their
 * stable order (account, direction AR/AP, distinct invoices, channel, flag), {@code role_groups}
 * only when there are groups, the event never throwing into the action, and the identity
 * ({@code ad_user_id}, ...) captured on the calling thread and carried by the event emitted on the
 * sender thread, which has no session (ETP-6228). {@code NeoTelemetryServiceTest} specifies how
 * the service sanitizes them.
 *
 * <p>A class of its own: {@code RemittanceFileTelemetry} is a new production class.
 *
 * @covers com.etendoerp.go.schemaforge.RemittanceFileTelemetry
 */
class RemittanceFileTelemetryTest {

  private static final String CLIENT_ID = "CLIENT-1";

  @AfterEach
  void tearDown() {
    RemittanceFileTelemetry.setTelemetryService(null);
  }

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

  /**
   * The sender thread has no session: the identity is captured through the service on the calling
   * thread and is the one the emitted event carries, so it has an {@code ad_user_id}.
   */
  @Test
  void theIdentityCapturedOnTheCallingThreadIsTheOneTheEventCarries() {
    NeoTelemetryService service = mock(NeoTelemetryService.class);
    RemittanceFileTelemetry.setTelemetryService(service);
    NeoTelemetryIdentity identity = new NeoTelemetryIdentity("U1", CLIENT_ID, "OWNER",
        "productive");
    AtomicReference<Thread> capturedOn = new AtomicReference<>();
    AtomicReference<Thread> emittedOn = new AtomicReference<>();
    AtomicReference<NeoTelemetryIdentity> seen = new AtomicReference<>();
    when(service.captureIdentity(CLIENT_ID, null)).thenAnswer(invocation -> {
      capturedOn.set(Thread.currentThread());
      return identity;
    });
    doAnswer(invocation -> {
      emittedOn.set(Thread.currentThread());
      seen.set(NeoTelemetryIdentity.handedOff());
      return null;
    }).when(service).emit(eq(NeoTelemetryEvents.BACKEND_REMITTANCE_FILE_GENERATED), anyMap(),
        eq(CLIENT_ID));
    Client client = mock(Client.class);
    when(client.getId()).thenReturn(CLIENT_ID);
    Remittance remittance = mock(Remittance.class);
    when(remittance.getClient()).thenReturn(client);

    RemittanceFileTelemetry.fileDownloaded(remittance, true, 3);

    verify(service, timeout(2000)).emit(eq(NeoTelemetryEvents.BACKEND_REMITTANCE_FILE_GENERATED),
        anyMap(), eq(CLIENT_ID));
    assertSame(Thread.currentThread(), capturedOn.get());
    assertNotSame(Thread.currentThread(), emittedOn.get());
    assertSame(identity, seen.get());
    assertEquals("U1", seen.get().toProperties().get("ad_user_id"));
  }
}
