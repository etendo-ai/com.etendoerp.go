/*
 *************************************************************************
 * The contents of this file are subject to the Etendo License
 * (the "License"), you may not use this file except in compliance with
 * the License.
 * You may obtain a copy of the License at
 * https://github.com/etendosoftware/etendo_core/blob/main/legal/Etendo_license.txt
 * Software distributed under the License is distributed on an
 * "AS IS" basis, WITHOUT WARRANTY OF ANY KIND, either express or
 * implied. See the License for the specific language governing rights
 * and limitations under the License.
 * All portions are Copyright © 2021–2025 FUTIT SERVICES, S.L
 * All Rights Reserved.
 * Contributor(s): Futit Services S.L.
 *************************************************************************
 */
package com.etendoerp.go.schemaforge.webhooks;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.openbravo.base.weld.test.WeldBaseTest;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBDal;
import org.openbravo.test.base.TestConstants;

/**
 * Real-database specs for the Invoice Inbox entry of the role menu (ETP-6051): the AD_MENU node
 * added in sourcedata is active, sits under Procurement Management > Transactions between
 * Purchase Invoice and Matched Purchase Invoices, and {@link SFListMenu} returns it (with its
 * window id) only to roles that hold an active window access on the Invoice Inbox window. The SPA
 * sidebar declares the window id of that entry, so a deactivated or moved node removes it for
 * every role without any error.
 *
 * <p>Read-only: it runs against the sourcedata-applied rows and the real template roles
 * (Purchasing, Finance, Sales) and commits nothing; the rollback in {@code @After} is a safety
 * net.</p>
 *
 * <p>A new class, not an extension of {@code SFListMenuTest}: that one is a Mockito (JUnit 5)
 * unit test with mocked DAL and native queries, and the node, its tree position and the role
 * grants are exactly what a mock cannot prove. A DB-backed test must be an
 * {@code *IntegrationTest} to run in {@code goIsolatedDalTest}.</p>
 *
 * @covers com.etendoerp.go.schemaforge.webhooks.SFListMenu
 */
public class SFListMenuInvoiceInboxIntegrationTest extends WeldBaseTest {

  private static final String MENU_ID = "581A06FAE09540B58D316136EC023B3A";
  private static final String WINDOW_ID = "81AC659E93504927B459E0CAE80CE923";
  private static final String TRANSACTIONS_ID = "800242";
  private static final String PURCHASE_INVOICE = "Purchase Invoice";
  private static final String MATCHED_INVOICES = "Matched Purchase Invoices";

  private static final String PURCHASING_TEMPLATE = "5E279F5102F9410F9B8CCBA424741F46";
  private static final String FINANCE_TEMPLATE = "B88A34B5D1874F8685FA6F3C3A609412";
  private static final String SALES_TEMPLATE = "15ECC46CFBD74CF3A76D1F4DC8BA9F80";

  @Before
  public void clearContext() {
    OBContext.clearAdminModeStack();
  }

  @After
  public void cleanUp() {
    OBDal.getInstance().rollbackAndClose();
    OBContext.setOBContext((OBContext) null);
  }

  // ------------------------------------------------------------------ the node itself

  @Test
  public void theNodeIsActiveWithTheInboxWindowUnderTransactions() {
    Object[] row = (Object[]) OBDal.getInstance().getSession().createNativeQuery(
        "SELECT m.isactive AS m_active, m.issummary, m.action, m.ad_window_id, tn.isactive AS tn_active, tn.parent_id,"
            + " tn.seqno FROM ad_menu m JOIN ad_treenode tn ON tn.node_id = m.ad_menu_id"
            + " AND tn.ad_tree_id = '10' WHERE m.ad_menu_id = '" + MENU_ID + "'")
        .uniqueResult();

    assertNotNull("the Invoice Inbox AD_MENU node is missing", row);
    assertEquals("Y", String.valueOf(row[0]));
    assertEquals("N", String.valueOf(row[1]));
    assertEquals("W", String.valueOf(row[2]));
    assertEquals(WINDOW_ID, String.valueOf(row[3]));
    assertEquals("Y", String.valueOf(row[4]));
    assertEquals(TRANSACTIONS_ID, String.valueOf(row[5]));
    assertEquals(75, ((Number) row[6]).intValue());
  }

  @Test
  public void theNodeSeqnoSitsBetweenPurchaseInvoiceAndMatchedPurchaseInvoices() {
    int purchaseInvoice = seqnoUnderTransactions(PURCHASE_INVOICE);
    int matched = seqnoUnderTransactions(MATCHED_INVOICES);
    int inbox = seqnoUnderTransactions("Invoice Inbox");

    assertTrue(purchaseInvoice + " < " + inbox, purchaseInvoice < inbox);
    assertTrue(inbox + " < " + matched, inbox < matched);
  }

  // ------------------------------------------------------------------ what each role gets

  @Test
  public void aSystemAdministratorGetsTheNodeUnderProcurementTransactions() throws Exception {
    JSONObject tree = menuFor(TestConstants.Roles.SYS_ADMIN, "0", "0");

    assertInboxUnderProcurementTransactions(tree);
  }

  @Test
  public void aClientAdminGetsTheNodeUnderProcurementTransactions() throws Exception {
    JSONObject tree = menuFor(TestConstants.Roles.FB_GRP_ADMIN, TestConstants.Clients.FB_GRP,
        TestConstants.Orgs.ESP);

    assertInboxUnderProcurementTransactions(tree);
  }

  @Test
  public void thePurchasingTemplateRoleGetsTheNode() throws Exception {
    assertInboxUnderProcurementTransactions(menuFor(PURCHASING_TEMPLATE, "0", "0"));
  }

  @Test
  public void theFinanceTemplateRoleGetsTheNode() throws Exception {
    assertInboxUnderProcurementTransactions(menuFor(FINANCE_TEMPLATE, "0", "0"));
  }

  @Test
  public void theSalesTemplateRoleDoesNotGetTheNode() throws Exception {
    assertEquals("precondition: the Sales template must hold no grant on the Invoice Inbox window",
        0, windowGrants(SALES_TEMPLATE));

    JSONObject tree = menuFor(SALES_TEMPLATE, "0", "0");

    assertNull(findById(tree.getJSONArray("tree"), MENU_ID, new ArrayList<>()));
  }

  // ------------------------------------------------------------------ helpers

  private static void assertInboxUnderProcurementTransactions(JSONObject result) throws Exception {
    List<String> path = new ArrayList<>();
    JSONObject node = findById(result.getJSONArray("tree"), MENU_ID, path);

    assertNotNull("Invoice Inbox was not returned", node);
    assertEquals(WINDOW_ID, node.getString("windowId"));
    assertEquals("Invoice Inbox", node.getString("name"));
    assertEquals(List.of("Procurement Management", "Transactions"), path);
  }

  private static JSONObject menuFor(String roleId, String clientId, String orgId) throws Exception {
    OBContext.setOBContext(TestConstants.Users.ADMIN, roleId, clientId, orgId);
    Map<String, String> params = new HashMap<>();
    Map<String, String> response = new HashMap<>();

    new SFListMenu().get(params, response);

    assertNull("SFListMenu failed: " + response.get("error"), response.get("error"));
    return new JSONObject(response.get("result"));
  }

  /** Depth-first search; {@code path} receives the names of the ancestors of the node found. */
  private static JSONObject findById(JSONArray nodes, String id, List<String> path)
      throws Exception {
    for (int i = 0; i < nodes.length(); i++) {
      JSONObject node = nodes.getJSONObject(i);
      if (id.equals(node.getString("id"))) {
        return node;
      }
      if (node.has("children")) {
        path.add(node.getString("name"));
        JSONObject found = findById(node.getJSONArray("children"), id, path);
        if (found != null) {
          return found;
        }
        path.remove(path.size() - 1);
      }
    }
    return null;
  }

  private static int seqnoUnderTransactions(String name) {
    Object value = OBDal.getInstance().getSession().createNativeQuery(
        "SELECT tn.seqno FROM ad_treenode tn JOIN ad_menu m ON m.ad_menu_id = tn.node_id"
            + " WHERE tn.ad_tree_id = '10' AND tn.parent_id = '" + TRANSACTIONS_ID
            + "' AND m.name = '" + name + "'").uniqueResult();
    assertNotNull(name + " is not under Transactions", value);
    return ((Number) value).intValue();
  }

  private static int windowGrants(String roleId) {
    Object count = OBDal.getInstance().getSession().createNativeQuery(
        "SELECT count(*) FROM ad_window_access WHERE ad_role_id = '" + roleId
            + "' AND ad_window_id = '" + WINDOW_ID + "' AND isactive = 'Y'").uniqueResult();
    return ((Number) count).intValue();
  }
}
