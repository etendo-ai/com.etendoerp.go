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

import static com.etendoerp.go.schemaforge.InvoiceInboxApplyValues.cleanText;
import static com.etendoerp.go.schemaforge.InvoiceInboxApplyValues.text;
import static com.etendoerp.go.schemaforge.ocr.InboxDocument.A_UNKNOWN;
import static com.etendoerp.go.schemaforge.ocr.InboxDocument.K_ADDRESS_STATE;
import static com.etendoerp.go.schemaforge.ocr.InboxDocument.K_CANDIDATES;
import static com.etendoerp.go.schemaforge.ocr.InboxDocument.K_CATEGORY_ID;
import static com.etendoerp.go.schemaforge.ocr.InboxDocument.CURRENCY_MANUAL;
import static com.etendoerp.go.schemaforge.ocr.InboxDocument.K_CATEGORY_SOURCE;
import static com.etendoerp.go.schemaforge.ocr.InboxDocument.K_CURRENCY_ID;
import static com.etendoerp.go.schemaforge.ocr.InboxDocument.K_CURRENCY_SOURCE;
import static com.etendoerp.go.schemaforge.ocr.InboxDocument.K_CURRENCY_UNAVAILABLE;
import static com.etendoerp.go.schemaforge.ocr.InboxDocument.K_NOTICES;
import static com.etendoerp.go.schemaforge.ocr.InboxDocument.N_CURRENCY_SYMBOL_AMBIGUOUS;
import static com.etendoerp.go.schemaforge.ocr.InboxDocument.K_DESCRIPTION;
import static com.etendoerp.go.schemaforge.ocr.InboxDocument.K_EXTRACTED;
import static com.etendoerp.go.schemaforge.ocr.InboxDocument.K_ID;
import static com.etendoerp.go.schemaforge.ocr.InboxDocument.K_INDEX;
import static com.etendoerp.go.schemaforge.ocr.InboxDocument.K_INVOICE_DATE;
import static com.etendoerp.go.schemaforge.ocr.InboxDocument.K_LOCATION_ID;
import static com.etendoerp.go.schemaforge.ocr.InboxDocument.K_MANUAL;
import static com.etendoerp.go.schemaforge.ocr.InboxDocument.K_MATCHED_BY;
import static com.etendoerp.go.schemaforge.ocr.InboxDocument.K_NAME;
import static com.etendoerp.go.schemaforge.ocr.InboxDocument.K_NEW;
import static com.etendoerp.go.schemaforge.ocr.InboxDocument.K_PRODUCT;
import static com.etendoerp.go.schemaforge.ocr.InboxDocument.K_STATE;
import static com.etendoerp.go.schemaforge.ocr.InboxDocument.K_UNIT_PRICE;
import static com.etendoerp.go.schemaforge.ocr.InboxDocument.K_UNIT_PRINTED;
import static com.etendoerp.go.schemaforge.ocr.InboxDocument.K_UOM_ID;
import static com.etendoerp.go.schemaforge.ocr.InboxDocument.K_UOM_SOURCE;
import static com.etendoerp.go.schemaforge.ocr.InboxDocument.K_VENDOR;
import static com.etendoerp.go.schemaforge.ocr.InboxDocument.MATCHED_MANUAL;
import static com.etendoerp.go.schemaforge.ocr.InboxDocument.S_LINKED;
import static com.etendoerp.go.schemaforge.ocr.InboxDocument.S_NEW;
import static com.etendoerp.go.schemaforge.ocr.InboxDocument.SRC_MANUAL;


import java.math.BigDecimal;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.Iterator;
import java.util.Optional;

import org.apache.commons.lang3.StringUtils;
import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONException;
import org.codehaus.jettison.json.JSONObject;
import org.openbravo.dal.service.OBDal;
import org.openbravo.erpCommon.utility.OBCurrencyUtils;
import org.openbravo.model.common.currency.Currency;

import com.etendoerp.go.schemaforge.ocr.InboxNewProductDefaults;
import com.etendoerp.go.schemaforge.ocr.OcrLookups;
import com.etendoerp.go.schemaforge.ocr.OcrLookups.Address;
import com.etendoerp.go.schemaforge.ocr.OcrLookups.Party;

/**
 * The operations of {@link InvoiceInboxApply} that choose or create the vendor of a document and
 * the product of a line (ETP-6050): {@code selectVendor}, {@code newVendor},
 * {@code selectProduct} and {@code newProduct}. Only ids are stored; every id is resolved again
 * through the lookups.
 */
final class InvoiceInboxApplyResolution {

  private static final String KEY_ID = "id";
  private static final String KEY_FIELDS = "fields";

  private InvoiceInboxApplyResolution() {
  }

  private static JSONObject vendorOf(InvoiceInboxApply.Edit edit) {
    return edit.header().optJSONObject(K_VENDOR);
  }

  static void selectVendor(InvoiceInboxApply.Edit edit, JSONObject op) throws JSONException {
    String id = op.getString(KEY_ID);
    JSONObject vendor = vendorOf(edit);
    boolean alreadyLinked = S_LINKED.equals(vendor.optString(K_STATE)) && id.equals(
        vendor.optString(K_ID));
    if (alreadyLinked) {
      return;
    }
    Optional<Party> found = edit.lookups == null ? Optional.empty() : edit.lookups.vendorById(id);
    if (found.isEmpty()) {
      throw new InvoiceInboxApply.Refusal(InvoiceInboxEditGate.refuse(422,
          InvoiceInboxApply.CODE_UNKNOWN_VENDOR, InvoiceInboxMessages.UNKNOWN_VENDOR, null));
    }
    Address address = edit.lookups.vendorAddress(id);
    boolean present = address.state() == OcrLookups.AddressState.PRESENT
        && address.locationId() != null;
    vendor.put(K_STATE, S_LINKED).put(K_MATCHED_BY, MATCHED_MANUAL).put(K_ID, found.get().id())
        .put(K_NAME, found.get().name()).put(K_ADDRESS_STATE, address.state().name())
        .put(K_LOCATION_ID, present ? address.locationId() : JSONObject.NULL);
    vendor.remove("details");
    edit.vendorChanged = true;
    edit.changed(InvoiceInboxAdjustedPolicy.selection(false));
  }

  static void newVendor(InvoiceInboxApply.Edit edit, JSONObject op) throws JSONException {
    JSONObject vendor = vendorOf(edit);
    JSONObject extracted = vendor.optJSONObject(K_EXTRACTED);
    if (extracted == null) {
      extracted = new JSONObject();
    }
    boolean wasLinked = S_LINKED.equals(vendor.optString(K_STATE));
    boolean wasNew = S_NEW.equals(vendor.optString(K_STATE));
    JSONObject fields = op.optJSONObject(KEY_FIELDS);
    boolean anyDiffers = false;
    JSONObject merged = new JSONObject(extracted.toString());
    Iterator<?> keys = fields == null ? new JSONObject().keys() : fields.keys();
    while (keys.hasNext()) {
      String key = (String) keys.next();
      String cleaned = cleanText(fields.get(key), key, null);
      if (!cleaned.equals(text(extracted, key))) {
        merged.put(key, cleaned);
        anyDiffers = true;
      }
    }
    if (wasNew && !anyDiffers) {
      return;
    }
    vendor.remove(K_ID);
    vendor.remove(K_NAME);
    vendor.remove(K_MATCHED_BY);
    vendor.remove("details");
    vendor.put(K_STATE, S_NEW).put(K_EXTRACTED, merged).put(K_ADDRESS_STATE, A_UNKNOWN)
        .put(K_LOCATION_ID, JSONObject.NULL);
    if (vendor.optJSONArray(K_CANDIDATES) == null) {
      vendor.put(K_CANDIDATES, new JSONArray());
    }
    edit.vendorChanged = true;
    edit.changed(InvoiceInboxAdjustedPolicy.newVendor(wasLinked, anyDiffers));
  }

  static void selectProduct(InvoiceInboxApply.Edit edit, JSONObject op) throws JSONException {
    int index = op.getInt(K_INDEX);
    JSONObject line = InvoiceInboxApply.lineOf(edit, index);
    String id = op.getString(KEY_ID);
    JSONObject product = line.optJSONObject(K_PRODUCT);
    boolean alreadyLinked = product != null && S_LINKED.equals(product.optString(K_STATE))
        && id.equals(product.optString(K_ID));
    if (alreadyLinked) {
      return;
    }
    Optional<Party> found = edit.lookups == null ? Optional.empty()
        : edit.lookups.productById(id);
    if (found.isEmpty()) {
      throw new InvoiceInboxApply.Refusal(InvoiceInboxEditGate.refuse(422,
          InvoiceInboxApply.CODE_UNKNOWN_PRODUCT, InvoiceInboxMessages.UNKNOWN_PRODUCT,
          InvoiceInboxEditGate.details(K_INDEX, index)));
    }
    line.put(K_PRODUCT, new JSONObject().put(K_STATE, S_LINKED).put(K_MATCHED_BY, MATCHED_MANUAL)
        .put(K_ID, found.get().id()).put(K_NAME, found.get().name())
        .put(K_CANDIDATES, candidatesOf(product)));
    priceManualLine(edit, line, found.get().id());
    edit.changed(InvoiceInboxAdjustedPolicy.selection(false));
  }

  /**
   * ETP-6051: a product picked on a MANUAL line ({@code manual: true}, set by {@code addLine}) always
   * takes the price list price ({@link InvoiceInboxLinePricing}), overwriting any price already on the
   * line (a typed one, or the one of the previous product), like the purchase invoice window. An OCR
   * line (no marker) is never touched, and a price the list cannot give leaves the current price as it
   * is (never zeroed). Not a user adjustment: the line is not marked.
   */
  private static void priceManualLine(InvoiceInboxApply.Edit edit, JSONObject line,
      String productId) {
    if (edit.lookups == null || !line.optBoolean(K_MANUAL, false)) {
      return;
    }
    String orgId = edit.row == null || edit.row.getOrganization() == null ? null
        : edit.row.getOrganization().getId();
    String clientId = edit.row == null || edit.row.getClient() == null ? null
        : edit.row.getClient().getId();
    JSONObject vendor = vendorOf(edit);
    String vendorId = vendor != null && S_LINKED.equals(vendor.optString(K_STATE))
        ? vendor.optString(K_ID, null) : null;
    Optional<BigDecimal> price = InvoiceInboxLinePricing.priceFor(edit.lookups, vendorId,
        productId, currencyIdNow(edit, orgId), orgId, clientId,
        dateOf(text(edit.header(), K_INVOICE_DATE)));
    price.ifPresent(value -> putPrice(line, value));
  }

  private static void putPrice(JSONObject line, BigDecimal value) {
    try {
      line.put(K_UNIT_PRICE, value.doubleValue());
    } catch (JSONException e) {
      throw new IllegalStateException("Could not store the unit price", e);
    }
  }

  /**
   * The currency the document has right now: the stored one, else the default derived now (the
   * organization's). A vendor change never moves it: the vendor and its price list infer nothing.
   */
  private static String currencyIdNow(InvoiceInboxApply.Edit edit, String orgId) {
    JSONObject header = edit.header();
    String stored = text(header, K_CURRENCY_ID);
    if (!stored.isEmpty()) {
      return stored;
    }
    return edit.lookups.documentCurrency(null, orgId)
        .map(OcrLookups.DocCurrency::id).orElse(null);
  }

  private static LocalDate dateOf(String iso) {
    try {
      return iso.isEmpty() ? null : LocalDate.parse(iso);
    } catch (DateTimeParseException e) {
      return null;
    }
  }

  static void newProduct(InvoiceInboxApply.Edit edit, JSONObject op) throws JSONException {
    int index = op.getInt(K_INDEX);
    JSONObject line = InvoiceInboxApply.lineOf(edit, index);
    JSONObject product = line.optJSONObject(K_PRODUCT);
    boolean wasLinked = product != null && S_LINKED.equals(product.optString(K_STATE));
    boolean wasNew = product != null && S_NEW.equals(product.optString(K_STATE));
    JSONObject stored = product == null ? null : product.optJSONObject(K_NEW);
    // The base the user's choice is compared with: what is stored when the line already was a new
    // product, else what the worker would have pre-filled for it (ETP-6051).
    JSONObject base = wasNew ? stored : preFilled(edit, line);
    String currentName = stored != null && stored.has(K_NAME) ? text(stored, K_NAME)
        : text(line, K_DESCRIPTION);
    String name = op.has(K_NAME) ? cleanText(op.get(K_NAME), K_NAME, index) : currentName;
    JSONObject draft = new JSONObject().put(K_NAME, name);
    resolveChoice(edit, op, base, draft, index, true);
    resolveChoice(edit, op, base, draft, index, false);
    if (wasNew && stored != null && sameDraft(stored, draft, currentName)) {
      return;
    }
    boolean choiceDiffers = storedValueChanged(base, draft, K_CATEGORY_ID)
        || storedValueChanged(base, draft, K_UOM_ID);
    line.put(K_PRODUCT, new JSONObject().put(K_STATE, S_NEW).put(K_ID, JSONObject.NULL)
        .put(K_CANDIDATES, candidatesOf(product)).put(K_NEW, draft));
    edit.changed(InvoiceInboxAdjustedPolicy.newProduct(true, wasLinked, !name.equals(currentName),
        choiceDiffers));
  }

  /**
   * Whether a field that HAD a stored value now holds another one or none (owner decision of
   * 2026-11-17). A field that was empty (the OCR could not define it and no default applies) does
   * not count when the user fills it: that is completing a mandatory value, not adjusting one.
   */
  private static boolean storedValueChanged(JSONObject base, JSONObject draft, String idKey) {
    String stored = text(base, idKey);
    return !StringUtils.isBlank(stored) && !stored.equals(text(draft, idKey));
  }

  /** What the worker pre-fills for a new product of this line: the printed unit, the defaults. */
  private static JSONObject preFilled(InvoiceInboxApply.Edit edit, JSONObject line)
      throws JSONException {
    if (edit.lookups == null) {
      return new JSONObject();
    }
    // The ROW's organization, as the worker used: not the session's, which may differ.
    String orgId = edit.row == null || edit.row.getOrganization() == null ? null
        : edit.row.getOrganization().getId();
    return new InboxNewProductDefaults(edit.lookups, orgId).draftFor(text(line, K_UNIT_PRINTED));
  }

  /**
   * Resolves the category (or the unit) of the draft: the one asked for (its id is checked), the
   * base one when the operation does not mention it, none when {@code null}. Only the ID and its
   * source are stored: the name is a display value that {@link InvoiceInboxReview} adds at read
   * time in the language of the request, so a stored copy can never be stale or in another
   * language. A name stored by an earlier version is ignored here and dropped on the next write.
   * The source ({@code ocr}, {@code default}) is kept while the id is the base one and becomes
   * {@code manual} as soon as the user picks another.
   */
  private static void resolveChoice(InvoiceInboxApply.Edit edit, JSONObject op,
      JSONObject base, JSONObject draft, int index, boolean category) throws JSONException {
    String idKey = category ? K_CATEGORY_ID : K_UOM_ID;
    String sourceKey = category ? K_CATEGORY_SOURCE : K_UOM_SOURCE;
    if (!op.has(idKey)) {
      keepBase(base, draft, idKey, sourceKey);
      return;
    }
    Object value = op.get(idKey);
    if (JSONObject.NULL.equals(value)) {
      return;
    }
    Optional<Party> found = findChoice(edit, (String) value, category);
    if (found.isEmpty()) {
      throw new InvoiceInboxApply.Refusal(InvoiceInboxEditGate.refuse(422,
          category ? InvoiceInboxApply.CODE_UNKNOWN_CATEGORY : InvoiceInboxApply.CODE_UNKNOWN_UOM,
          category ? InvoiceInboxMessages.UNKNOWN_CATEGORY : InvoiceInboxMessages.UNKNOWN_UOM,
          InvoiceInboxEditGate.details(K_INDEX, index)));
    }
    String id = found.get().id();
    draft.put(idKey, id);
    if (id.equals(text(base, idKey))) {
      keepSource(base, draft, sourceKey);
    } else {
      draft.put(sourceKey, SRC_MANUAL);
    }
  }

  private static void keepBase(JSONObject base, JSONObject draft, String idKey, String sourceKey)
      throws JSONException {
    if (!StringUtils.isBlank(text(base, idKey))) {
      draft.put(idKey, text(base, idKey));
      keepSource(base, draft, sourceKey);
    }
  }

  private static void keepSource(JSONObject base, JSONObject draft, String sourceKey)
      throws JSONException {
    if (!StringUtils.isBlank(text(base, sourceKey))) {
      draft.put(sourceKey, text(base, sourceKey));
    }
  }

  /** The category (or unit) with this id for the session, empty when there is none. */
  private static Optional<Party> findChoice(InvoiceInboxApply.Edit edit, String id,
      boolean category) {
    if (edit.lookups == null) {
      return Optional.empty();
    }
    return category ? edit.lookups.productCategoryById(id) : edit.lookups.uomById(id);
  }

  /** The name a stored draft without one has is the line description ({@code currentName}). */
  private static boolean sameDraft(JSONObject stored, JSONObject draft, String currentName) {
    return currentName.equals(text(draft, K_NAME))
        && text(stored, K_CATEGORY_ID).equals(text(draft, K_CATEGORY_ID))
        && text(stored, K_UOM_ID).equals(text(draft, K_UOM_ID));
  }

  private static JSONArray candidatesOf(JSONObject product) throws JSONException {
    JSONArray candidates = product == null ? null : product.optJSONArray(K_CANDIDATES);
    return candidates == null ? new JSONArray() : new JSONArray(candidates.toString());
  }

  // ── Currency (ETP-6051) ──────────────────────────────────────────────────────────────────────

  /**
   * {@code setCurrency {currencyId}}: the user picks the document currency among those registered
   * for the organization (the organization currency, or one with a conversion range to or from
   * it: exactly what the invoice's own selector offers). No custom rate: the rate is the one
   * registered for the invoice date, read at review time. Line prices are not touched.
   *
   * <p>The chosen currency becomes {@code currencySource = manual} (so it is the user's choice, and the
   * source says where the currency came from), drops {@code currencyUnavailable} and the {@code currency_symbol_ambiguous} notice, which
   * no longer apply. Choosing the stored currency changes nothing and never marks.</p>
   */
  static void setCurrency(InvoiceInboxApply.Edit edit, JSONObject op) throws JSONException {
    String id = op.getString("currencyId");
    JSONObject header = edit.header();
    boolean same = id.equals(text(header, K_CURRENCY_ID));
    // The currency the review shows (the stored one) is accepted without the "selectable" rule:
    // it may be a vendor default with no conversion range, and the live verdict then decides
    // whether the exchange-rate blocker stays. Any other id must be one the selector offers.
    if (!isUsableCurrency(edit, id) || (!same && !isSelectableCurrency(edit, id))) {
      throw new InvoiceInboxApply.Refusal(InvoiceInboxEditGate.refuse(422,
          InvoiceInboxApply.CODE_INVALID_CURRENCY, InvoiceInboxMessages.INVALID_CURRENCY, null));
    }
    // Accepting the currency already shown still has to be persisted when it was not yet the
    // user's choice (source not manual, or an unavailable printed currency still flagged): that is
    // what makes the choice sticky and clears the exchange-rate blocker. It is not an adjustment.
    boolean pending = header.has(K_CURRENCY_UNAVAILABLE)
        || !CURRENCY_MANUAL.equals(text(header, K_CURRENCY_SOURCE));
    if (same && !pending) {
      return;
    }
    header.put(K_CURRENCY_ID, id);
    header.put(K_CURRENCY_SOURCE, CURRENCY_MANUAL);
    header.remove(K_CURRENCY_UNAVAILABLE);
    removeAmbiguityNotice(edit.document);
    edit.changed(InvoiceInboxAdjustedPolicy.currencyChanged(same));
  }

  /** Exists, is active and belongs to the system or to the row's tenant. */
  private static boolean isUsableCurrency(InvoiceInboxApply.Edit edit, String id) {
    Currency currency = OBDal.getInstance().get(Currency.class, id);
    if (currency == null || !Boolean.TRUE.equals(currency.isActive())
        || currency.getClient() == null || edit.row.getClient() == null) {
      return false;
    }
    String owner = currency.getClient().getId();
    return "0".equals(owner) || owner.equals(edit.row.getClient().getId());
  }

  /** The organization currency or one the invoice currency selector offers. */
  private static boolean isSelectableCurrency(InvoiceInboxApply.Edit edit, String id) {
    String orgId = edit.row.getOrganization() == null ? null : edit.row.getOrganization().getId();
    String clientId = edit.row.getClient() == null ? null : edit.row.getClient().getId();
    String orgCurrencyId = orgId == null ? null : OBCurrencyUtils.getOrgCurrency(orgId);
    if (orgCurrencyId == null || clientId == null) {
      return false;
    }
    if (orgCurrencyId.equals(id)) {
      return true;
    }
    try {
      return CurrencyOptionsHandler.selectableCurrencies(OBDal.getInstance().getConnection(),
          orgCurrencyId, clientId, orgId).containsKey(id);
    } catch (SQLException e) {
      return false;
    }
  }

  private static void removeAmbiguityNotice(JSONObject document) throws JSONException {
    JSONArray notices = document.optJSONArray(K_NOTICES);
    if (notices == null) {
      return;
    }
    JSONArray kept = new JSONArray();
    for (int i = 0; i < notices.length(); i++) {
      if (!N_CURRENCY_SYMBOL_AMBIGUOUS.equals(notices.opt(i))) {
        kept.put(notices.get(i));
      }
    }
    document.put(K_NOTICES, kept);
  }
}
