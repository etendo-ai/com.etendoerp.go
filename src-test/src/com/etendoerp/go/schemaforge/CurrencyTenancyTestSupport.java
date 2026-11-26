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

import static org.junit.Assert.assertNotNull;

import org.hibernate.query.NativeQuery;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBDal;
import org.openbravo.erpCommon.utility.OBCurrencyUtils;
import org.openbravo.test.base.TestConstants;

/**
 * Fixtures shared by the DB-backed currency tenancy specs
 * ({@link CurrencyOptionsHandlerTenancyIntegrationTest},
 * {@link PurchaseInvoiceOcrLookupsTaxCurrencyIntegrationTest}): currencies and conversion ranges
 * inserted with native SQL on the DAL session's own connection (undone by the test rollback), another
 * tenant's client id, and the organization currency. Not a test class.
 */
final class CurrencyTenancyTestSupport {

  private CurrencyTenancyTestSupport() {
  }

  /** The currency of {@code orgId}, resolved the way the production code resolves it. */
  static String orgCurrency(String orgId) {
    OBContext.setAdminMode(false);
    try {
      String id = OBCurrencyUtils.getOrgCurrency(orgId);
      assertNotNull("the organization " + orgId + " has no currency: the fixture needs one", id);
      return id;
    } finally {
      OBContext.restorePreviousMode();
    }
  }

  /** An ISO code is unique in the whole database, so each test names its own. */
  static String currency(String isoCode, String symbol, String clientId, boolean active) {
    OBContext.setAdminMode(false);
    try {
      String id = (String) OBDal.getInstance().getSession()
          .createNativeQuery("SELECT get_uuid()").uniqueResult();
      NativeQuery<?> insert = OBDal.getInstance().getSession().createNativeQuery(
          "INSERT INTO c_currency (c_currency_id, ad_client_id, ad_org_id, isactive, createdby, "
              + "updatedby, iso_code, cursymbol, description, stdprecision, costingprecision, "
              + "priceprecision) VALUES (:id, :client, '0', :active, :user, :user, :iso, :symbol, "
              + ":iso, 2, 4, 2)");
      insert.setParameter("id", id);
      insert.setParameter("client", clientId);
      insert.setParameter("active", active ? "Y" : "N");
      insert.setParameter("user", TestConstants.Users.ADMIN);
      insert.setParameter("iso", isoCode);
      insert.setParameter("symbol", symbol);
      insert.executeUpdate();
      return id;
    } finally {
      OBContext.restorePreviousMode();
    }
  }

  /** A conversion range {@code from -> to} valid for all dates. */
  static String rate(String from, String to, String clientId, String orgId, boolean active) {
    OBContext.setAdminMode(false);
    try {
      String id = (String) OBDal.getInstance().getSession()
          .createNativeQuery("SELECT get_uuid()").uniqueResult();
      NativeQuery<?> insert = OBDal.getInstance().getSession().createNativeQuery(
          "INSERT INTO c_conversion_rate (c_conversion_rate_id, ad_client_id, ad_org_id, isactive, "
              + "createdby, updatedby, c_currency_id, c_currency_id_to, validfrom, validto, "
              + "conversionratetype, multiplyrate, dividerate) VALUES (:id, :client, :org, :active, "
              + ":user, :user, :from, :to, CAST('2000-01-01' AS timestamp), "
              + "CAST('9999-12-31' AS timestamp), 'S', 2, 0.5)");
      insert.setParameter("id", id);
      insert.setParameter("client", clientId);
      insert.setParameter("org", orgId);
      insert.setParameter("active", active ? "Y" : "N");
      insert.setParameter("user", TestConstants.Users.ADMIN);
      insert.setParameter("from", from);
      insert.setParameter("to", to);
      insert.executeUpdate();
      return id;
    } finally {
      OBContext.restorePreviousMode();
    }
  }

  /**
   * Any active client other than System and {@code sessionClientId}: the fixture the tenant fence is
   * proved against. Fails (never skips) when the database has none, so a CI database without a second
   * client cannot turn the tenant tests into a green no-op.
   */
  static String otherClientId(String sessionClientId) {
    NativeQuery<?> query = OBDal.getInstance().getSession().createNativeQuery(
        "SELECT ad_client_id FROM ad_client WHERE ad_client_id NOT IN ('0', :session) "
            + "AND isactive = 'Y' ORDER BY ad_client_id LIMIT 1");
    query.setParameter("session", sessionClientId);
    Object id = query.uniqueResult();
    assertNotNull("the currency tenancy tests need a second active client besides System and "
        + sessionClientId + " in the test database (none found): add one to the fixture database",
        id);
    return (String) id;
  }
}
