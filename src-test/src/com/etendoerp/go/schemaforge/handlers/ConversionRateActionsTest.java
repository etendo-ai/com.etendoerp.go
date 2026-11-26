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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.LocalDateTime;
import java.time.ZoneId;

import org.codehaus.jettison.json.JSONObject;
import org.junit.Test;

/**
 * Unit tests for {@link ConversionRateActions#serverClock}: the action the SPA reads to default and
 * bound the start of a new rate, and — since ETP-6365 QA — to convert the server's wall-clock
 * datetimes to the viewer's zone. The answer carries the current minute (seconds dropped) and the
 * server zone id, both from the store's seams, so the test does not depend on the JVM's zone.
 *
 * @covers com.etendoerp.go.schemaforge.handlers.ConversionRateActions
 */
public class ConversionRateActionsTest {

  @Test
  public void serverClockAnswersTheCurrentMinuteAndTheServerZone() throws Exception {
    ConversionRateStore store = mock(ConversionRateStore.class);
    when(store.now()).thenReturn(LocalDateTime.of(2026, 11, 20, 10, 5, 42));
    when(store.zone()).thenReturn(ZoneId.of("America/Argentina/Buenos_Aires"));

    JSONObject clock = ConversionRateActions.serverClock(store);

    assertEquals("2026-11-20T10:05:00", clock.getString("now"));
    assertEquals("America/Argentina/Buenos_Aires", clock.getString("timeZone"));
  }
}
