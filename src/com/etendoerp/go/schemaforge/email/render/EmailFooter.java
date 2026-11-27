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

package com.etendoerp.go.schemaforge.email.render;

import org.apache.commons.lang3.StringUtils;

/**
 * The "Creado con etendo.ai" footer of the emails that reach our customers' customers (ETP-6209):
 * the document email that carries a portal link, the portal link renewal and the accept / reject
 * confirmations. The seller's decision notice goes to our own customer and carries none.
 *
 * <p>An email carries it only when its contract asks for one with
 * {@link EmailContent.Builder#footer(EmailFooter)}; every other email renders exactly as before.
 * The markup lives in {@link EmailLayout}, like every other block.</p>
 *
 * <h2>Design (marketing / legal spec, ETP-6209 QA)</h2>
 * <p>One small, discreet line: «Creado con» followed by the horizontal etendo.ai logo
 * ({@value #LOGO_URL}, published by marketing, drawn at {@value #LOGO_WIDTH}×{@value #LOGO_HEIGHT})
 * with {@value #BRAND} as its alt text, so the brand still reads in a client that blocks remote
 * images. Words and logo are one link — no button, no «Conoce más», no social links, no privacy
 * link. The markup lives in {@code EmailLayout#appendFooter} only.</p>
 *
 * <h2>Link</h2>
 * <p>The etendo.ai home page with the UTM of the email it goes out in. The UTM is declared per
 * {@link Piece}, and each contract passes its own piece, so the shared layout never names a
 * contract.</p>
 */
public final class EmailFooter {

  /** The brand as the footer writes it: lower case, the alt text of the logo. */
  public static final String BRAND = "etendo.ai";
  /** The page the brand links to. */
  static final String HOME_URL = "https://etendo.ai/";
  /** The horizontal etendo.ai logo, published by marketing for emails (200×30, drawn at half). */
  public static final String LOGO_URL =
      "https://etendo.ai/wp-content/uploads/2026/11/etendoai-logo-email-200.png";
  /** The width the logo is drawn at, in pixels. */
  public static final int LOGO_WIDTH = 100;
  /** The height the logo is drawn at, in pixels. */
  public static final int LOGO_HEIGHT = 15;
  /** The UTM every customer email shares; each piece adds its own {@code utm_content}. */
  static final String TRACKING_PREFIX =
      "utm_source=portal_clientes&utm_medium=email&utm_campaign=portal_clientes&utm_content=";

  private static final String KEY_PREFIX = "footer.";

  /** The email a footer goes out in: its {@code utm_content}, verbatim from marketing. */
  public enum Piece {
    /** A sales document email carrying a customer portal link. */
    DOCUMENT_SEND("mail_envio_documento"),
    /** The renewed customer portal link. */
    LINK_RENEWAL("mail_reenvio_enlace"),
    /** The confirmation of a quotation acceptance requested in the portal. */
    DECISION_ACCEPT("mail_aceptacion"),
    /** The confirmation of a quotation rejection requested in the portal. */
    DECISION_REJECT("mail_rechazo");

    private final String utmContent;

    Piece(String utmContent) {
      this.utmContent = utmContent;
    }
  }

  private final String prefix;
  private final String url;

  private EmailFooter(Piece piece, String language) {
    this.prefix = EmailMessages.get(KEY_PREFIX + "prefix", language);
    this.url = link(piece);
  }

  /**
   * The footer of one email, in the recipient's language.
   *
   * @param piece the email it goes out in
   * @param language the recipient language
   * @return the footer
   */
  public static EmailFooter etendo(Piece piece, String language) {
    return new EmailFooter(piece, language);
  }

  /**
   * The home page link with the piece's UTM; the bare home page without a piece.
   *
   * @param piece the email the link goes out in, may be {@code null}
   * @return the link to emit
   */
  static String link(Piece piece) {
    return piece == null || StringUtils.isBlank(piece.utmContent) ? HOME_URL
        : HOME_URL + "?" + TRACKING_PREFIX + piece.utmContent;
  }

  /** @return the words before the brand: «Creado con» / «Built with» */
  public String getPrefix() {
    return prefix;
  }

  /** @return the logo drawn after the prefix, {@value #LOGO_URL} */
  public String getLogoUrl() {
    return LOGO_URL;
  }

  /** @return the brand, {@value #BRAND}: the logo's alt text */
  public String getBrand() {
    return BRAND;
  }

  /** @return where the footer links to: the home page with the piece's UTM */
  public String getUrl() {
    return url;
  }
}
