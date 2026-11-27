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

import static com.etendoerp.go.schemaforge.LineAmountTestSupport.assertAmount;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.Date;
import java.util.stream.Stream;

import org.codehaus.jettison.json.JSONObject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.MockedStatic;
import org.openbravo.dal.service.OBDal;
import org.openbravo.financial.FinancialUtils;
import org.openbravo.model.common.order.Order;
import org.openbravo.model.common.order.OrderLine;
import org.openbravo.model.common.plm.Product;
import org.openbravo.model.pricing.pricelist.PriceList;
import org.openbravo.model.pricing.pricelist.ProductPrice;

/**
 * The price of an order line whose product an update changes (ETP-6107): taken from the order's
 * price list, not from the product selector's auxiliary values, which are resolved without the
 * order's price list and left the previous product's list price on a tenant with several lists.
 *
 * @covers com.etendoerp.go.schemaforge.OrderLineProductChangePrice
 */
class OrderLineProductChangePriceTest {

  private static final String LINE_ID = "L1";
  private static final String PRODUCT_ID = "NEW";

  static Stream<Arguments> prices() {
    return Stream.of(
        // label, caller values, catalog standard, catalog list → unit, list, discount
        Arguments.of("new product alone: the catalog price", "{}", "45", "50", "45", "50", "10"),
        Arguments.of("list equal to standard: no discount", "{}", "50", "50", "50", "50", "0"),
        Arguments.of("a sent price is the net, discounted from the new list",
            "{\"unitPrice\":40}", "50", "50", "40", "50", "20"),
        Arguments.of("a sent discount applies to the new list", "{\"discount\":10}", "50", "50",
            "45", "50", "10"),
        Arguments.of("a sent price and discount are both the caller's",
            "{\"unitPrice\":40,\"discount\":5}", "50", "50", "40", "50", "5"),
        Arguments.of("a price above the list gives a negative discount, as SL_Order_Amt",
            "{\"unitPrice\":60}", "50", "50", "60", "50", "-20"));
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("prices")
  void theNewProductIsPricedFromItsCatalogEntry(String label, String sent, String standard,
      String list, String unit, String expectedList, String discount) throws Exception {
    JSONObject body = new JSONObject();

    OrderLineProductChangePrice.writePrices(body, new JSONObject(sent), new BigDecimal(standard),
        new BigDecimal(list));

    assertAmount(label, unit, body, OrderLineDiscountSupport.FIELD_UNIT_PRICE);
    assertAmount(label + ": the standard price follows the unit price", unit, body,
        OrderLineDiscountSupport.FIELD_STANDARD_PRICE);
    assertAmount(label, expectedList, body, OrderLineDiscountSupport.FIELD_LIST_PRICE);
    assertAmount(label, discount, body, OrderLineDiscountSupport.FIELD_DISCOUNT);
  }

  /** On MCP the re-fire rewrote the body first: the caller's values are the snapshot. */
  @Test
  void theCallerValuesAreTheMcpSnapshotWhenThereIsOne() throws Exception {
    JSONObject body = new JSONObject().put("unitPrice", 33);
    JSONObject snapshot = new JSONObject().put("unitPrice", 40);
    NeoContext ctx = update(body);
    ctx.setAttribute(NeoContext.ATTR_CLIENT_SENT_VALUES, snapshot);

    assertSame(snapshot, OrderLineProductChangePrice.callerValues(ctx));
  }

  /** On REST the pre-hook runs first: the body is what the caller sent. */
  @Test
  void theCallerValuesAreTheBodyOnRest() throws Exception {
    JSONObject body = new JSONObject().put("unitPrice", 40);

    assertSame(body, OrderLineProductChangePrice.callerValues(update(body)));
  }

  /** A list price the caller sent is left to the other rules; nothing is read. */
  @Test
  void aSentListPriceIsLeftAlone() throws Exception {
    JSONObject body = new JSONObject().put("product", "NEW").put("listPrice", 20);

    try (MockedStatic<OBDal> dalMock = mockStatic(OBDal.class)) {
      OrderLineProductChangePrice.reprice(update(body));
      dalMock.verifyNoInteractions();
    }

    assertEquals(2, body.length(), "untouched: " + body);
  }

  /** A line without an order (or that cannot be read) leaves the body as sent. Total. */
  @Test
  void aLineWithoutAnOrderIsLeftAlone() throws Exception {
    JSONObject body = new JSONObject().put("product", "NEW");
    OBDal dal = mock(OBDal.class);
    when(dal.get(OrderLine.class, LINE_ID)).thenReturn(mock(OrderLine.class));

    try (MockedStatic<OBDal> dalMock = mockStatic(OBDal.class)) {
      dalMock.when(OBDal::getInstance).thenReturn(dal);
      OrderLineProductChangePrice.reprice(update(body));
    }

    assertEquals(1, body.length(), "untouched: " + body);
  }

  @Test
  void aFailingReadIsTotal() throws Exception {
    JSONObject body = new JSONObject().put("product", "NEW");
    OBDal dal = mock(OBDal.class);
    when(dal.get(OrderLine.class, LINE_ID)).thenThrow(new IllegalStateException("boom"));

    try (MockedStatic<OBDal> dalMock = mockStatic(OBDal.class)) {
      dalMock.when(OBDal::getInstance).thenReturn(dal);
      OrderLineProductChangePrice.reprice(update(body));
    }

    assertEquals(1, body.length(), "untouched: " + body);
  }

  /** Nothing to price without a context, a body or a line id. */
  @Test
  void anIncompleteContextIsLeftAlone() throws Exception {
    JSONObject body = new JSONObject().put("product", PRODUCT_ID);
    NeoContext noId = NeoContext.builder().httpMethod("PATCH").requestBody(body).build();
    NeoContext noBody = NeoContext.builder().httpMethod("PATCH").recordId(LINE_ID).build();

    try (MockedStatic<OBDal> dalMock = mockStatic(OBDal.class)) {
      OrderLineProductChangePrice.reprice(null);
      OrderLineProductChangePrice.reprice(noId);
      OrderLineProductChangePrice.reprice(noBody);
      dalMock.verifyNoInteractions();
    }

    assertEquals(1, body.length(), "untouched: " + body);
  }

  /** A catalog entry without a standard price is no price. */
  @Test
  void aCatalogEntryWithoutStandardPriceIsLeftAlone() throws Exception {
    JSONObject body = new JSONObject().put("product", PRODUCT_ID);
    ProductPrice price = mock(ProductPrice.class);

    repriceWith(body, price, false);

    assertEquals(1, body.length(), "untouched: " + body);
  }

  /** The new product's entry in the order's price list prices the line (the e2e: 44, not 33). */
  @Test
  void theOrdersPriceListPricesTheNewProduct() throws Exception {
    JSONObject body = new JSONObject().put("product", PRODUCT_ID);

    repriceWith(body, catalog("44", "44"), false);

    assertAmount("list", "44", body, OrderLineDiscountSupport.FIELD_LIST_PRICE);
    assertAmount("unit", "44", body, OrderLineDiscountSupport.FIELD_UNIT_PRICE);
    assertAmount("standard", "44", body, OrderLineDiscountSupport.FIELD_STANDARD_PRICE);
    assertAmount("discount", "0", body, OrderLineDiscountSupport.FIELD_DISCOUNT);
  }

  /** A catalog entry without a list price is priced at its standard price, no discount. */
  @Test
  void aZeroListPriceFallsBackToTheStandardPrice() throws Exception {
    JSONObject body = new JSONObject().put("product", PRODUCT_ID);

    repriceWith(body, catalog("30", "0"), false);

    assertAmount("list", "30", body, OrderLineDiscountSupport.FIELD_LIST_PRICE);
    assertAmount("discount", "0", body, OrderLineDiscountSupport.FIELD_DISCOUNT);
  }

  /** A tax-included list is left to the callout, which derives the net side. */
  @Test
  void aTaxIncludedListIsLeftToTheCallout() throws Exception {
    JSONObject body = new JSONObject().put("product", PRODUCT_ID);

    repriceWith(body, catalog("44", "44"), true);

    assertEquals(1, body.length(), "untouched: " + body);
  }

  /** A product with no price in the order's list leaves the body as sent. */
  @Test
  void aProductWithoutPriceIsLeftAlone() throws Exception {
    JSONObject body = new JSONObject().put("product", PRODUCT_ID);

    repriceWith(body, null, false);

    assertEquals(1, body.length(), "untouched: " + body);
  }

  private static void repriceWith(JSONObject body, ProductPrice price, boolean taxIncluded) {
    PriceList priceList = mock(PriceList.class);
    when(priceList.isPriceIncludesTax()).thenReturn(taxIncluded);
    Order order = mock(Order.class);
    when(order.getOrderDate()).thenReturn(new Date());
    when(order.getPriceList()).thenReturn(priceList);
    when(order.isSalesTransaction()).thenReturn(true);
    OrderLine line = mock(OrderLine.class);
    when(line.getSalesOrder()).thenReturn(order);
    Product product = mock(Product.class);
    OBDal dal = mock(OBDal.class);
    when(dal.get(OrderLine.class, LINE_ID)).thenReturn(line);
    when(dal.get(Product.class, PRODUCT_ID)).thenReturn(product);

    try (MockedStatic<OBDal> dalMock = mockStatic(OBDal.class);
        MockedStatic<FinancialUtils> financial = mockStatic(FinancialUtils.class)) {
      dalMock.when(OBDal::getInstance).thenReturn(dal);
      financial.when(() -> FinancialUtils.getProductPrice(eq(product), any(), anyBoolean(),
          eq(priceList), eq(false))).thenReturn(price);
      OrderLineProductChangePrice.reprice(update(body));
    }
  }

  private static ProductPrice catalog(String standard, String list) {
    ProductPrice price = mock(ProductPrice.class);
    when(price.getStandardPrice()).thenReturn(new BigDecimal(standard));
    when(price.getListPrice()).thenReturn(new BigDecimal(list));
    return price;
  }

  private static NeoContext update(JSONObject body) {
    return NeoContext.builder().specName("sales-order").entityName("lines").httpMethod("PATCH")
        .endpointType(NeoEndpointType.CRUD).recordId(LINE_ID).requestBody(body).build();
  }
}
