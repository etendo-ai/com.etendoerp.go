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

import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * The pairing rule of {@code counts.discardedPairs} ({@link BankBounceDiscardedPairs#countPairs}),
 * the one the panel's "Descartadas (N)" shows: two discarded lines are ONE entry when each one's
 * link points at the other; every other line is an entry of its own. Pure: plain ids and links, no
 * DAL. How the links are read for a {@code pendingLines} answer, and when the count is written at
 * all, is in {@code StatementLineFilterTest} and {@code ReconciliationHandlerTest}.
 *
 * @covers com.etendoerp.go.schemaforge.BankBounceDiscardedPairs
 */
@DisplayName("BankBounceDiscardedPairs.countPairs — discarded lines counted by pair")
class BankBounceDiscardedPairsTest {

  /**
   * The rule's cases: a name, the ids in answer order, each line's link, and the expected count.
   *
   * @return the cases
   */
  static Stream<Arguments> cases() {
    return Stream.of(
        Arguments.of("no line", List.of(), Map.of(), 0),
        Arguments.of("a mutual pair is one entry", List.of("A", "B"),
            Map.of("A", "B", "B", "A"), 1),
        Arguments.of("the same pair listed the other way round", List.of("B", "A"),
            Map.of("A", "B", "B", "A"), 1),
        Arguments.of("a one-sided link is two entries", List.of("A", "B"), Map.of("A", "B"), 2),
        Arguments.of("a link to a line not in the set is two entries", List.of("A", "B"),
            Map.of("A", "X", "B", "A"), 2),
        Arguments.of("a pair, a lone line and a dangling link are three",
            List.of("A", "B", "C", "D"), Map.of("A", "B", "B", "A", "C", "Z"), 3),
        Arguments.of("a self-link counts once", List.of("A"), Map.of("A", "A"), 1),
        Arguments.of("a repeated id counts once", List.of("A", "A"), Map.of("A", "A"), 1),
        Arguments.of("a third line pointing at an already-paired line is alone",
            List.of("A", "B", "C"), Map.of("A", "B", "B", "A", "C", "A"), 2),
        Arguments.of("a link that does not point back leaves the partner for its own pair",
            List.of("A", "B", "C"), Map.of("A", "B", "B", "C", "C", "B"), 2),
        Arguments.of("two mutual pairs are two entries", List.of("A", "B", "C", "D"),
            Map.of("A", "B", "B", "A", "C", "D", "D", "C"), 2));
  }

  /**
   * Each case of {@link #cases}.
   *
   * @param name     what the case shows
   * @param ids      the discarded line ids, in answer order
   * @param links    the line each one links to
   * @param expected the expected count
   */
  @ParameterizedTest(name = "{0} → {3}")
  @MethodSource("cases")
  @DisplayName("the pairing rule")
  void countsByPair(String name, List<String> ids, Map<String, String> links, int expected) {
    assertEquals(expected, BankBounceDiscardedPairs.countPairs(ids, links), name);
  }
}
