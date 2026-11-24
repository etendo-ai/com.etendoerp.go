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

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import com.etendoerp.go.schemaforge.util.NeoActionAvailability;
import com.etendoerp.go.schemaforge.util.NeoActionContract;

/**
 * The {@code post} / {@code unpost} contracts of the inventory documents — Internal Consumption,
 * Physical Inventory and Goods Movement (ETP-6406).
 *
 * <p>Why they are declared. The SPA books and unbooks these documents with the menu actions
 * {@code post} / {@code unpost}, served by
 * {@link com.etendoerp.go.schemaforge.handlers.DocumentPostingService}. Nothing declared them, so
 * {@code etendo_schema(view:"actions")} listed only the AD {@code Posted} button: a blind toggle
 * the SPA never uses, which skips the service's cost pre-check and reaches core's accounting
 * engine, whose cost error names no product ("The cost of the product product has not been
 * calculated.") and is logged as two ERRORs. Declaring them publishes the real actions with their
 * states, as {@link GoodsPostingContracts} does for the goods documents (ETP-5714); the entities'
 * {@code MCP_CONFIG} hides the {@code Posted} button.</p>
 *
 * <p>Two differences with the goods documents, which is why they are not reused as they are:</p>
 * <ul>
 *   <li>The state property. An Internal Consumption carries {@code status} (DR / CO / VO); a
 *       Physical Inventory and a Goods Movement only {@code processed}. None has
 *       {@code documentStatus}, which {@link DocumentAvailability#WHEN_POSTABLE} reads.</li>
 *   <li>{@code unpost} of a document that is not posted keeps the service's handled 200 with
 *       "0 entries removed" (ETP-5445); only the goods documents refuse it with a 422.</li>
 * </ul>
 *
 * <p>Declaring changes no routing: the handlers keep serving the actions through the posting
 * service, and REST and the SPA do not read the contracts.</p>
 */
public final class InventoryPostingContracts {

  /** {@code InternalConsumption.status}: draft, completed or voided. */
  static final String PROP_STATUS = "status";
  /** {@code processed} of a Physical Inventory or a Goods Movement, read as a string. */
  static final String PROP_PROCESSED = "processed";
  /** {@code processed} as the availability reader renders a {@code true} Boolean. */
  static final String PROCESSED_TRUE = "true";

  private static final String ID_DESCRIPTION = "the id of the document";
  private static final String NOT_POSTED = NeoActionAvailability.NOT_PREFIX
      + DocumentAvailability.POSTED_YES;

  /** Internal Consumption: availability read from {@code status = 'CO'}. */
  private static final Map<String, NeoActionContract> BY_STATUS = contracts(
      NeoActionAvailability.whenIn(PROP_STATUS, DocumentAvailability.STATUS_COMPLETED),
      "completed");
  /** Physical Inventory and Goods Movement: availability read from {@code processed}. */
  private static final Map<String, NeoActionContract> BY_PROCESSED = contracts(
      NeoActionAvailability.whenIn(PROP_PROCESSED, PROCESSED_TRUE), "processed");

  private InventoryPostingContracts() {
  }

  /**
   * The contracts of an Internal Consumption, whose state is {@code status}.
   *
   * @return {@code post} then {@code unpost}, unmodifiable
   */
  public static Map<String, NeoActionContract> byStatus() {
    return BY_STATUS;
  }

  /**
   * The contracts of a Physical Inventory or a Goods Movement, whose state is {@code processed}.
   *
   * @return {@code post} then {@code unpost}, unmodifiable
   */
  public static Map<String, NeoActionContract> byProcessed() {
    return BY_PROCESSED;
  }

  private static Map<String, NeoActionContract> contracts(NeoActionAvailability whenDone,
      String doneWord) {
    Map<String, NeoActionContract> contracts = new LinkedHashMap<>();
    contracts.put("post", NeoActionContract.write("post",
        "Books the document in the general ledger: creates its accounting entries and sets posted "
            + "to 'Y'. Exactly what the app's Post button does. Only for a " + doneWord
            + " document whose posted field is not 'Y': one that is not " + doneWord
            + " answers 422 with messageKeys ['ETGO_PostingDocumentNotProcessed'] and an already "
            + "posted one 422 with ['PostedDocument']; an incomplete accounting setup or a closed "
            + "period also answers 422 with the reason. Nothing changes on a refusal. Cost is "
            + "calculated by a background process about every 30 seconds, so posting right after "
            + "processing can answer 409 costNotCalculated with retryAfterSeconds 30 (nothing was "
            + "posted): wait that long and repeat the same call. If it answers 422 "
            + "costCalculationStalled (retryable false), costing is stuck, not slow: do not retry, "
            + "tell the user an administrator must check the Costing Background process. Takes no "
            + "parameters; never use the AD Posted button instead.")
        .withIdDescription(ID_DESCRIPTION)
        .availableWhen(whenDone.andWhen(DocumentAvailability.PROP_POSTED, NOT_POSTED)));
    contracts.put(InvoicePostingGate.ACTION_UNPOST, NeoActionContract.write(
        InvoicePostingGate.ACTION_UNPOST,
        "Removes the document's accounting entries (posted becomes 'N') and leaves it "
            + doneWord + ". Exactly what the app's Unpost button does. Meant for a document whose "
            + "posted field is 'Y': on one that is not posted it removes nothing and answers 200 "
            + "with \"Unposted (0 entries removed)\", so check posted first instead of reporting "
            + "that as an unpost. A period closed for unposting answers 422. Takes no parameters.")
        .withIdDescription(ID_DESCRIPTION)
        .availableWhen(whenDone.andWhen(DocumentAvailability.PROP_POSTED,
            DocumentAvailability.POSTED_YES)));
    return Collections.unmodifiableMap(contracts);
  }
}
