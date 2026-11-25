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

import java.util.Set;

import com.etendoerp.go.common.GoAccountResolver;
import com.etendoerp.go.schemaforge.util.StructuredAddressComposer;

/**
 * The published create contract of an entity whose address is composed by
 * {@link StructuredAddressComposer} (ETP-5806 CP-13, QA finding).
 *
 * <p><b>Why this exists.</b> {@code etendo_schema} derives mandatoriness from
 * {@code AD_Column.isMandatory()}, and none of {@code EM_OBSEPA_StrtNm}, {@code EM_OBSEPA_BldgNb}
 * or {@code C_Location.City} is NOT NULL — the mandatoriness is the composer's own product rule,
 * stricter than Classic's. So {@code view:"create"} answered {@code requiredCount: 0} with
 * {@code streetName} and {@code cityName} under a group whose hint reads "accepted but not needed",
 * while {@code etendo_create} refused the very same payload with a 400. A rule AD cannot see can
 * only be declared by the entity's own customization — never by a generic service, which would
 * have to name the entities to find them.
 *
 * <p><b>Why an interface rather than three copies.</b> The three entities that wrap a
 * {@code C_Location} ({@code contacts/locationAddress}, {@code warehouse/location},
 * {@code bp-location}) answer this identically, because they share one composer. A mixin they opt
 * into by {@code implements} is the explicit shared-util form the extensibility rules ask for:
 * the binding is still per-handler and visible in each handler's declaration, and nothing
 * generic branches on who they are.
 *
 * <p>Both answers are gated on {@code structured-address-for-sepa} for the caller's account; with
 * the flag off they are empty, which republishes the pre-ETP-5806 (country-only) contract
 * unchanged.
 */
public interface StructuredAddressCustomization extends NeoHandler {

  /**
   * {@code streetName} and {@code cityName} — the two fields the write path refuses a create
   * without and AD does not know about. {@code country} is absent because its column IS mandatory,
   * so the generic rule already reports it.
   *
   * @return the caller-required create fields; never {@code null}
   */
  @Override
  default Set<String> callerRequiredCreateFields() {
    return StructuredAddressComposer.callerRequiredFields(GoAccountResolver.currentAccountEmail());
  }

  /**
   * {@code buildingNumber}, and nothing else. It is mandatory at the level of the stored row and
   * never at the level of what a caller supplies: a blank or omitted value becomes {@code "s/n"}
   * ({@link StructuredAddressComposer#effectiveBuildingNumber}). Declaring it here is what moves it
   * to {@code optional} carrying {@code serverDefaulted: true} — "mandatory in Etendo but the
   * server already has a value, so do not ask the user" — rather than leaving it
   * indistinguishable from a field nobody needs.
   *
   * @return the server-resolved create fields; never {@code null}
   */
  @Override
  default Set<String> serverResolvedCreateFields() {
    return StructuredAddressComposer.serverDefaultedFields(GoAccountResolver.currentAccountEmail());
  }
}
