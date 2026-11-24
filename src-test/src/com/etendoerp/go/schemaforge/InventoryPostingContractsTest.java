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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.function.UnaryOperator;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.etendoerp.go.schemaforge.util.NeoActionAvailability;
import com.etendoerp.go.schemaforge.util.NeoActionContract;

/**
 * ETP-6406: the {@code post} / {@code unpost} contracts of Internal Consumption, Physical Inventory
 * and Goods Movement — declared in order, judged on each document's own state property.
 */
class InventoryPostingContractsTest {

  private static UnaryOperator<String> record(Map<String, String> values) {
    return values::get;
  }

  private static Boolean availableOn(Map<String, NeoActionContract> contracts, String action,
      Map<String, String> state) {
    NeoActionAvailability availability = contracts.get(action).getAvailability();
    return availability.evaluate(record(state)).available();
  }

  @Test
  @DisplayName("both sets declare post then unpost, and never the AD Posted button")
  void declaresPostThenUnpost() {
    for (Map<String, NeoActionContract> contracts : List.of(InventoryPostingContracts.byStatus(),
        InventoryPostingContracts.byProcessed())) {
      assertEquals(List.of("post", "unpost"), List.copyOf(contracts.keySet()));
      String post = contracts.get("post").getDescription();
      assertTrue(post.contains("409 costNotCalculated") && post.contains("costCalculationStalled"),
          post);
      assertTrue(post.contains("never use the AD Posted button"), post);
    }
  }

  @Test
  @DisplayName("Internal Consumption: post on a completed, unposted one; unpost once posted")
  void internalConsumptionReadsStatus() {
    Map<String, NeoActionContract> c = InventoryPostingContracts.byStatus();
    assertTrue(availableOn(c, "post", Map.of("status", "CO", "posted", "N")));
    assertFalse(availableOn(c, "post", Map.of("status", "DR", "posted", "N")));
    assertFalse(availableOn(c, "post", Map.of("status", "CO", "posted", "Y")));
    assertTrue(availableOn(c, "unpost", Map.of("status", "CO", "posted", "Y")));
    assertFalse(availableOn(c, "unpost", Map.of("status", "CO", "posted", "N")));
  }

  @Test
  @DisplayName("Physical Inventory and Goods Movement: availability read from processed")
  void inventoryAndMovementReadProcessed() {
    Map<String, NeoActionContract> c = InventoryPostingContracts.byProcessed();
    assertTrue(availableOn(c, "post", Map.of("processed", "true", "posted", "N")));
    assertFalse(availableOn(c, "post", Map.of("processed", "false", "posted", "N")));
    assertFalse(availableOn(c, "post", Map.of("processed", "true", "posted", "Y")));
    assertTrue(availableOn(c, "unpost", Map.of("processed", "true", "posted", "Y")));
  }

  @Test
  @DisplayName("unpost does not promise the goods documents' 422 for a document not posted")
  void unpostKeepsTheHandled200() {
    String unpost = InventoryPostingContracts.byStatus().get("unpost").getDescription();
    assertTrue(unpost.contains("0 entries removed"), unpost);
    assertFalse(unpost.contains("ETGO_GoodsUnpostNotPosted"), unpost);
  }
}
