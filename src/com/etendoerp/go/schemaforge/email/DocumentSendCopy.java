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

package com.etendoerp.go.schemaforge.email;

import com.etendoerp.go.schemaforge.email.render.EmailMessages;

/**
 * Catalog keys of the document emails' copy that a contract may override in the catalog.
 */
final class DocumentSendCopy {

  private static final String SHARED_BODY = "document.body";

  private DocumentSendCopy() {
  }

  /**
   * The catalog key of the body sentence: the contract's own {@code <contract>.body} when the
   * catalog declares one, else the shared {@code document.body}. Declared in the catalog, never
   * branched on in code, like the document type label: the supplier-facing contracts keep the
   * formal wording while the customer-facing ones address the customer as «tú» (ETP-6209 QA).
   *
   * @param contractName the sending contract
   * @param language the recipient language
   * @return the key to read the body sentence from
   */
  static String bodyKey(String contractName, String language) {
    String own = contractName + ".body";
    return EmailMessages.getOptional(own, language) != null ? own : SHARED_BODY;
  }
}
