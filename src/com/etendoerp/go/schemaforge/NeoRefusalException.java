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

import org.codehaus.jettison.json.JSONObject;

/**
 * A keyed business refusal that carries the whole answer it stands for — HTTP status and body,
 * extra keys included — for a refusal raised deep inside a flow that can only report by throwing.
 *
 * <p>A caller that knows the type answers {@link #getResponse()} verbatim (for instance
 * {@code FinancialAccountTransactionsSupport.runMutation}). Any other caller still sees a
 * {@link MessageKeyedException}: its generic {@code catch (OBException)} answers the same text and
 * {@code messageKeys}, only with its own status and without the extra keys.
 */
public class NeoRefusalException extends MessageKeyedException {

  private static final long serialVersionUID = 1L;

  /** Not serialized: the response is only meaningful inside the request that built it. */
  private final transient NeoResponse response;

  /**
   * Creates a refusal that carries the response to answer and the message key it was built from.
   *
   * @param response   the refusal to answer, built with {@link NeoMessageText#error}
   * @param messageKey its {@code AD_MESSAGE.VALUE}
   */
  public NeoRefusalException(NeoResponse response, String messageKey) {
    super(new ResolvedText(messageOf(response)), messageKey);
    this.response = response;
  }

  /** @return the refusal exactly as it must be answered */
  public NeoResponse getResponse() {
    return response;
  }

  private static String messageOf(NeoResponse response) {
    JSONObject body = response == null ? null : response.getBody();
    JSONObject error = body == null ? null : body.optJSONObject("error");
    return error == null ? null : error.optString("message", null);
  }
}
