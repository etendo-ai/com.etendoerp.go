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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Unit tests for {@link EmailLayout}, the single place in the module allowed to emit email markup
 * (ETP-5003).
 *
 * <p>The ETP-6209 card ({@link EmailCardLayout}) and Etendo footer ({@link EmailFooter}) are drawn
 * only through {@link EmailLayout#render}, so their rendering rules are tested here too.
 *
 * @covers com.etendoerp.go.schemaforge.email.render.EmailLayout
 * @covers com.etendoerp.go.schemaforge.email.render.EmailCardLayout
 * @covers com.etendoerp.go.schemaforge.email.render.EmailFooter
 */
public class EmailLayoutTest {

  private static EmailContent.Builder minimal() {
    return EmailContent.builder().paragraph("Cuerpo del mensaje");
  }

  @Test
  public void rendersACompleteDocumentBecauseTheProviderWrapsNothing() {
    String html = EmailLayout.render(minimal().build());

    assertTrue(html.startsWith("<!DOCTYPE html>"));
    assertTrue(html.trim().endsWith("</html>"));
    assertTrue(html.contains("<body"));
  }

  @Test
  public void carriesTheLogoAsImagePlusLiveText() {
    String html = EmailLayout.render(minimal().build());

    assertTrue(html.contains(EmailLayout.LOGO_URL));
    assertTrue(html.contains("alt=\"Etendo\""));
    // The wordmark must be text, not part of the image: it has to survive a client that blocks
    // remote images.
    assertTrue(html.contains(">Etendo</td>"));
  }

  @Test
  public void pinsTheLogoToProductionRegardlessOfEnvironment() {
    assertEquals("https://app.etendo.ai/favicon.png", EmailLayout.LOGO_URL);
  }

  @Test
  public void emitsBothPalettes() {
    String html = EmailLayout.render(minimal().build());

    assertTrue(html.contains(EmailPalette.LIGHT_CARD_BACKGROUND));
    assertTrue(html.contains("@media (prefers-color-scheme: dark)"));
    assertTrue(html.contains(EmailPalette.DARK_CARD_BACKGROUND));
  }

  @Test
  public void escapesUntrustedText() {
    String html = EmailLayout.render(
        EmailContent.builder().paragraph("Tom & Jerry <script>alert(1)</script>").build());

    assertFalse(html.contains("<script>"));
    assertTrue(html.contains("Tom &amp; Jerry &lt;script&gt;"));
  }

  @Test
  public void leavesPreEscapedMarkupAlone() {
    String html = EmailLayout.render(
        EmailContent.builder().paragraphHtml("Hola <strong>ACME</strong>").build());

    assertTrue(html.contains("Hola <strong>ACME</strong>"));
  }

  @Test
  public void rendersTheCtaWithAnOutlookFallback() {
    String html = EmailLayout.render(
        minimal().cta("Aceptar invitación", "https://go.etendo.cloud/invite?token=abc").build());

    assertTrue(html.contains("v:roundrect"));
    assertTrue(html.contains("<!--[if mso]>"));
    assertTrue(html.contains("href=\"https://go.etendo.cloud/invite?token=abc\""));
  }

  @Test
  public void omitsTheCtaBlockWhenEitherHalfIsMissing() {
    assertFalse(EmailLayout.render(minimal().cta("Aceptar", null).build()).contains("v:roundrect"));
    assertFalse(EmailLayout.render(minimal().cta(null, "https://x.test").build())
        .contains("v:roundrect"));
  }

  @Test
  public void omitsTheLinkFallbackWhenThereIsNoCta() {
    String html = EmailLayout.render(minimal().linkFallbackText("Si el botón no funciona:").build());

    assertFalse(html.contains("Si el botón no funciona:"));
  }

  @Test
  public void rendersNotesAndSignature() {
    String html = EmailLayout.render(
        minimal().note("Válido 7 días.").signature("Saludos, Equipo de Etendo").build());

    assertTrue(html.contains("Válido 7 días."));
    assertTrue(html.contains("Saludos, Equipo de Etendo"));
  }

  @Test
  public void keepsTheDocumentWellUnderTheGmailClippingThreshold() {
    String html = EmailLayout.render(minimal()
        .greetingHtml("Hola, <strong>Santiago</strong>:")
        .cta("Aceptar invitación", "https://go.etendo.cloud/invite?token=abc")
        .linkFallbackText("Si el botón no funciona, copia el enlace:")
        .note("Válido 7 días.")
        .signature("Saludos, Equipo de Etendo")
        .build());

    // Gmail clips around 102KB. A layout that ever approaches it has grown a bug, not a feature.
    assertTrue("layout grew to " + html.length() + " chars", html.length() < 20000);
  }

  @Test
  public void rendersTheSummaryBlockAsATwoColumnTable() {
    String html = EmailLayout.render(minimal()
        .detail("Factura de venta", "10000016")
        .detail("Fecha", "26/08/2026")
        .build());

    assertTrue(html.contains(">Factura de venta</td>"));
    assertTrue(html.contains(">10000016</td>"));
    assertTrue(html.contains(">Fecha</td>"));
    assertTrue(html.contains(">26/08/2026</td>"));
    // The value column is right-aligned through the cell attribute: Word's engine ignores
    // text-align on a block, so the attribute is what actually holds the layout together.
    assertTrue(html.contains("align=\"right\""));
  }

  @Test
  public void omitsTheSummaryBlockWhenThereAreNoRows() {
    String html = EmailLayout.render(minimal().build());

    assertFalse(html.contains("border-collapse:collapse"));
  }

  @Test
  public void dropsASummaryRowWhoseValueIsMissing() {
    // A document without a due date must not print the label with nothing beside it.
    String html = EmailLayout.render(minimal()
        .detail("Vencimiento", null)
        .detail("Total", "133,10 EUR")
        .build());

    assertFalse(html.contains("Vencimiento"));
    assertTrue(html.contains(">133,10 EUR</td>"));
  }

  @Test
  public void escapesBothSidesOfASummaryRow() {
    String html = EmailLayout.render(minimal()
        .detail("<b>Label</b>", "<script>alert(1)</script>")
        .build());

    assertFalse(html.contains("<script>"));
    assertTrue(html.contains("&lt;script&gt;"));
    assertTrue(html.contains("&lt;b&gt;Label&lt;/b&gt;"));
  }

  // ── ETP-6209: the «Creado con etendo.ai» footer and the decision card ─────────────────────────

  /**
   * A standard letter (no title, no footer) carries none of the ETP-6209 markup, and asking for the
   * footer only inserts it: removing the inserted run gives back the letter byte for byte. This
   * stands in for a byte-for-byte comparison with the pre-change output, which cannot be captured
   * without running the old code; the refactor of the letter itself only swapped literals for
   * constants of the same value.
   */
  @Test
  public void theFooterIsPurelyAdditiveAndAStandardLetterCarriesNoneOfTheNewMarkup() {
    EmailFooter footer = EmailFooter.etendo(EmailFooter.Piece.DOCUMENT_SEND, "es_ES");
    String plain = EmailLayout.render(letter().build());
    String withFooter = EmailLayout.render(letter().footer(footer).build());

    // Only markup that did not exist before the card and footer (the letter already used sf-card).
    // (Not the bare brand: the letter's own logo is hosted on app.etendo.ai.)
    for (String marker : new String[] { EmailLayout.CARD_FONT_STACK, footer.getPrefix(),
        EmailEscape.escapeHtml(footer.getUrl()) }) {
      assertFalse(marker, plain.contains(marker));
    }
    String footerMarkup = insertedRun(plain, withFooter);
    assertTrue(footerMarkup, footerMarkup.contains(footerAnchor(footer, "Creado con")));
  }

  /**
   * The marketing / legal spec (ETP-6209 QA): one discreet line, «Creado con» and the etendo.ai
   * logo (100×15, alt {@value EmailFooter#BRAND}) wrapped by one single anchor to the email's UTM
   * link — no button, no second link (the old «Conoce más» pill, the site row and the privacy row
   * are gone), in the recipient's language.
   */
  @Test
  public void theFooterIsOneLinkWrappingTheWordsAndTheLogoWithNoButtonOrPrivacyRow() {
    // Columns: language, words before the logo.
    String[][] cases = { { "es_ES", "Creado con" }, { "en_US", "Built with" } };
    for (String[] c : cases) {
      EmailFooter footer = EmailFooter.etendo(EmailFooter.Piece.LINK_RENEWAL, c[0]);
      String plain = EmailLayout.render(minimal().build());
      String footerMarkup = insertedRun(plain, EmailLayout.render(minimal().footer(footer)
          .build()));

      assertEquals(c[0], c[1], footer.getPrefix());
      assertTrue(c[0] + ": " + footerMarkup, footerMarkup.contains(footerAnchor(footer, c[1])));
      assertEquals(c[0] + ": one link only", 1, footerMarkup.split("<a ", -1).length - 1);
      assertEquals(c[0] + ": one image only", 1, footerMarkup.split("<img ", -1).length - 1);
      assertTrue(c[0] + ": 14px words in the footer colour", footerMarkup.contains(
          "font-size:14px;line-height:20px;font-weight:400;color:#4B5563;"));
      assertFalse(c[0], footerMarkup.contains("v:roundrect"));
      assertFalse(c[0], footerMarkup.toLowerCase().contains("privac"));
      assertFalse(c[0], footerMarkup.contains("border-radius"));
    }
  }

  /** The logo is the asset marketing published for emails, drawn at half its 200×30 size. */
  @Test
  public void theFooterLogoIsTheMarketingAssetAtHalfSize() {
    assertEquals("https://etendo.ai/wp-content/uploads/2026/11/etendoai-logo-email-200.png",
        EmailFooter.LOGO_URL);
    assertEquals(100, EmailFooter.LOGO_WIDTH);
    assertEquals(15, EmailFooter.LOGO_HEIGHT);
    assertEquals(EmailFooter.LOGO_URL,
        EmailFooter.etendo(EmailFooter.Piece.DOCUMENT_SEND, "es_ES").getLogoUrl());
  }

  /**
   * Each email's brand link is the etendo.ai home page with marketing's UTM, whose
   * {@code utm_content} names that email; without a piece, the bare home page.
   */
  @Test
  public void eachPiecesFooterLinkCarriesItsOwnUtmContent() {
    String utm = "https://etendo.ai/?utm_source=portal_clientes&utm_medium=email"
        + "&utm_campaign=portal_clientes&utm_content=";
    // Columns: piece, utm_content.
    Object[][] cases = { { EmailFooter.Piece.DOCUMENT_SEND, "mail_envio_documento" },
        { EmailFooter.Piece.LINK_RENEWAL, "mail_reenvio_enlace" },
        { EmailFooter.Piece.DECISION_ACCEPT, "mail_aceptacion" },
        { EmailFooter.Piece.DECISION_REJECT, "mail_rechazo" } };
    assertEquals("every piece is pinned", EmailFooter.Piece.values().length, cases.length);
    for (Object[] c : cases) {
      EmailFooter.Piece piece = (EmailFooter.Piece) c[0];

      assertEquals(piece.name(), utm + c[1], EmailFooter.etendo(piece, "en_US").getUrl());
    }
    assertEquals("https://etendo.ai/", EmailFooter.link(null));
  }

  /** The footer's single anchor: the words and the logo, linked to the piece's UTM URL. */
  private static String footerAnchor(EmailFooter footer, String prefix) {
    return "<a href=\"" + EmailEscape.escapeHtml(footer.getUrl()) + "\" style=\"color:"
        + EmailLayout.FOOTER_TEXT_COLOR + ";text-decoration:none;\">" + prefix + "&nbsp;"
        + "<img src=\"" + EmailFooter.LOGO_URL + "\" width=\"100\" height=\"15\" alt=\"etendo.ai\" "
        + "style=\"width:100px;height:15px;border:0;vertical-align:baseline;\">"
        + "</a>";
  }

  /** The run of markup {@code longer} has on top of {@code shorter}, which must be all it adds. */
  private static String insertedRun(String shorter, String longer) {
    int prefix = 0;
    while (prefix < shorter.length() && shorter.charAt(prefix) == longer.charAt(prefix)) {
      prefix++;
    }
    int inserted = longer.length() - shorter.length();
    assertTrue(inserted > 0);
    assertEquals("the footer only inserts markup", shorter,
        longer.substring(0, prefix) + longer.substring(prefix + inserted));
    return longer.substring(prefix, prefix + inserted);
  }

  @Test
  public void theCardDeclaresTheEmailsPrimaryLanguageAndFallsBackToSpanish() {
    // Columns: content language, lang attribute.
    String[][] cases = { { "es_ES", "es" }, { "en_US", "en" }, { "en-US", "en" }, { "FR", "fr" },
        { null, "es" }, { "", "es" }, { "1x_ES", "es" }, { "e$_ES", "es" } };
    for (String[] c : cases) {
      String html = EmailLayout.render(EmailContent.builder().title("Confirma")
          .language(c[0]).build());

      assertTrue(c[0] + ": " + html.substring(0, 60),
          html.contains("<html lang=\"" + c[1] + "\">"));
    }
  }

  /**
   * The card's body and closing notes (ETP-6209 QA CP-48, the document email with a portal link):
   * plain text is escaped, a paragraph its author already escaped is kept, and the blocks follow
   * the design's order — headline, body, button, notes and signature.
   */
  @Test
  public void theCardEscapesItsBodyAndNotesAndKeepsTheirOrder() {
    String html = EmailLayout.render(EmailContent.builder().title("Factura F-1")
        .greeting("Hola <b>Ana</b>")
        .paragraph("1 < 2 & <script>x</script>")
        .paragraphHtml("Te enviamos tu <strong>Factura</strong>.")
        .cta("Ver documento", "https://app.example.test/portal/C/tok")
        .note("<i>nota</i>")
        .signature("Equipo & Co")
        .build());

    assertFalse(html, html.contains("<script>"));
    assertTrue(html, html.contains(">Hola &lt;b&gt;Ana&lt;/b&gt;</p>"));
    assertTrue(html, html.contains(">1 &lt; 2 &amp; &lt;script&gt;x&lt;/script&gt;</p>"));
    assertTrue(html, html.contains(">Te enviamos tu <strong>Factura</strong>.</p>"));
    assertTrue(html, html.contains(">&lt;i&gt;nota&lt;/i&gt;</p>"));
    assertTrue(html, html.contains(">Equipo &amp; Co</p>"));
    int title = html.indexOf(">Factura F-1</h1>");
    int body = html.indexOf("Hola &lt;b&gt;");
    int button = html.indexOf(">Ver documento</a>");
    int notes = html.indexOf("&lt;i&gt;nota");
    assertTrue(title + " < " + body + " < " + button + " < " + notes,
        title > 0 && title < body && body < button && button < notes);
  }

  @Test
  public void aLogoIsFittedInsideTheAvatarKeepingItsAspect() {
    // Columns: natural width, natural height, fitted width, fitted height (44px diagonal).
    int[][] cases = { { 100, 100, 31, 31 }, { 300, 100, 42, 14 }, { 100, 300, 14, 42 },
        { 0, 0, 31, 31 }, { -5, 40, 31, 31 } };
    for (int[] c : cases) {
      int[] fitted = EmailCardLayout.fitInAvatar(c[0], c[1]);

      assertEquals(c[0] + "x" + c[1], c[2] + "x" + c[3], fitted[0] + "x" + fitted[1]);
    }
  }

  private static EmailContent.Builder letter() {
    return EmailContent.builder().greeting("Hola Ana,").paragraph("Te enviamos la factura.")
        .detail("Total", "133,10 EUR").cta("Ver factura", "https://app.example.test/doc")
        .linkFallbackText("Si el botón no funciona:").note("Nota").signature("Acme");
  }
}
