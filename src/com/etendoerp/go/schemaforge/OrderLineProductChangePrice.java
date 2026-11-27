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

package com.etendoerp.go.schemaforge;

import static com.etendoerp.go.schemaforge.OrderLineDiscountSupport.FIELD_DISCOUNT;
import static com.etendoerp.go.schemaforge.OrderLineDiscountSupport.FIELD_LIST_PRICE;
import static com.etendoerp.go.schemaforge.OrderLineDiscountSupport.FIELD_PRODUCT;
import static com.etendoerp.go.schemaforge.OrderLineDiscountSupport.FIELD_STANDARD_PRICE;
import static com.etendoerp.go.schemaforge.OrderLineDiscountSupport.FIELD_UNIT_PRICE;

import java.math.BigDecimal;
import java.math.RoundingMode;

import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.codehaus.jettison.json.JSONException;
import org.codehaus.jettison.json.JSONObject;
import org.openbravo.dal.service.OBDal;
import org.openbravo.financial.FinancialUtils;
import org.openbravo.model.common.order.Order;
import org.openbravo.model.common.order.OrderLine;
import org.openbravo.model.common.plm.Product;
import org.openbravo.model.pricing.pricelist.PriceList;
import org.openbravo.model.pricing.pricelist.ProductPrice;

/**
 * Prices an order line whose product an update changes from the order's price list (ETP-6107), as
 * {@link OrderLineDiscountSupport#setStandardPriceOnCreate} does for a new line.
 *
 * <p><b>Why not the product callout.</b> The update re-fires the product's callout
 * ({@code NeoUpdateCalloutRefire}), and {@code SL_Order_Product} reads the price from the
 * selector's auxiliary values. Those are resolved by the product id alone, without the order's
 * price list, so on a tenant with more than one price list they can belong to another list or be
 * missing: the line kept the previous product's list price (the public API e2e, 33 instead of 44).
 * The catalog of the order's own price list is the price the form shows.
 *
 * <p><b>What it writes.</b> The list price of the new product, in the order's currency. The unit
 * price is the caller's when sent (the net, as in the form when the user types a price after
 * picking the product; on MCP too, unlike the ETP-5852 rule of an update that keeps the product,
 * where a lone {@code unitPrice} is the list price), else the list price less a sent discount, else the catalog's standard
 * price; the standard price follows it, as {@code SL_Order_Amt} does. The discount is the
 * caller's when sent, else the gap between list and unit price. A list price the caller sent is
 * left to the other rules.
 *
 * <p><b>What the caller sent.</b> On REST the pre-hook runs before the re-fire, so the body is what
 * the caller sent. On MCP the re-fire and the agent price rule ran first; the caller's values are
 * the snapshot in {@link NeoContext#ATTR_CLIENT_SENT_VALUES}.
 *
 * <p>Abstains when the stored line, its order or the product cannot be read, when the order has no
 * price list or its list includes tax (deriving the net side is the callout's job), or when the
 * product has no price there. Total: never throws.
 */
final class OrderLineProductChangePrice {

  private static final Logger log = LogManager.getLogger(OrderLineProductChangePrice.class);
  private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);
  private static final int PRICE_SCALE = 6;

  private OrderLineProductChangePrice() {
  }

  /**
   * Writes the new product's prices into the update body.
   *
   * @param ctx the pre-hook context of an update that changes the line's product
   */
  static void reprice(NeoContext ctx) {
    if (ctx == null || ctx.getRequestBody() == null || StringUtils.isBlank(ctx.getRecordId())) {
      return;
    }
    try {
      JSONObject sent = callerValues(ctx);
      if (sent.has(FIELD_LIST_PRICE)) {
        return;
      }
      OrderLine line = OBDal.getInstance().get(OrderLine.class, ctx.getRecordId());
      Order order = line == null ? null : line.getSalesOrder();
      ProductPrice catalog = catalogPrice(order,
          ctx.getRequestBody().optString(FIELD_PRODUCT, null));
      if (catalog == null || catalog.getStandardPrice() == null) {
        return;
      }
      OrderLineCurrencyConversion conversion = OrderLineCurrencyConversion.forOrder(order);
      BigDecimal standard = conversion.convert(catalog.getStandardPrice());
      BigDecimal list = conversion.convert(catalog.getListPrice());
      if (list == null || list.signum() == 0) {
        list = standard;
      }
      writePrices(ctx.getRequestBody(), sent, standard, list);
    } catch (Exception e) {
      log.warn("[ORDER-LINE-PRICE] Could not price the new product of {} on {} {}: {}",
          ctx.getRecordId(), ctx.getSpecName(), ctx.getEntityName(), e.getMessage());
    }
  }

  /** The caller's values: the MCP snapshot when there is one, else the body itself (REST). */
  static JSONObject callerValues(NeoContext ctx) {
    Object snapshot = ctx.getAttribute(NeoContext.ATTR_CLIENT_SENT_VALUES);
    return snapshot instanceof JSONObject ? (JSONObject) snapshot : ctx.getRequestBody();
  }

  /**
   * The unit price and discount from what the caller sent, then the four prices into
   * {@code body}.
   */
  static void writePrices(JSONObject body, JSONObject sent, BigDecimal standard, BigDecimal list)
      throws JSONException {
    BigDecimal unit = LineAmountSupport.decimal(sent.opt(FIELD_UNIT_PRICE));
    BigDecimal discount = LineAmountSupport.decimal(sent.opt(FIELD_DISCOUNT));
    if (unit == null) {
      unit = discount == null ? standard
          : list.multiply(HUNDRED.subtract(discount)).divide(HUNDRED, PRICE_SCALE,
              RoundingMode.HALF_UP);
    }
    if (discount == null) {
      discount = list.signum() == 0 ? BigDecimal.ZERO
          : list.subtract(unit).multiply(HUNDRED).divide(list, 2, RoundingMode.HALF_UP);
    }
    body.put(FIELD_LIST_PRICE, list);
    // The standard price follows the unit price, as SL_Order_Amt sets it when the user types a
    // price; with no price sent both are the catalog's standard price.
    body.put(FIELD_STANDARD_PRICE, unit);
    body.put(FIELD_UNIT_PRICE, unit);
    body.put(FIELD_DISCOUNT, discount);
  }

  private static ProductPrice catalogPrice(Order order, String productId) {
    if (order == null || order.getOrderDate() == null || StringUtils.isBlank(productId)) {
      return null;
    }
    PriceList priceList = order.getPriceList();
    Product product = OBDal.getInstance().get(Product.class, productId);
    if (product == null || priceList == null
        || Boolean.TRUE.equals(priceList.isPriceIncludesTax())) {
      return null;
    }
    return FinancialUtils.getProductPrice(product, order.getOrderDate(),
        Boolean.TRUE.equals(order.isSalesTransaction()), priceList, false);
  }
}
