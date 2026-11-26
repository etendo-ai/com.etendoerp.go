/*
 *************************************************************************
 * The contents of this file are subject to the Etendo License
 * (the "License"), you may not use this file except in compliance
 * with the License.
 * You may obtain a copy of the License at
 * https://github.com/etendosoftware/etendo_core/blob/main/legal/Etendo_license.txt
 * Software distributed under the License is distributed on an
 * "AS IS" basis, WITHOUT WARRANTY OF ANY KIND, either express or
 * implied. See the License for the specific language governing rights
 * and limitations under the License.
 * All portions are Copyright (C) 2021-2026 FUTIT SERVICES, S.L
 * All Rights Reserved.
 * Contributor(s): Futit Services S.L.
 *************************************************************************
 */

package com.etendoerp.go.schemaforge;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static com.etendoerp.go.schemaforge.CurrencyTenancyTestSupport.currency;
import static com.etendoerp.go.schemaforge.CurrencyTenancyTestSupport.orgCurrency;
import static com.etendoerp.go.schemaforge.CurrencyTenancyTestSupport.otherClientId;
import static com.etendoerp.go.schemaforge.CurrencyTenancyTestSupport.rate;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.lang.reflect.Field;
import java.sql.SQLException;
import java.util.Map;

import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONException;
import org.codehaus.jettison.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.openbravo.base.weld.test.WeldBaseTest;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.ad.system.Client;
import org.openbravo.model.common.enterprise.Organization;
import org.openbravo.test.base.TestConstants;

import com.etendoerp.go.schemaforge.data.IncomingDocument;

/**
 * Real-database specs for which currencies the invoice currency selector offers to a tenant
 * ({@link CurrencyOptionsHandler#selectableCurrencies}) and for the {@code setCurrency} edit of the
 * invoice inbox ({@link InvoiceInboxApplyResolution#setCurrency}) that is built on it: against real
 * {@code c_currency} and {@code c_conversion_rate} rows, a currency of the session client or of
 * System with an active range to or from the organization currency is selectable, and nothing of
 * another client ever is.
 *
 * <p>Nothing is committed: currencies and ranges are inserted with native SQL on the DAL session's
 * own connection (the test transaction) and undone by the rollback.</p>
 *
 * <p>A new class, not an extension: {@code CurrencyOptionsHandlerTest} is a Mockito unit test and the
 * DB-backed specs need {@link WeldBaseTest}; {@code InvoiceInboxApplyTest} stubs the DAL, so it
 * cannot prove a tenant rule that lives in SQL. The existing DB-backed
 * {@code PurchaseInvoiceOcrLookupsTaxCurrencyIntegrationTest} could not host these cases either: its
 * unit under test is {@code PurchaseInvoiceOcrLookups} (its {@code @covers} and fixtures are the
 * OCR lookups'), while these specs cover {@code CurrencyOptionsHandler} and
 * {@code InvoiceInboxApplyResolution}, with their own cell-relevant fixtures. The fixtures they share
 * live in {@link CurrencyTenancyTestSupport}. The second-client fixture fails hard, never skips.</p>
 *
 * @covers com.etendoerp.go.schemaforge.CurrencyOptionsHandler
 * @covers com.etendoerp.go.schemaforge.InvoiceInboxApplyResolution
 */
public class CurrencyOptionsHandlerTenancyIntegrationTest extends WeldBaseTest {

  private static final String CLIENT_ID = TestConstants.Clients.FB_GRP;
  private static final String ORG_ID = TestConstants.Orgs.ESP;
  private static final String SIBLING_ORG_ID = TestConstants.Orgs.ESP_SUR;
  private static final String SYSTEM = "0";

  /** The currency of the F&B España organization, resolved as the production code resolves it. */
  private String orgCurrency;

  @Before
  public void prepareContext() {
    OBContext.clearAdminModeStack();
    OBContext.setOBContext(TestConstants.Users.ADMIN, TestConstants.Roles.FB_GRP_ADMIN, CLIENT_ID,
        ORG_ID);
    orgCurrency = orgCurrency(ORG_ID);
  }

  @After
  public void cleanUp() {
    OBDal.getInstance().rollbackAndClose();
    OBContext.setOBContext((OBContext) null);
  }

  // ---------------------------------------------------------------- selectableCurrencies

  @Test
  public void aCurrencyWithAnActiveRangeInEitherDirectionIsSelectable() throws SQLException {
    String outbound = currency("ZS1", "ZS1", CLIENT_ID, true);
    String inbound = currency("ZS2", "ZS2", CLIENT_ID, true);
    rate(orgCurrency, outbound, CLIENT_ID, SYSTEM, true);
    rate(inbound, orgCurrency, CLIENT_ID, SYSTEM, true);

    Map<String, String> offered = selectable();

    assertEquals("ZS1", offered.get(outbound));
    assertEquals("ZS2", offered.get(inbound));
  }

  @Test
  public void aCurrencyWithoutAnyRangeIsNotSelectable() throws SQLException {
    String bare = currency("ZS3", "ZS3", CLIENT_ID, true);

    assertFalse(selectable().containsKey(bare));
  }

  @Test
  public void anInactiveRangeOrAnInactiveCurrencyIsNotSelectable() throws SQLException {
    String inactiveRange = currency("ZS4", "ZS4", CLIENT_ID, true);
    String inactiveCurrency = currency("ZS5", "ZS5", CLIENT_ID, false);
    rate(orgCurrency, inactiveRange, CLIENT_ID, SYSTEM, false);
    rate(orgCurrency, inactiveCurrency, CLIENT_ID, SYSTEM, true);

    Map<String, String> offered = selectable();

    assertFalse(offered.containsKey(inactiveRange));
    assertFalse(offered.containsKey(inactiveCurrency));
  }

  @Test
  public void aCurrencyOfAnotherClientIsNeverSelectableEvenWithARangeOfTheSessionClient()
      throws SQLException {
    String other = otherClientId(CLIENT_ID);
    String theirs = currency("ZS6", "ZS6", other, true);
    rate(orgCurrency, theirs, CLIENT_ID, SYSTEM, true);
    rate(theirs, orgCurrency, CLIENT_ID, SYSTEM, true);
    rate(orgCurrency, theirs, other, SYSTEM, true);

    assertFalse(selectable().containsKey(theirs));
  }

  @Test
  public void aRangeOfAnotherClientDoesNotMakeACurrencySelectable() throws SQLException {
    String other = otherClientId(CLIENT_ID);
    String mine = currency("ZS7", "ZS7", CLIENT_ID, true);
    rate(orgCurrency, mine, other, SYSTEM, true);

    assertFalse(selectable().containsKey(mine));
  }

  @Test
  public void aSystemRangeAndASystemCurrencyAreSelectable() throws SQLException {
    String shared = currency("ZS8", "ZS8", SYSTEM, true);
    rate(orgCurrency, shared, SYSTEM, SYSTEM, true);

    assertEquals("ZS8", selectable().get(shared));
  }

  @Test
  public void aRangeOfTheDocumentOrganizationCountsButOneOfASiblingOrganizationDoesNot()
      throws SQLException {
    String ownOrg = currency("ZS9", "ZS9", CLIENT_ID, true);
    String siblingOrg = currency("ZT1", "ZT1", CLIENT_ID, true);
    rate(orgCurrency, ownOrg, CLIENT_ID, ORG_ID, true);
    rate(orgCurrency, siblingOrg, CLIENT_ID, SIBLING_ORG_ID, true);

    Map<String, String> offered = selectable();

    assertTrue(offered.containsKey(ownOrg));
    assertFalse(offered.containsKey(siblingOrg));
  }

  // ---------------------------------------------------------------- setCurrency

  @Test
  public void theStoredCurrencyIsAcceptedWithoutARangeAndBecomesTheUsersChoice() throws Exception {
    String stored = currency("ZU1", "ZU1", CLIENT_ID, true);
    Fixture f = new Fixture(stored);

    InvoiceInboxApplyResolution.setCurrency(f.edit, op(stored));

    assertEquals(stored, f.header.getString("currencyId"));
    assertEquals("manual", f.header.getString("currencySource"));
    assertFalse("choosing the stored currency is not an adjustment", f.edit.marked);
  }

  @Test
  public void aDifferentCurrencyWithoutARangeIsRefusedAsInvalid() throws Exception {
    String stored = currency("ZU2", "ZU2", CLIENT_ID, true);
    String bare = currency("ZU3", "ZU3", CLIENT_ID, true);
    Fixture f = new Fixture(stored);

    assertInvalidCurrency(f, bare);
  }

  @Test
  public void aDifferentCurrencyWithAConversionRangeIsAccepted() throws Exception {
    String stored = currency("ZU4", "ZU4", CLIENT_ID, true);
    String ranged = currency("ZU5", "ZU5", CLIENT_ID, true);
    rate(orgCurrency, ranged, CLIENT_ID, SYSTEM, true);
    Fixture f = new Fixture(stored);

    InvoiceInboxApplyResolution.setCurrency(f.edit, op(ranged));

    assertEquals(ranged, f.header.getString("currencyId"));
    assertEquals("manual", f.header.getString("currencySource"));
    assertTrue("a different currency is an adjustment", f.edit.marked);
  }

  @Test
  public void theOrganizationCurrencyIsAcceptedWithoutARange() throws Exception {
    Fixture f = new Fixture(currency("ZU6", "ZU6", CLIENT_ID, true));

    InvoiceInboxApplyResolution.setCurrency(f.edit, op(orgCurrency));

    assertEquals(orgCurrency, f.header.getString("currencyId"));
  }

  @Test
  public void anInactiveAnUnknownAndAnotherTenantsCurrencyAreRefused() throws Exception {
    String other = otherClientId(CLIENT_ID);
    String stored = currency("ZU7", "ZU7", CLIENT_ID, true);
    String inactive = currency("ZU8", "ZU8", CLIENT_ID, false);
    String theirs = currency("ZU9", "ZU9", other, true);
    rate(orgCurrency, inactive, CLIENT_ID, SYSTEM, true);
    rate(orgCurrency, theirs, CLIENT_ID, SYSTEM, true);
    rate(orgCurrency, theirs, other, SYSTEM, true);

    assertInvalidCurrency(new Fixture(stored), inactive);
    assertInvalidCurrency(new Fixture(stored), "NO-SUCH-CURRENCY");
    assertInvalidCurrency(new Fixture(stored), theirs);
  }

  @Test
  public void theStoredCurrencyIsStillRefusedWhenItIsInactiveOrOfAnotherTenant() throws Exception {
    String other = otherClientId(CLIENT_ID);
    String inactive = currency("ZV1", "ZV1", CLIENT_ID, false);
    String theirs = currency("ZV2", "ZV2", other, true);

    assertInvalidCurrency(new Fixture(inactive), inactive);
    assertInvalidCurrency(new Fixture(theirs), theirs);
  }

  // ---------------------------------------------------------------- helpers

  private Map<String, String> selectable() throws SQLException {
    return CurrencyOptionsHandler.selectableCurrencies(OBDal.getInstance().getConnection(),
        orgCurrency, CLIENT_ID, ORG_ID);
  }

  private static JSONObject op(String currencyId) throws JSONException {
    return new JSONObject().put("op", "setCurrency").put("currencyId", currencyId);
  }

  /** The refusal is a 422 {@code invalid_currency} and the document is left untouched. */
  private static void assertInvalidCurrency(Fixture f, String currencyId) throws Exception {
    String before = f.document.toString();
    try {
      InvoiceInboxApplyResolution.setCurrency(f.edit, op(currencyId));
      fail("expected a refusal for " + currencyId);
    } catch (InvoiceInboxApply.Refusal refusal) {
      Field field = InvoiceInboxApply.Refusal.class.getDeclaredField("response");
      field.setAccessible(true);
      NeoResponse response = (NeoResponse) field.get(refusal);
      assertEquals(422, response.getHttpStatus());
      assertEquals("invalid_currency",
          response.getBody().getJSONObject("error").getString("code"));
    }
    assertEquals("a refused edit must not touch the document", before, f.document.toString());
    assertFalse(f.edit.dirty);
  }

  /** A stored document whose header holds {@code storedCurrencyId}, of a row of F&B España. */
  private static final class Fixture {
    final JSONObject document;
    final JSONObject header;
    final InvoiceInboxApply.Edit edit;

    Fixture(String storedCurrencyId) throws JSONException {
      header = new JSONObject().put("currencyId", storedCurrencyId).put("currencySource",
          "default");
      document = new JSONObject().put("header", header).put("notices", new JSONArray());
      IncomingDocument row = mock(IncomingDocument.class);
      when(row.getClient()).thenReturn(OBDal.getInstance().get(Client.class, CLIENT_ID));
      when(row.getOrganization()).thenReturn(OBDal.getInstance().get(Organization.class, ORG_ID));
      edit = new InvoiceInboxApply.Edit(document, null, row);
    }
  }
}
