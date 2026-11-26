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

import static com.etendoerp.go.schemaforge.CurrencyTenancyTestSupport.orgCurrency;
import static com.etendoerp.go.schemaforge.CurrencyTenancyTestSupport.otherClientId;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;

import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONObject;
import org.hibernate.query.NativeQuery;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.openbravo.base.weld.test.WeldBaseTest;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBDal;
import org.openbravo.test.base.TestConstants;

import com.etendoerp.go.schemaforge.selector.policy.ProductPriceSelectorPolicy;

/**
 * Real-database specs for the price list the invoice inbox prices a manual line with (ETP-6051):
 * {@link InvoiceInboxPriceList#forVendor} (the vendor's purchase list, else the default purchase
 * list, first by name, then id, never another tenant's, never an inactive one) and
 * {@link ProductPriceSelectorPolicy#standardPrice} (the latest ACTIVE version whose validity is not
 * after NOW(), the product's active row), against real {@code m_pricelist}, {@code m_pricelist_version}
 * and {@code m_productprice} rows. The tenant rule and the version rule live in SQL / the DAL filters,
 * so a Mockito test cannot prove them.
 *
 * <p>Nothing is committed: every fixture is inserted (and the pre-existing default purchase lists
 * are demoted) with native SQL on the DAL session's own connection and undone by the rollback.</p>
 *
 * <p>A new class, not an extension: the existing tests of both units are Mockito unit tests
 * ({@code ProductPriceSelectorPolicyTest}, {@code InvoiceInboxConfirmChecksTest}) and
 * {@code PurchaseInvoiceOcrLookupsTaxCurrencyIntegrationTest} is about the tax and currency
 * lookups, with other fixtures; price lists have none of them. Both units are covered here because
 * they share the price-list fixtures.</p>
 *
 * @covers com.etendoerp.go.schemaforge.InvoiceInboxPriceList
 * @covers com.etendoerp.go.schemaforge.selector.policy.ProductPriceSelectorPolicy
 */
public class InvoiceInboxPriceListIntegrationTest extends WeldBaseTest {

  private static final String CLIENT_ID = TestConstants.Clients.FB_GRP;
  private static final String ORG_ID = TestConstants.Orgs.ESP;

  private final String marker = "etgo-pl-" + UUID.randomUUID().toString().substring(0, 8);

  private String currencyId;
  private String schemaId;
  private String productId;
  private String vendorId;

  @Before
  public void prepareContext() {
    OBContext.clearAdminModeStack();
    OBContext.setOBContext(TestConstants.Users.ADMIN, TestConstants.Roles.FB_GRP_ADMIN, CLIENT_ID,
        ORG_ID);
    currencyId = orgCurrency(ORG_ID);
    schemaId = scalar("SELECT m_discountschema_id FROM m_discountschema WHERE ad_client_id = '"
        + CLIENT_ID + "' AND isactive = 'Y' ORDER BY m_discountschema_id LIMIT 1");
    productId = scalar("SELECT m_product_id FROM m_product WHERE ad_client_id = '" + CLIENT_ID
        + "' AND isactive = 'Y' ORDER BY m_product_id LIMIT 1");
    vendorId = scalar("SELECT c_bpartner_id FROM c_bpartner WHERE ad_client_id = '" + CLIENT_ID
        + "' AND isvendor = 'Y' AND isactive = 'Y' ORDER BY c_bpartner_id LIMIT 1");
    // The data already has default purchase lists: demote them, so the fixtures decide the answer.
    execute("UPDATE m_pricelist SET isdefault = 'N' WHERE issopricelist = 'N' AND isdefault = 'Y'");
  }

  @After
  public void cleanUp() {
    OBDal.getInstance().rollbackAndClose();
    OBContext.setOBContext((OBContext) null);
  }

  // ---------------------------------------------------------------- forVendor

  @Test
  public void theVendorPurchaseListWinsOverTheDefaultOne() {
    String own = priceList("own", CLIENT_ID, true, false, false);
    priceList("default", CLIENT_ID, true, true, false);
    vendorUses(own);

    InvoiceInboxPriceList.Resolved resolved = InvoiceInboxPriceList.forVendor(vendorId);

    assertEquals(own, resolved.id());
    assertFalse(resolved.includesTax());
  }

  @Test
  public void aTaxIncludedListIsReportedAsSuch() {
    vendorUses(priceList("gross", CLIENT_ID, true, false, true));

    assertTrue(InvoiceInboxPriceList.forVendor(vendorId).includesTax());
  }

  @Test
  public void aVendorWithoutAListGetsTheDefaultPurchaseList() {
    String fallback = priceList("default", CLIENT_ID, true, true, false);
    vendorUses(null);

    assertEquals(fallback, InvoiceInboxPriceList.forVendor(vendorId).id());
  }

  @Test
  public void aNewVendorABlankOrAnUnknownOneGetsTheDefaultPurchaseList() {
    String fallback = priceList("default", CLIENT_ID, true, true, false);

    assertEquals(fallback, InvoiceInboxPriceList.forVendor(null).id());
    assertEquals(fallback, InvoiceInboxPriceList.forVendor("").id());
    assertEquals(fallback, InvoiceInboxPriceList.forVendor("NO-SUCH-VENDOR").id());
  }

  @Test
  public void withNoDefaultAndNoVendorListThereIsNoneAndNoException() {
    vendorUses(null);

    assertNull(InvoiceInboxPriceList.forVendor(vendorId));
    assertNull(InvoiceInboxPriceList.forVendor(null));
  }

  @Test
  public void aSalesListIsNeverTheDefaultPurchaseList() {
    priceList("sales", CLIENT_ID, true, true, false, true);

    assertNull(InvoiceInboxPriceList.forVendor(null));
  }

  @Test
  public void severalDefaultsAreResolvedByNameSoTheAnswerIsStable() {
    // inserted in reverse order on purpose; a name is unique per client, so the id never ties
    priceList("c-name", CLIENT_ID, true, true, false);
    String a = priceList("a-name", CLIENT_ID, true, true, false);
    priceList("b-name", CLIENT_ID, true, true, false);

    assertEquals(a, InvoiceInboxPriceList.forVendor(null).id());
    assertEquals("the answer does not change between calls", a,
        InvoiceInboxPriceList.forVendor(null).id());
  }

  @Test
  public void anotherTenantsListIsNeverUsedNeitherAsTheVendorsNorAsTheDefault() {
    String other = otherClientId(CLIENT_ID);
    String theirs = priceList("0-theirs", other, true, true, false);
    String mine = priceList("z-mine", CLIENT_ID, true, true, false);
    vendorUses(theirs);

    assertEquals("their default sorts first, but it is not visible: ours wins", mine,
        InvoiceInboxPriceList.forVendor(vendorId).id());
    assertEquals(mine, InvoiceInboxPriceList.forVendor(null).id());
  }

  @Test
  public void anInactiveDefaultIsIgnored() {
    priceList("a-off", CLIENT_ID, false, true, false);
    String active = priceList("b-on", CLIENT_ID, true, true, false);

    assertEquals(active, InvoiceInboxPriceList.forVendor(null).id());
  }

  // ---------------------------------------------------------------- standardPrice

  @Test
  public void theLatestActiveVersionAlreadyValidWins() {
    String list = priceList("versions", CLIENT_ID, true, false, false);
    String old = version(list, "2020-01-01", true);
    String current = version(list, "2025-01-01", true);
    productPrice(old, productId, "5", true);
    productPrice(current, productId, "7", true);

    assertEquals(0, new BigDecimal("7").compareTo(standard(list).orElseThrow()));
  }

  @Test
  public void aFutureVersionIsIgnored() {
    String list = priceList("future", CLIENT_ID, true, false, false);
    productPrice(version(list, "2020-01-01", true), productId, "5", true);
    productPrice(version(list, "2999-01-01", true), productId, "99", true);

    assertEquals(0, new BigDecimal("5").compareTo(standard(list).orElseThrow()));
  }

  @Test
  public void anInactiveVersionIsIgnoredAndTheOlderActiveOneAnswers() {
    String list = priceList("inactive-version", CLIENT_ID, true, false, false);
    productPrice(version(list, "2020-01-01", true), productId, "5", true);
    productPrice(version(list, "2025-01-01", false), productId, "8", true);

    assertEquals(0, new BigDecimal("5").compareTo(standard(list).orElseThrow()));
  }

  @Test
  public void anInactiveProductRowAnAbsentProductAndBlankArgumentsAreEmpty() {
    String list = priceList("rows", CLIENT_ID, true, false, false);
    String version = version(list, "2020-01-01", true);
    productPrice(version, productId, "5", false);

    assertTrue(standard(list).isEmpty());
    assertTrue(ProductPriceSelectorPolicy.standardPrice(list, "NO-SUCH-PRODUCT").isEmpty());
    assertTrue(ProductPriceSelectorPolicy.standardPrice("NO-SUCH-LIST", productId).isEmpty());
    assertTrue(ProductPriceSelectorPolicy.standardPrice("", productId).isEmpty());
    assertTrue(ProductPriceSelectorPolicy.standardPrice(list, null).isEmpty());
  }

  @Test
  public void aProductOnlyInAnOlderVersionIsNotPriced() {
    String list = priceList("latest-only", CLIENT_ID, true, false, false);
    productPrice(version(list, "2020-01-01", true), productId, "5", true);
    version(list, "2025-01-01", true);

    assertTrue("the selector only reads the latest version", standard(list).isEmpty());
  }

  @Test
  public void aPriceOfZeroIsZeroNotEmpty() {
    String list = priceList("zero", CLIENT_ID, true, false, false);
    productPrice(version(list, "2020-01-01", true), productId, "0", true);

    assertEquals(0, BigDecimal.ZERO.compareTo(standard(list).orElseThrow()));
  }

  @Test
  public void theStandardPriceIsTheOneTheSelectorRowsShow() throws Exception {
    String list = priceList("parity", CLIENT_ID, true, false, false);
    productPrice(version(list, "2020-01-01", true), productId, "12.3456", true);
    productPrice(version(list, "2025-01-01", true), productId, "13.5", true);
    JSONObject item = new JSONObject().put("id", productId);
    NeoResponse response = NeoResponse.ok(new JSONObject().put("items",
        new JSONArray().put(item)));

    ProductPriceSelectorPolicy.enrichProductSelectorWithPrices(response, list);

    BigDecimal shown = new BigDecimal(String.valueOf(
        response.getBody().getJSONArray("items").getJSONObject(0).get("standardPrice")));
    assertEquals(0, shown.compareTo(standard(list).orElseThrow()));
    assertEquals(0, new BigDecimal("13.5").compareTo(shown));
  }

  // ---------------------------------------------------------------- fixtures

  private Optional<BigDecimal> standard(String list) {
    return ProductPriceSelectorPolicy.standardPrice(list, productId);
  }

  private String priceList(String name, String clientId, boolean active, boolean isDefault,
      boolean includesTax) {
    return priceList(name, clientId, active, isDefault, includesTax, false);
  }

  private String priceList(String name, String clientId, boolean active, boolean isDefault,
      boolean includesTax, boolean sales) {
    String id = uuid();
    NativeQuery<?> insert = session().createNativeQuery(
        "INSERT INTO m_pricelist (m_pricelist_id, ad_client_id, ad_org_id, isactive, createdby, "
            + "updatedby, name, istaxincluded, issopricelist, isdefault, c_currency_id) VALUES "
            + "(:id, :client, :org, :active, :user, :user, :name, :tax, :sales, :def, :currency)");
    insert.setParameter("id", id);
    insert.setParameter("client", clientId);
    insert.setParameter("org", CLIENT_ID.equals(clientId) ? ORG_ID : "0");
    insert.setParameter("active", yn(active));
    insert.setParameter("user", TestConstants.Users.ADMIN);
    insert.setParameter("name", marker + "-" + name);
    insert.setParameter("tax", yn(includesTax));
    insert.setParameter("sales", yn(sales));
    insert.setParameter("def", yn(isDefault));
    insert.setParameter("currency", currencyId);
    insert.executeUpdate();
    return id;
  }

  private String version(String list, String validFrom, boolean active) {
    String id = uuid();
    NativeQuery<?> insert = session().createNativeQuery(
        "INSERT INTO m_pricelist_version (m_pricelist_version_id, ad_client_id, ad_org_id, "
            + "isactive, createdby, updatedby, name, m_pricelist_id, m_discountschema_id, "
            + "validfrom) VALUES (:id, :client, :org, :active, :user, :user, :name, :list, "
            + ":schema, CAST(:from AS timestamp))");
    insert.setParameter("id", id);
    insert.setParameter("client", CLIENT_ID);
    insert.setParameter("org", ORG_ID);
    insert.setParameter("active", yn(active));
    insert.setParameter("user", TestConstants.Users.ADMIN);
    insert.setParameter("name", marker + "-v-" + validFrom);
    insert.setParameter("list", list);
    insert.setParameter("schema", schemaId);
    insert.setParameter("from", validFrom);
    insert.executeUpdate();
    return id;
  }

  private void productPrice(String version, String product, String standardPrice, boolean active) {
    NativeQuery<?> insert = session().createNativeQuery(
        "INSERT INTO m_productprice (m_productprice_id, m_pricelist_version_id, m_product_id, "
            + "ad_client_id, ad_org_id, isactive, createdby, updatedby, pricelist, pricestd, "
            + "pricelimit) VALUES (:id, :version, :product, :client, :org, :active, :user, :user, "
            + ":price, :price, :price)");
    insert.setParameter("id", uuid());
    insert.setParameter("version", version);
    insert.setParameter("product", product);
    insert.setParameter("client", CLIENT_ID);
    insert.setParameter("org", ORG_ID);
    insert.setParameter("active", yn(active));
    insert.setParameter("user", TestConstants.Users.ADMIN);
    insert.setParameter("price", new BigDecimal(standardPrice));
    insert.executeUpdate();
  }

  /** Points the fixture vendor at {@code listId} ({@code null} clears its purchase list). */
  private void vendorUses(String listId) {
    NativeQuery<?> update = session().createNativeQuery(
        "UPDATE c_bpartner SET po_pricelist_id = :list WHERE c_bpartner_id = :id");
    update.setParameter("list", listId);
    update.setParameter("id", vendorId);
    update.executeUpdate();
    session().clear();
  }

  private org.hibernate.Session session() {
    return OBDal.getInstance().getSession();
  }

  private String uuid() {
    return scalar("SELECT get_uuid()");
  }

  private String scalar(String sql) {
    Object value = session().createNativeQuery(sql).uniqueResult();
    assertNotNull("the fixture query found nothing: " + sql, value);
    return (String) value;
  }

  private void execute(String sql) {
    session().createNativeQuery(sql).executeUpdate();
  }

  private static String yn(boolean value) {
    return value ? "Y" : "N";
  }
}
