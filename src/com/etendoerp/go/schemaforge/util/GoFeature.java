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

import java.util.Arrays;
import java.util.Collections;
import java.util.Set;

/**
 * Declaration of an Etendo GO feature that has no classic window or process of its own (ETP-4802).
 *
 * <p>A feature is granted to a role through a row of the <i>GO Access</i> subtab of the Role window
 * ({@code ETGO_Go_Access}); {@link #id} is the row's {@code Feature} value (list reference
 * {@code ETGO_GoFeature}, whose list values must match {@link #ids()}).</p>
 *
 * <p><b>Legacy grant.</b> Until a role has a GO Access row for the feature, access keeps coming from
 * the classic record the feature was proxied through ({@link #legacyKind} / {@link #legacyId}), so
 * no role gains or loses access by this change. A GO Access row, when present, replaces that grant
 * for its role (an inactive row is ignored).</p>
 *
 * <p><b>Support tables.</b> {@link #supportTableIds} are the tables the feature writes attachments
 * through; {@code NeoAttachmentAuthorizer} resolves table to feature to access.</p>
 */
public enum GoFeature {
  /** "Modelos fiscales" (303/349), granted through the Tax Report window. */
  FISCAL_MODELS("fiscal-models", LegacyKind.WINDOW, "3E8FEA1EA7404D979306C9EE7FD2E7E8",
      // ETGO_Fiscal_Decl
      "F8B5925363A64980B7522FFD98A1628B"),
  /** "Monitor fiscal", granted through the SII Monitor window. */
  FISCAL_MONITOR("fiscal-monitor", LegacyKind.WINDOW, "FEF76C3E0F104F06A89AAD15A4A4A35C"),
  /** "Documentos no contabilizados", granted through an OBUIAPP process. */
  NOT_POSTED_DOCUMENTS("not-posted-documents", LegacyKind.OBUIAPP_PROCESS,
      "D6AB95CE52D34E1599590526115E26C6"),
  /** "Informes financieros", granted through an anchor pseudo-window. */
  FINANCIAL_REPORTS("financial-reports", LegacyKind.WINDOW, "D647D118F5014D00AF47A636B2CD0DD3"),
  /** "Escaneo inteligente", granted through an anchor pseudo-window. */
  SMART_SCAN("smart-scan", LegacyKind.WINDOW, "33705E0F52874D91B0BB2FF8BB648B8E"),
  /** "Informes de inventario", granted through an anchor pseudo-window. */
  INVENTORY_STOCK_REPORT("inventory-stock-report", LegacyKind.WINDOW,
      "6346B88619F948F9A42224BDB0B239FA");

  // TODO(ETP-4802): "tax-report" is deliberately NOT a feature yet. It has no AD_Process and no
  // AD_Tab_ID, so NeoAccessHelper#hasAccessToConstituentWindows lets every authenticated role
  // through (permissive fallback). Keeping that behaviour here is intentional; its grant source
  // needs a functional decision before it can be declared as a feature (and the fallback closed).

  /** Where the pre-GO-Access grant of a feature lives. */
  public enum LegacyKind {
    WINDOW, OBUIAPP_PROCESS
  }

  /** The {@code Feature} value of the GO Access row. */
  public final String id;
  /** Kind of the classic record the feature was proxied through. */
  public final LegacyKind legacyKind;
  /** Id of the classic record the feature was proxied through. */
  public final String legacyId;
  /** {@code AD_Table_ID}s the feature writes attachments through. */
  public final Set<String> supportTableIds;

  GoFeature(String id, LegacyKind legacyKind, String legacyId, String... supportTableIds) {
    this.id = id;
    this.legacyKind = legacyKind;
    this.legacyId = legacyId;
    this.supportTableIds = Set.of(supportTableIds);
  }

  /**
   * @param tableId an {@code AD_Table_ID}
   * @return the feature that declares {@code tableId} as a support table, or {@code null}
   */
  public static GoFeature forSupportTable(String tableId) {
    return Arrays.stream(values()).filter(f -> f.supportTableIds.contains(tableId)).findFirst()
        .orElse(null);
  }

  /**
   * @param legacyId id of a classic window or process
   * @return the feature currently granted through that record, or {@code null}
   */
  public static GoFeature forLegacyId(String legacyId) {
    return Arrays.stream(values()).filter(f -> f.legacyId.equals(legacyId)).findFirst()
        .orElse(null);
  }

  /**
   * @param id a GO Access {@code Feature} value
   * @return the matching feature, or {@code null}
   */
  public static GoFeature forId(String id) {
    return Arrays.stream(values()).filter(f -> f.id.equals(id)).findFirst().orElse(null);
  }

  /** @return the immutable set of every declared feature id. */
  public static Set<String> ids() {
    return Collections.unmodifiableSet(
        Arrays.stream(values()).map(f -> f.id).collect(java.util.stream.Collectors.toSet()));
  }
}
