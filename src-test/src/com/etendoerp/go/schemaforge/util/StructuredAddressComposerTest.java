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

package com.etendoerp.go.schemaforge.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mockStatic;

import java.util.List;

import org.apache.commons.lang3.StringUtils;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import com.etendoerp.go.featureflags.GoFeatureFlags;
import com.etendoerp.go.schemaforge.util.StructuredAddressComposer.ComposedAddress;

/**
 * Unit tests for {@link StructuredAddressComposer} (ETP-5806).
 *
 * <p>{@link #composeAddressLines} is an exact port of Classic's
 * {@code LocationSEPA.java::fillAddressLines}: these tests pin that algorithm (everything fits
 * on line 1, overflow to line 2 with a truncated building name, a street alone longer than the
 * line gets truncated, separators), not a reinterpretation of the ticket's simplified wording.
 * Also {@link StructuredAddressComposer#isEnabled(String)}, which mirrors
 * {@code GoFeatureFlags.isEnabled} for {@code FLAG_STRUCTURED_ADDRESS_FOR_SEPA}, stubbed directly
 * so the assertion is independent of the local {@code Openbravo.properties} toggle. Since the
 * ETP-5806 staged-rollout follow-up it takes the account email and targets the evaluation at it,
 * so the tests below also pin the targeting key reaching the provider.
 *
 * @covers com.etendoerp.go.schemaforge.util.StructuredAddressComposer
 */
// Test methods live in the @Nested inner classes below; S2187 only inspects
// the outer class for @Test methods, hence the suppression.
@SuppressWarnings("java:S2187")
class StructuredAddressComposerTest {

  @Nested
  class ComposeAddressLines {

    @Test
    void everythingFitsOnLine1() {
      ComposedAddress composed = StructuredAddressComposer.composeAddressLines(
          "Gran Via", "45", "Local 2");

      assertEquals("Gran Via, 45 - Local 2", composed.getAddress1());
      assertNull(composed.getAddress2());
    }

    @Test
    void noBuildingNameStillFitsOnLine1WithNoDanglingSeparator() {
      ComposedAddress composed = StructuredAddressComposer.composeAddressLines(
          "Calle Mayor", "10", null);

      assertEquals("Calle Mayor, 10", composed.getAddress1());
      assertNull(composed.getAddress2());
    }

    @Test
    void streetAlonePresentOnly() {
      ComposedAddress composed = StructuredAddressComposer.composeAddressLines(
          "Just Street", null, null);

      assertEquals("Just Street", composed.getAddress1());
      assertNull(composed.getAddress2());
    }

    @Test
    void allBlankProducesBothLinesNull() {
      ComposedAddress composed = StructuredAddressComposer.composeAddressLines(" ", "", null);

      assertNull(composed.getAddress1());
      assertNull(composed.getAddress2());
    }

    @Test
    void streetAndNumberFitButWholeAddressOverflowsToLine2WithBuildingNameTruncated() {
      String street = StringUtils.repeat('A', 50);
      String buildingName = StringUtils.repeat('C', 70);
      // streetAndNumber = 50 + ", " + 3 digits = 55 chars, fits on line 1 alone.
      // wholeAddress = 55 + " - " + 70 = 128 chars, overflows: line 2 gets the (truncated to 60)
      // building name.
      ComposedAddress composed = StructuredAddressComposer.composeAddressLines(
          street, "123", buildingName);

      String expectedLine1 = street + ", 123";
      assertEquals(55, expectedLine1.length());
      assertEquals(expectedLine1, composed.getAddress1());
      assertEquals(StringUtils.left(buildingName, 60), composed.getAddress2());
      assertEquals(60, composed.getAddress2().length());
    }

    @Test
    void streetAndNumberFitAndLine2BuildingNameNotLongEnoughToTruncate() {
      String street = StringUtils.repeat('A', 50);
      String buildingName = StringUtils.repeat('C', 20);
      ComposedAddress composed = StructuredAddressComposer.composeAddressLines(
          street, "123", buildingName);

      assertEquals(street + ", 123", composed.getAddress1());
      // Not truncated: the whole 20-char building name survives untouched on line 2.
      assertEquals(buildingName, composed.getAddress2());
    }

    @Test
    void streetAloneLongerThan60IsTruncatedOnLine1AndNumberAndNameGoOnLine2() {
      String street = StringUtils.repeat('D', 70);
      ComposedAddress composed = StructuredAddressComposer.composeAddressLines(
          street, "123", "E");

      assertEquals(StringUtils.left(street, 60), composed.getAddress1());
      assertEquals(60, composed.getAddress1().length());
      assertEquals("123 - E", composed.getAddress2());
    }

    @Test
    void streetAloneLongerThan60WithOversizedTailAlsoTruncatesLine2() {
      String street = StringUtils.repeat('D', 70);
      String buildingName = StringUtils.repeat('E', 70);
      ComposedAddress composed = StructuredAddressComposer.composeAddressLines(
          street, "123", buildingName);

      assertEquals(StringUtils.left(street, 60), composed.getAddress1());
      String expectedTail = "123 - " + buildingName;
      assertEquals(StringUtils.left(expectedTail, 60), composed.getAddress2());
      assertEquals(60, composed.getAddress2().length());
    }

    /**
     * ETP-5806 CP-13 (QA finding) — the third branch IS reachable with input this composer itself
     * accepts: a street of exactly {@code STREET_NAME_MAX_LENGTH} (60) plus any non-blank number
     * already measures 63 as {@code street + ", " + number}. The javadoc claimed otherwise twice.
     * Nothing is truncated here — the street is exactly the line width — the number simply moves
     * to line 2.
     */
    @Test
    void sixtyCharStreetPlusANumberMovesTheNumberToLineTwoWithoutTruncatingTheStreet() {
      String street = StringUtils.repeat('A', 60);
      ComposedAddress composed = StructuredAddressComposer.composeAddressLines(street, "7", "Edificio A");
      assertEquals(street, composed.getAddress1());
      assertEquals("7 - Edificio A", composed.getAddress2());
    }

    /**
     * ETP-5806 CP-13 — the documented, deliberate consequence: a long street with no number and no
     * building name composes a second line that reads only {@code "s/n"}, because the blank number
     * resolved to that literal upstream. Product decision, faithful to Classic's line algorithm.
     */
    @Test
    void longStreetWithTheSlashNFallbackLeavesSlashNAloneOnLineTwo() {
      String street = StringUtils.repeat('B', 58);
      ComposedAddress composed = StructuredAddressComposer.composeAddressLines(
          street, StructuredAddressComposer.effectiveBuildingNumber(street, null), null);
      assertEquals(street, composed.getAddress1());
      assertEquals("s/n", composed.getAddress2());
    }

    @Test
    void separatorsAreCommaSpaceBetweenStreetAndNumberAndSpaceDashSpaceBeforeBuildingName() {
      ComposedAddress composed = StructuredAddressComposer.composeAddressLines(
          "Street", "1", "Name");
      assertEquals("Street, 1 - Name", composed.getAddress1());
    }

    @Test
    void blankInputsAreTrimmedBeforeComposing() {
      ComposedAddress composed = StructuredAddressComposer.composeAddressLines(
          "  Gran Via  ", " 45 ", "  ");
      // Trailing/leading whitespace on street/number is folded in, and a whitespace-only
      // building name behaves exactly like an absent one (no dangling " - ").
      assertEquals("Gran Via, 45", composed.getAddress1());
      assertNull(composed.getAddress2());
    }
  }

  @Nested
  class MissingMandatoryFields {

    @Test
    void nothingMissingWhenAllFourArePresent() {
      List<String> missing = StructuredAddressComposer.missingMandatoryFields(
          "Gran Via", "45", "Madrid", "ES");
      assertTrue(missing.isEmpty());
    }

    @Test
    void reportsEachFieldMissingInStableOrder() {
      List<String> missing = StructuredAddressComposer.missingMandatoryFields(null, "", " ", null);
      assertEquals(List.of("streetName", "cityName", "country"), missing);
    }

    /**
     * ETP-5806 CP-13 (QA finding) — {@code buildingNumber} is never named, not even when a blank
     * Street Name suppresses the {@code "s/n"} fallback. It used to be, so a create missing only
     * the street answered {@code 400 Missing required field(s): streetName, buildingNumber} and
     * sent the caller after a value it is never responsible for — while the same create WITH a
     * street and without a number succeeded and persisted {@code "s/n"}.
     */
    @Test
    void buildingNumberIsNeverReportedMissingEvenWithoutAStreetName() {
      List<String> missing = StructuredAddressComposer.missingMandatoryFields(
          null, null, "Madrid", "ES");
      assertEquals(List.of("streetName"), missing);
    }

    @Test
    void onlyCountryMissing() {
      List<String> missing = StructuredAddressComposer.missingMandatoryFields(
          "Gran Via", "45", "Madrid", null);
      assertEquals(List.of("country"), missing);
    }

    @Test
    void whitespaceOnlyValueCountsAsMissing() {
      List<String> missing = StructuredAddressComposer.missingMandatoryFields(
          "   ", "45", "Madrid", "ES");
      assertEquals(List.of("streetName"), missing);
    }
  }

  /**
   * ETP-5806 CP-13 (QA finding) — what {@code etendo_schema} publishes must match what the write
   * path enforces. With the flag on, the schema answered {@code requiredCount: 0} with
   * {@code streetName} under {@code optional} ("accepted but not needed") while
   * {@code etendo_create} refused the very same payload with a 400.
   */
  @Nested
  class PublishedCreateContract {

    @Test
    void callerRequiredIsStreetAndCityWhileTheFlagIsOn() {
      try (MockedStatic<GoFeatureFlags> flags = mockStatic(GoFeatureFlags.class)) {
        flags.when(() -> GoFeatureFlags.isEnabled(
            eq(GoFeatureFlags.FLAG_STRUCTURED_ADDRESS_FOR_SEPA), any())).thenReturn(true);

        assertEquals(java.util.Set.of("streetName", "cityName"),
            StructuredAddressComposer.callerRequiredFields("lola@example.com"));
      }
    }

    /** Building Number is NOT caller-required: the server resolves it to {@code "s/n"}. */
    @Test
    void callerRequiredNeverIncludesBuildingNumberOrBuildingName() {
      try (MockedStatic<GoFeatureFlags> flags = mockStatic(GoFeatureFlags.class)) {
        flags.when(() -> GoFeatureFlags.isEnabled(
            eq(GoFeatureFlags.FLAG_STRUCTURED_ADDRESS_FOR_SEPA), any())).thenReturn(true);

        assertFalse(StructuredAddressComposer.callerRequiredFields("lola@example.com")
            .contains("buildingNumber"));
        assertFalse(StructuredAddressComposer.callerRequiredFields("lola@example.com")
            .contains("buildingName"));
      }
    }

    @Test
    void serverDefaultedIsExactlyBuildingNumberWhileTheFlagIsOn() {
      try (MockedStatic<GoFeatureFlags> flags = mockStatic(GoFeatureFlags.class)) {
        flags.when(() -> GoFeatureFlags.isEnabled(
            eq(GoFeatureFlags.FLAG_STRUCTURED_ADDRESS_FOR_SEPA), any())).thenReturn(true);

        assertEquals(java.util.Set.of("buildingNumber"),
            StructuredAddressComposer.serverDefaultedFields("lola@example.com"));
      }
    }

    /** Flag off: the pre-ETP-5806 (country-only) mandatoriness is republished unchanged. */
    @Test
    void bothSetsAreEmptyWhileTheFlagIsOff() {
      try (MockedStatic<GoFeatureFlags> flags = mockStatic(GoFeatureFlags.class)) {
        flags.when(() -> GoFeatureFlags.isEnabled(
            eq(GoFeatureFlags.FLAG_STRUCTURED_ADDRESS_FOR_SEPA), any())).thenReturn(false);

        assertTrue(StructuredAddressComposer.callerRequiredFields("lola@example.com").isEmpty());
        assertTrue(StructuredAddressComposer.serverDefaultedFields("lola@example.com").isEmpty());
      }
    }
  }

  @Nested
  class IsEnabled {

    /** Reflects {@link GoFeatureFlags#isEnabled} resolving {@code true}. */
    @Test
    void reflectsTheFlagResolvingTrue() {
      try (MockedStatic<GoFeatureFlags> flags = mockStatic(GoFeatureFlags.class)) {
        flags.when(() -> GoFeatureFlags.isEnabled(
            eq(GoFeatureFlags.FLAG_STRUCTURED_ADDRESS_FOR_SEPA), any())).thenReturn(true);

        assertTrue(StructuredAddressComposer.isEnabled("lola@example.com"));
      }
    }

    /** Reflects {@link GoFeatureFlags#isEnabled} resolving {@code false}. */
    @Test
    void reflectsTheFlagResolvingFalse() {
      try (MockedStatic<GoFeatureFlags> flags = mockStatic(GoFeatureFlags.class)) {
        flags.when(() -> GoFeatureFlags.isEnabled(
            eq(GoFeatureFlags.FLAG_STRUCTURED_ADDRESS_FOR_SEPA), any())).thenReturn(false);

        assertFalse(StructuredAddressComposer.isEnabled("lola@example.com"));
      }
    }

    /**
     * Queries exactly {@code FLAG_STRUCTURED_ADDRESS_FOR_SEPA} with a non-null context — never a
     * different flag key, never a null context.
     */
    @Test
    void queriesExactlyTheStructuredAddressFlagKey() {
      try (MockedStatic<GoFeatureFlags> flags = mockStatic(GoFeatureFlags.class)) {
        flags.when(() -> GoFeatureFlags.isEnabled(
            eq(GoFeatureFlags.FLAG_STRUCTURED_ADDRESS_FOR_SEPA), any())).thenReturn(true);

        StructuredAddressComposer.isEnabled("lola@example.com");

        flags.verify(() -> GoFeatureFlags.isEnabled(
            eq(GoFeatureFlags.FLAG_STRUCTURED_ADDRESS_FOR_SEPA), any()));
      }
    }

    /**
     * ETP-5806 follow-up — the account email becomes the evaluation's targeting key, so a
     * provider-side rule written against one account can turn the backend's own
     * validate/persist/expose gating on for exactly that account.
     */
    @Test
    void targetsTheGivenAccountEmail() {
      try (MockedStatic<GoFeatureFlags> flags = mockStatic(GoFeatureFlags.class)) {
        flags.when(() -> GoFeatureFlags.isEnabled(
            eq(GoFeatureFlags.FLAG_STRUCTURED_ADDRESS_FOR_SEPA), any())).thenReturn(false);

        StructuredAddressComposer.isEnabled("lola@example.com");

        flags.verify(() -> GoFeatureFlags.isEnabled(
            eq(GoFeatureFlags.FLAG_STRUCTURED_ADDRESS_FOR_SEPA),
            argThat(ctx -> "lola@example.com".equals(ctx.getTargetingKey()))));
      }
    }

    /**
     * A targeted account resolves {@code true} while an untargeted one resolves {@code false} in
     * the very same environment — the staged rollout this gating exists for. A global, account-less
     * evaluation could not tell the two apart, which is what left the write path accepting per the
     * global default while the UI already rendered the fields for the targeted account.
     */
    @Test
    void resolvesPerAccountWithinTheSameEnvironment() {
      try (MockedStatic<GoFeatureFlags> flags = mockStatic(GoFeatureFlags.class)) {
        flags.when(() -> GoFeatureFlags.isEnabled(
            eq(GoFeatureFlags.FLAG_STRUCTURED_ADDRESS_FOR_SEPA),
            argThat(ctx -> "lola@example.com".equals(ctx.getTargetingKey())))).thenReturn(true);
        flags.when(() -> GoFeatureFlags.isEnabled(
            eq(GoFeatureFlags.FLAG_STRUCTURED_ADDRESS_FOR_SEPA),
            argThat(ctx -> !"lola@example.com".equals(ctx.getTargetingKey())))).thenReturn(false);

        assertTrue(StructuredAddressComposer.isEnabled("lola@example.com"));
        assertFalse(StructuredAddressComposer.isEnabled("someone@example.com"));
      }
    }

    /**
     * A blank or {@code null} email is "no account", never "everyone": the context carries no
     * targeting key at all, so an account-scoped rule cannot match it.
     */
    @Test
    void blankEmailYieldsAnUntargetedEvaluation() {
      try (MockedStatic<GoFeatureFlags> flags = mockStatic(GoFeatureFlags.class)) {
        flags.when(() -> GoFeatureFlags.isEnabled(
            eq(GoFeatureFlags.FLAG_STRUCTURED_ADDRESS_FOR_SEPA), any())).thenReturn(false);

        assertFalse(StructuredAddressComposer.isEnabled(null));
        assertFalse(StructuredAddressComposer.isEnabled("   "));

        flags.verify(() -> GoFeatureFlags.isEnabled(
            eq(GoFeatureFlags.FLAG_STRUCTURED_ADDRESS_FOR_SEPA),
            argThat(ctx -> ctx.getTargetingKey() == null)), org.mockito.Mockito.times(2));
      }
    }
  }

  @Nested
  class EffectiveBuildingNumber {

    /**
     * ETP-5806 follow-up (QA finding): a present streetName with a blank/absent buildingNumber
     * falls back to the "s/n" convention, instead of being reported as a missing mandatory field.
     */
    @Test
    void blankBuildingNumberWithStreetNamePresentFallsBackToSlashN() {
      assertEquals("s/n",
          StructuredAddressComposer.effectiveBuildingNumber("Gran Via", ""));
      assertEquals("s/n",
          StructuredAddressComposer.effectiveBuildingNumber("Gran Via", null));
      assertEquals("s/n",
          StructuredAddressComposer.effectiveBuildingNumber("Gran Via", "   "));
    }

    /** A non-blank buildingNumber is trimmed and returned as-is, never replaced by the fallback. */
    @Test
    void nonBlankBuildingNumberIsTrimmedAndKeptAsIs() {
      assertEquals("45", StructuredAddressComposer.effectiveBuildingNumber("Gran Via", "45"));
      assertEquals("45", StructuredAddressComposer.effectiveBuildingNumber("Gran Via", "  45  "));
    }

    /**
     * When streetName is itself blank, no fallback is applied: the raw (blank/null)
     * buildingNumber is returned unchanged, so streetName remains the only reported missing
     * field — there is nothing to anchor a "no building number" convention to without a street.
     */
    @Test
    void blankStreetNameLeavesBuildingNumberUnchangedNoFallbackInvented() {
      assertNull(StructuredAddressComposer.effectiveBuildingNumber(null, null));
      assertEquals("", StructuredAddressComposer.effectiveBuildingNumber("", ""));
      assertEquals("   ", StructuredAddressComposer.effectiveBuildingNumber("   ", "   "));
    }
  }

  @Nested
  class FieldsExceedingMaxLength {

    @Test
    void nothingTooLongWhenAllAtOrBelowTheirLimit() {
      // CP-6 (QA Pasada 2): Street Name's own limit is 60, tighter than the EM_OBSEPA_StrtNm
      // column's 70 — see StructuredAddressComposer#STREET_NAME_MAX_LENGTH.
      List<String> tooLong = StructuredAddressComposer.fieldsExceedingMaxLength(
          StringUtils.repeat('A', 60), StringUtils.repeat('B', 16), StringUtils.repeat('C', 35));
      assertTrue(tooLong.isEmpty());
    }

    @Test
    void nullValuesAreNeverTooLongRegardlessOfTheirWouldBeLength() {
      List<String> tooLong = StructuredAddressComposer.fieldsExceedingMaxLength(null, null, null);
      assertTrue(tooLong.isEmpty());
    }

    @Test
    void streetNameOneCharOverTheSixtyLimitIsFlagged() {
      // CP-6 (QA Pasada 2, persiste): a street between 61 and 70 chars used to pass this check
      // (the old 70-char cap matched the SEPA column) and then get silently truncated mid-word by
      // composeAddressLines, since it could not fit the 60-char Address1/Address2 line. It must
      // now be reported as exceeding, not accepted.
      List<String> tooLong = StructuredAddressComposer.fieldsExceedingMaxLength(
          StringUtils.repeat('A', 61), null, null);
      assertEquals(List.of("streetName"), tooLong);
    }

    @Test
    void streetNameInTheOldSeventyCharWindowIsNowFlaggedNotAccepted() {
      // CP-6 — the specific regression QA found: a 70-char street (the old, now-removed ceiling)
      // must be rejected cleanly instead of silently truncated when composed.
      List<String> tooLong = StructuredAddressComposer.fieldsExceedingMaxLength(
          StringUtils.repeat('A', 70), null, null);
      assertEquals(List.of("streetName"), tooLong);
    }

    @Test
    void buildingNumberOneCharOverTheSixteenLimitIsFlagged() {
      List<String> tooLong = StructuredAddressComposer.fieldsExceedingMaxLength(
          null, StringUtils.repeat('B', 17), null);
      assertEquals(List.of("buildingNumber"), tooLong);
    }

    @Test
    void buildingNameOneCharOverTheThirtyFiveLimitIsFlagged() {
      List<String> tooLong = StructuredAddressComposer.fieldsExceedingMaxLength(
          null, null, StringUtils.repeat('C', 36));
      assertEquals(List.of("buildingName"), tooLong);
    }

    @Test
    void allThreeOverTheirLimitAreReportedInStableOrder() {
      List<String> tooLong = StructuredAddressComposer.fieldsExceedingMaxLength(
          StringUtils.repeat('A', 61), StringUtils.repeat('B', 17), StringUtils.repeat('C', 36));
      assertEquals(List.of("streetName", "buildingNumber", "buildingName"), tooLong);
    }
  }
}
