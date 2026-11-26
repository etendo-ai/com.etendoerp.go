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

import java.util.Map;

import org.openbravo.base.exception.OBException;
import org.openbravo.erpCommon.utility.OBMessageUtils;

/**
 * A business refusal that also carries its {@code AD_MESSAGE} identity, so the error response can
 * publish it in {@code messageKeys} and the SPA can react to the identity rather than the
 * (locale-dependent) prose. See {@link NeoResponse#error(int, OBException)}.
 */
public class MessageKeyedException extends OBException {

  private static final long serialVersionUID = 1L;

  private final String messageKey;

  /**
   * Creates the exception with the message's text in the GO locale.
   *
   * @param messageKey the {@code AD_MESSAGE.VALUE}
   */
  public MessageKeyedException(String messageKey) {
    super(OBMessageUtils.messageBD(messageKey));
    this.messageKey = messageKey;
  }

  /**
   * Creates the exception with the message's text in the GO locale, its {@code @name@}
   * placeholders replaced from {@code params}.
   *
   * @param messageKey the {@code AD_MESSAGE.VALUE}
   * @param params     placeholder name (without the {@code @}) to value
   */
  public MessageKeyedException(String messageKey, Map<String, String> params) {
    super(OBMessageUtils.parseTranslation(OBMessageUtils.messageBD(messageKey), params));
    this.messageKey = messageKey;
  }

  /** Text the caller already resolved, kept apart from the key in the constructor signature. */
  record ResolvedText(String value) {
  }

  /** Carries text the caller already resolved: {@link #withArgs}, {@link NeoRefusalException}. */
  MessageKeyedException(ResolvedText text, String messageKey) {
    super(text.value());
    this.messageKey = messageKey;
  }

  /**
   * The exception for a message of this module whose placeholders are positional ({@code %0..%n}),
   * worded through {@link NeoMessageText#text(String, Object...)} so the Spanish catalog applies
   * where {@code AD_MESSAGE_TRL} is not exported (see {@link NeoMessageText}).
   *
   * @param messageKey the {@code AD_MESSAGE.VALUE}
   * @param args       the placeholder values, in order
   * @return the exception, carrying {@code messageKey} as its identity
   */
  public static MessageKeyedException withArgs(String messageKey, Object... args) {
    return new MessageKeyedException(new ResolvedText(NeoMessageText.text(messageKey, args)),
        messageKey);
  }

  public String getMessageKey() {
    return messageKey;
  }
}
