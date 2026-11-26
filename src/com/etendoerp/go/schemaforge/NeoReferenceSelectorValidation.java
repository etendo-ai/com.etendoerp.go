/*
 * *************************************************************************
 * The contents of this file are subject to the Etendo License
 * (the "License"), you may not use this file except in compliance with
 * the License.
 * You may obtain a copy of the License at
 * https://github.com/etendosoftware/etendo_core/blob/main/legal/Etendo_license.txt
 * Software distributed under the License  is  distributed  on  an  "AS IS"
 * basis, WITHOUT WARRANTY OF ANY KIND, either express or implied. See the
 * License for the specific language governing rights and limitations under
 * the License.
 * All portions are Copyright © 2021-2026 FUTIT SERVICES, S.L
 * All Rights Reserved.
 * Contributor(s): Futit Services S.L.
 * *************************************************************************
 */

package com.etendoerp.go.schemaforge;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.codehaus.jettison.json.JSONException;
import org.codehaus.jettison.json.JSONObject;
import org.openbravo.base.model.Entity;
import org.openbravo.base.model.ModelProvider;
import org.openbravo.base.model.Property;
import org.openbravo.base.provider.OBProvider;
import org.openbravo.base.structure.BaseOBObject;
import org.openbravo.client.kernel.KernelUtils;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.ad.datamodel.Column;
import org.openbravo.model.ad.ui.Tab;
import org.openbravo.service.json.DataResolvingMode;
import org.openbravo.service.json.DataToJsonConverter;

import com.etendoerp.go.schemaforge.data.SFEntity;
import com.etendoerp.go.schemaforge.selector.meta.SelectorMeta;
import com.etendoerp.go.schemaforge.util.NeoHandlerLookup;

/**
 * Refuses a NEO write that SETS a reference to a record the app would not offer for it (ETP-6176).
 *
 * <p><b>The rule.</b> If the app allows it, the API allows it; if the app does not, the API does
 * not. A reference the request sets is accepted only when the selector the UI shows for that field
 * — validation rule, selector policies and organization scoping, all of it, through
 * {@link NeoSelectorService#offersValue} — lists it. When the entity serves the field's selector
 * from its own SELECTOR customization, that customization is the selector the UI shows, so it is
 * the one asked ({@link NeoCustomizedSelectorCheck}, ETP-6377). Ownership
 * ({@link NeoReferenceOwnership}) and activeness ({@link NeoReferenceActiveness}) are checked before and stay as they are.
 *
 * <p><b>A field with its own picker (ETP-6365).</b> When the entity's customization answers
 * {@link NeoHandler#offersReference} for a field, its answer replaces the generic selector's: the
 * field is picked in the app through the customization's own list, not through the selector. A
 * document header's currency is the case — chosen from its {@code currencyOptions} action (every
 * currency with a conversion range against the organization currency), while the generic
 * {@code Currency} selector keeps its EUR/USD/GBP allowlist for every other picker.
 *
 * <p><b>Context.</b> The UI resolves a selector against the whole form. Here the form is the
 * <i>merged record</i>: the parent record (child tabs; found through {@code parentId} or the
 * link-to-parent value the request sets), overlaid by the stored record (updates), overlaid by the
 * incoming values (on update, the callout outputs are already in them), completed with the tab's
 * literal auxiliary inputs (ETP-6381). Every property is published under its DAL name and under its
 * column name, which is how validation rules ({@code @C_BPartner_ID@}) and selector policies
 * ({@code businessPartner}) read it. So an address must belong to the partner, a price list must
 * match the document direction, a tax must be valid for the line, and a payment method must suit
 * the direction, with no entity named here.
 *
 * <p><b>Changed, or dependent on a change.</b> A reference is re-validated when its own value
 * changes, or when its selector reads a field the update changes (a new business partner re-checks
 * the stored address). Untouched references whose context did not change are skipped: an update
 * must not start failing on legacy data because the world moved on.
 *
 * <p><b>Subquery rules (ETP-6165).</b> {@code SelectorValidationResolver} skips a validation clause
 * with a nested subquery rather than translate it to HQL. Decision: this check does not add its own
 * translation. Selectors whose rule is raw SQL are routed through core's ComboTableData, which runs
 * the whole rule, so those are enforced; where a clause is skipped the write is accepted — the
 * clause is never applied half-way, and a valid record is never refused on a guess.
 *
 * <p><b>What the server proposes is accepted (ETP-6452).</b> On a create only, a value the selector
 * does not offer is still accepted when it is exactly what the base defaults
 * ({@link NeoDefaultsService#resolveDefaults}, without any DEFAULTS customization) propose for the
 * field in the record's context (the other fields the request sets, and the parent) — a client
 * echoing the defaults back must never be refused. A sales representative defaulted from the
 * session user, who has no business partner, is the case. Asked only on the refusal path, so an
 * offered value pays nothing; updates are unchanged.
 *
 * <p><b>Fail closed.</b> A selector that cannot answer (error, unverifiable) refuses the write, like
 * one that answered and did not list the record.
 */
public final class NeoReferenceSelectorValidation {

  private static final Logger log = LogManager.getLogger(NeoReferenceSelectorValidation.class);
  private static final DateTimeFormatter CLASSIC_DATE = DateTimeFormatter.ofPattern("dd-MM-yyyy");
  private static final String YES = "Y";
  private static final String FIELD_PREFIX = "Field '";
  private static final String NO = "N";
  private static final String ID = "id";
  private static final Pattern CONTEXT_VARIABLE = Pattern.compile("@(\\w+)@");

  private NeoReferenceSelectorValidation() {
  }

  /**
   * Finds the first reference the request sets that the app's selector would not offer.
   *
   * @param sfEntity      the Schema Forge entity written; may be {@code null}
   * @param adTab         the tab being written
   * @param recordId      the id of the record being updated, {@code null} on create
   * @param parentId      the parent record id of a child create, or {@code null}
   * @param requestFields the body keyed by DAL property name, holding ONLY what the request set
   * @return the offending field and target entity, or {@code null} when every reference is offered
   */
  public static RefusedReference findRefusedReference(SFEntity sfEntity, Tab adTab,
      String recordId, String parentId, JSONObject requestFields) {
    if (adTab == null || adTab.getTable() == null || requestFields == null
        || OBContext.getOBContext() == null) {
      return null;
    }
    Entity written = ModelProvider.getInstance().getEntityByTableId(adTab.getTable().getId());
    if (written == null) {
      return null;
    }
    OBContext.setAdminMode(true);
    String[] current = new String[1];
    try {
      RefusedReference conflict = conflictingIdSpelling(written, requestFields);
      if (conflict != null) {
        return conflict;
      }
      return firstRefused(sfEntity, adTab, written, recordId, parentId,
          withIdSpellingFolded(written, requestFields), current);
    } catch (RuntimeException e) {
      // Fail closed: a selector check that errors cannot vouch for the value.
      log.warn("Selector validation of a NEO write failed, refusing it: {}", e.getMessage());
      return new RefusedReference(current[0] == null ? "record" : current[0], "unknown", null,
          false);
    } finally {
      OBContext.restorePreviousMode();
    }
  }

  /**
   * Refuses (fail closed) a body that sets a reference as both {@code x} and {@code x.id} with
   * different ids: only one of them can be selector-checked, and persistence may keep the other.
   */
  private static RefusedReference conflictingIdSpelling(Entity written, JSONObject requestFields) {
    Iterator<?> keys = requestFields.keys();
    while (keys.hasNext()) {
      String key = String.valueOf(keys.next());
      String name = idSpelledReference(written, key);
      if (name == null || !requestFields.has(name)) {
        continue;
      }
      Property property = written.getProperty(name, false);
      String plain = idText(requestFields.opt(name));
      String spelled = idText(requestFields.opt(key));
      if (!StringUtils.equalsIgnoreCase(plain, spelled)) {
        log.warn("Refused a NEO write: '{}' and '{}' carry different ids", name, key);
        return new RefusedReference(key, property.getTargetEntity().getName(), spelled, false,
            "Fields '" + name + "' and '" + key + "' carry different ids ('" + plain + "' and '"
                + spelled + "'); send the reference once. The write was rejected.");
      }
    }
    return null;
  }

  /**
   * The reference name a {@code <reference>.id} key spells, or {@code null} when the key is not
   * that spelling of a plain (non-nested) reference of the entity.
   */
  private static String idSpelledReference(Entity written, String key) {
    String suffix = "." + ID;
    String name = key.endsWith(suffix) ? key.substring(0, key.length() - suffix.length()) : "";
    if (name.isEmpty() || name.indexOf('.') >= 0) {
      return null;
    }
    Property property = written.getProperty(name, false);
    return property == null || property.isPrimitive() || property.getTargetEntity() == null
        ? null : name;
  }

  private static String idText(Object value) {
    Object raw = value instanceof JSONObject ? ((JSONObject) value).opt(ID) : value;
    return raw == null || JSONObject.NULL.equals(raw) ? null : String.valueOf(raw);
  }

  /**
   * A copy of the request where a {@code <reference>.id} key reads as {@code <reference>}: that is
   * how {@link NeoReferenceOwnership} understands the spelling, and {@code getProperty} would
   * resolve it to the target's id property, leaving the reference unchecked. A key already sent
   * in its plain form wins.
   */
  private static JSONObject withIdSpellingFolded(Entity written, JSONObject requestFields) {
    JSONObject folded = null;
    Iterator<?> keys = requestFields.keys();
    while (keys.hasNext()) {
      String key = String.valueOf(keys.next());
      String name = idSpelledReference(written, key);
      if (name == null || requestFields.has(name)) {
        continue;
      }
      try {
        if (folded == null) {
          folded = new JSONObject(requestFields.toString());
        }
        folded.remove(key);
        folded.put(name, requestFields.get(key));
      } catch (JSONException e) {
        // Fail closed: the caller refuses a check that errors.
        throw new IllegalStateException(e);
      }
    }
    return folded == null ? requestFields : folded;
  }

  private static RefusedReference firstRefused(SFEntity sfEntity, Tab adTab, Entity written,
      String recordId, String parentId, JSONObject requestFields, String[] current) {
    BaseOBObject stored = StringUtils.isBlank(recordId) ? null : load(written, recordId);
    RefusedReference disagreement = parentDisagreement(adTab, written, stored, parentId,
        requestFields);
    if (disagreement != null) {
      return disagreement;
    }
    Set<String> changed = changedKeys(written, stored, requestFields);
    OfferInputs inputs = new OfferInputs(sfEntity, adTab, written, stored, parentId, requestFields);
    for (Property property : written.getProperties()) {
      String key = property.getName();
      boolean sent = requestFields.has(key);
      String id = referenceId(written, key, sent ? requestFields.opt(key) : storedId(stored, key));
      Column column = id == null ? null : columnOf(adTab, property);
      if (column == null || !mustValidate(column, key, sent, changed, stored)) {
        continue;
      }
      current[0] = key;
      Map<String, String> context = inputs.context();
      RefusedReference refused = check(sfEntity, inputs.customization(), column, property, id,
          context);
      if (refused != null && stored == null && inputs.proposedByDefault(key, id)) {
        log.debug("Accepted '{}' on a create: not offered by its selector, but it is the value"
            + " the server proposes for it", key);
        refused = null;
      }
      if (refused != null) {
        return refused;
      }
    }
    return null;
  }

  /**
   * The merged context and the entity's customization, built once and only when a reference must
   * actually be judged: a write that sets no reference pays for neither.
   */
  private static final class OfferInputs {
    private final SFEntity sfEntity;
    private final Tab adTab;
    private final Entity written;
    private final BaseOBObject stored;
    private final String parentId;
    private final JSONObject requestFields;
    private Map<String, String> context;
    private NeoHandler customization;
    private boolean customizationResolved;

    OfferInputs(SFEntity sfEntity, Tab adTab, Entity written, BaseOBObject stored,
        String parentId, JSONObject requestFields) {
      this.sfEntity = sfEntity;
      this.adTab = adTab;
      this.written = written;
      this.stored = stored;
      this.parentId = parentId;
      this.requestFields = requestFields;
    }

    Map<String, String> context() {
      if (context == null) {
        context = mergedContext(adTab, written, stored, parentId, requestFields);
      }
      return context;
    }

    NeoHandler customization() {
      if (!customizationResolved) {
        customization = NeoHandlerLookup.forEntityQuietly(sfEntity);
        customizationResolved = true;
      }
      return customization;
    }

    /**
     * Whether {@code id} is exactly what the base defaults
     * ({@link NeoDefaultsService#resolveDefaults}; a DEFAULTS customization is not consulted)
     * propose for {@code key} on this create
     * (ETP-6452): what the server offers a client to send back must never be refused as "not
     * offered". Asked only on the refusal path of a create, so an accepted write pays nothing.
     *
     * <p>The defaults are computed in the record's context — the other fields the request sets,
     * passed as the {@code /defaults?field=value} record context (ETP-5852), and the parent — so a
     * value derived from another field (a currency from the financial account) is the one derived
     * from the field the client actually sent. The judged field itself is never in that context,
     * or it would trivially "default" to whatever was sent. Defaults that cannot be computed
     * accept nothing.
     */
    boolean proposedByDefault(String key, String id) {
      if (sfEntity == null) {
        return false;
      }
      try {
        Map<String, String> recordContext = new HashMap<>();
        Iterator<?> keys = requestFields.keys();
        while (keys.hasNext()) {
          String name = String.valueOf(keys.next());
          String value = name.equals(key) ? null : recordContextValue(requestFields.opt(name));
          if (value != null) {
            recordContext.put(name, value);
          }
        }
        // Another request field's callout can derive this one, so two fields could vouch for each
        // other; accepted on purpose — the form would derive the same values from the same input.
        NeoContext defaultsContext = NeoContext.builder()
            .specName(sfEntity.getETGOSFSpec() == null ? null : sfEntity.getETGOSFSpec().getName())
            .entityName(sfEntity.getName())
            .httpMethod("GET")
            .adTab(adTab)
            .sfEntity(sfEntity)
            .obContext(OBContext.getOBContext())
            .queryParams(recordContext)
            .build();
        String parent = effectiveParent(written, parentLink(adTab, written), parentId, null,
            requestFields);
        NeoResponse response = NeoDefaultsService.resolveDefaults(defaultsContext, parent);
        JSONObject defaults = response == null || response.getHttpStatus() >= 400
            || response.getBody() == null ? null : response.getBody().optJSONObject("defaults");
        return defaults != null && id.equals(idText(defaults.opt(key)));
      } catch (RuntimeException e) {
        log.debug("Could not compute the default of '{}' to compare it: {}", key, e.getMessage());
        return false;
      }
    }

    /** A request value as the {@code /defaults} record context carries it, or {@code null}. */
    private static String recordContextValue(Object value) {
      Object raw = value instanceof JSONObject ? ((JSONObject) value).opt(ID) : value;
      if (raw instanceof Boolean) {
        return Boolean.TRUE.equals(raw) ? YES : NO;
      }
      return raw instanceof String || raw instanceof Number ? String.valueOf(raw) : null;
    }
  }

  /**
   * Whether the app offers {@code id} for the property: the entity's customization answers when
   * the field's picker is its own ({@link NeoHandler#offersReference}, ETP-6365), the selector the
   * app shows for the field otherwise ({@link #offeredByTheAppSelector}).
   */
  private static RefusedReference check(SFEntity sfEntity, NeoHandler customization,
      Column column, Property property, String id, Map<String, String> context) {
    String key = property.getName();
    Optional<Boolean> offered = customization == null ? Optional.empty()
        : customization.offersReference(key, id, context);
    if (offered.isEmpty()) {
      offered = offeredByTheAppSelector(sfEntity, column, key, id, context);
    }
    if (Boolean.TRUE.equals(offered.orElse(null))) {
      return null;
    }
    log.warn("Refused a NEO write: '{}' is {} by its selector for this record", key,
        offered.isPresent() ? "not offered" : "not verifiable");
    return new RefusedReference(key, property.getTargetEntity().getName(), id,
        offered.isPresent());
  }

  /**
   * The selector the app shows for the field: the entity's own SELECTOR customization when it
   * serves the field (ETP-6377), otherwise the generic selector.
   */
  private static Optional<Boolean> offeredByTheAppSelector(SFEntity sfEntity, Column column,
      String key, String id, Map<String, String> context) {
    switch (NeoCustomizedSelectorCheck.offers(sfEntity, key, id, context)) {
      case OFFERED:
        return Optional.of(Boolean.TRUE);
      case NOT_OFFERED:
        return Optional.of(Boolean.FALSE);
      case UNVERIFIABLE:
        return Optional.empty();
      default:
        return NeoSelectorService.offersValue(sfEntity, column, key, id, context);
    }
  }

  /**
   * A reference is validated when the request changes its own value, or — on an update — when its
   * selector reads a field the request changed (so a new business partner re-checks the stored
   * address). A selector whose dependencies cannot be read is validated whenever anything changed.
   */
  private static boolean mustValidate(Column column, String key, boolean sent,
      Set<String> changed, BaseOBObject stored) {
    if (stored == null) {
      return sent;
    }
    if (sent && changed.contains(key.toLowerCase(Locale.ROOT))) {
      return true;
    }
    if (changed.isEmpty()) {
      return false;
    }
    Optional<Set<String>> dependencies = dependenciesOf(column);
    return dependencies.isEmpty() || dependencies.get().stream().anyMatch(changed::contains);
  }

  /**
   * The context names (lower-cased) the selector of the column reads through {@code @Name@}
   * variables in its validation rule and selector query; empty when it cannot be read.
   */
  private static Optional<Set<String>> dependenciesOf(Column column) {
    try {
      StringBuilder text = new StringBuilder();
      if (column.getValidation() != null && column.getValidation().getValidationCode() != null) {
        text.append(column.getValidation().getValidationCode());
      }
      if (NeoSelectorService.hasObuiselSelector(column)) {
        SelectorMeta meta = NeoSelectorService.resolveTarget(column,
            NeoSelectorService.getBaseReferenceId(column));
        if (meta == null) {
          return Optional.empty();
        }
        text.append(' ').append(StringUtils.defaultString(meta.whereClause)).append(' ')
            .append(StringUtils.defaultString(meta.customHql));
      }
      Set<String> names = new HashSet<>();
      Matcher matcher = CONTEXT_VARIABLE.matcher(text);
      while (matcher.find()) {
        names.add(matcher.group(1).toLowerCase(Locale.ROOT));
      }
      return Optional.of(names);
    } catch (RuntimeException e) {
      return Optional.empty();
    }
  }

  /** Lower-cased DAL and column names of every property whose value the request changes. */
  private static Set<String> changedKeys(Entity written, BaseOBObject stored,
      JSONObject requestFields) {
    Set<String> changed = new HashSet<>();
    Iterator<?> keys = requestFields.keys();
    while (keys.hasNext()) {
      String key = String.valueOf(keys.next());
      Property property = written.getProperty(key, false);
      if (property == null) {
        continue;
      }
      Object value = requestFields.opt(key);
      Object raw = value instanceof JSONObject ? ((JSONObject) value).opt(ID) : value;
      String newText = raw == null || JSONObject.NULL.equals(raw) ? null : String.valueOf(raw);
      if (!Objects.equals(textOf(stored == null ? null : stored.get(key)), newText)) {
        changed.add(key.toLowerCase(Locale.ROOT));
        if (property.getColumnName() != null) {
          changed.add(property.getColumnName().toLowerCase(Locale.ROOT));
        }
      }
    }
    return changed;
  }

  private static String textOf(Object old) {
    if (old == null) {
      return null;
    }
    return old instanceof BaseOBObject ? String.valueOf(((BaseOBObject) old).getId())
        : String.valueOf(old);
  }

  private static String storedId(BaseOBObject stored, String key) {
    Object value = stored == null ? null : stored.get(key);
    return value instanceof BaseOBObject ? String.valueOf(((BaseOBObject) value).getId()) : null;
  }

  /** The id a request key sets when it is a persisted reference, else {@code null}. */
  private static String referenceId(Entity entity, String key, Object value) {
    Property property = entity.getProperty(key, false);
    if (property == null || property.isId() || property.isPrimitive() || property.isOneToMany()
        || property.isInactive() || property.isProxy() || property.getTargetEntity() == null) {
      return null;
    }
    Object raw = value instanceof JSONObject ? ((JSONObject) value).opt(ID) : value;
    if (!(raw instanceof String || raw instanceof Number)) {
      return null;
    }
    String id = String.valueOf(raw);
    return NeoReferenceActiveness.isNotAReference(id) ? null : id;
  }

  private static Column columnOf(Tab adTab, Property property) {
    if (property == null || property.getColumnName() == null) {
      return null;
    }
    for (Column column : adTab.getTable().getADColumnList()) {
      if (Boolean.TRUE.equals(column.isActive())
          && property.getColumnName().equalsIgnoreCase(column.getDBColumnName())) {
        return column;
      }
    }
    return null;
  }

  private static BaseOBObject load(Entity entity, String id) {
    try {
      BaseOBObject row = OBDal.getInstance().get(entity.getName(), id);
      return TenantOwnership.belongsToReadableClient(row) ? row : null;
    } catch (RuntimeException e) {
      return null;
    }
  }

  /**
   * Parent record, overlaid by the stored record, overlaid by the incoming values, then the tab's
   * literal auxiliary inputs and the window direction for what the record does not say.
   *
   * <p>The parent is the explicit {@code parentId}, else the one the request sets in the link-to-
   * parent column (a line created with {@code shipmentReceipt} and no {@code parentId}), else the
   * stored record's. Without it a line selector that reads a header field — a storage bin reading
   * the receipt's warehouse — offers nothing.
   */
  private static Map<String, String> mergedContext(Tab adTab, Entity written,
      BaseOBObject stored, String parentId, JSONObject requestFields) {
    Map<String, String> context = new HashMap<>();
    Entity parentEntity = parentEntity(adTab);
    Property parentLink = parentLink(adTab, written);
    String effectiveParent = effectiveParent(written, parentLink, parentId, stored,
        requestFields);
    if (parentEntity != null && StringUtils.isNotBlank(effectiveParent)) {
      BaseOBObject parent = load(parentEntity, effectiveParent);
      if (parent != null) {
        publish(context, parent.getEntity(), flat(parent));
      }
      if (parentLink != null) {
        publishValue(context, parentLink, effectiveParent);
      }
    }
    if (stored != null) {
      publish(context, written, flat(stored));
    }
    publish(context, written, requestFields);
    if (parentLink != null
        && (storedId(stored, parentLink.getName()) != null || StringUtils.isNotBlank(parentId))) {
      // The stored parent (update) or the explicit parentId (create) decides the context: the
      // request's link value must not replace it.
      publishValue(context, parentLink, effectiveParent);
    }
    addAuxiliaryLiterals(adTab, context);
    addWindowDirection(adTab, context);
    return context;
  }

  /**
   * Fail closed: a create whose explicit {@code parentId} and link-to-parent value name different
   * parents. Persistence takes one of them and this check judges the other.
   */
  private static RefusedReference parentDisagreement(Tab adTab, Entity written,
      BaseOBObject stored, String parentId, JSONObject requestFields) {
    Property link = parentLink(adTab, written);
    if (stored != null || link == null || StringUtils.isBlank(parentId)
        || !requestFields.has(link.getName())) {
      return null;
    }
    String sent = idText(requestFields.opt(link.getName()));
    if (sent == null || StringUtils.equalsIgnoreCase(sent, parentId)) {
      return null;
    }
    log.warn("Refused a NEO write: parentId and '{}' name different parents", link.getName());
    return new RefusedReference(link.getName(), link.getTargetEntity().getName(), sent, false,
        FIELD_PREFIX + link.getName() + "' names parent '" + sent + "' but parentId is '" + parentId
            + "'; send one parent, or the same one in both. The write was rejected.");
  }

  private static String effectiveParent(Entity written, Property parentLink, String parentId,
      BaseOBObject stored, JSONObject requestFields) {
    if (parentLink == null) {
      return parentId;
    }
    String linkName = parentLink.getName();
    String storedParent = storedId(stored, linkName);
    if (storedParent != null) {
      // An existing record keeps its parent: neither parentId nor the link value moves it.
      return storedParent;
    }
    if (StringUtils.isNotBlank(parentId)) {
      return parentId;
    }
    if (requestFields.has(linkName)) {
      String sent = referenceId(written, linkName, requestFields.opt(linkName));
      if (sent != null) {
        return sent;
      }
    }
    return null;
  }

  /**
   * The tab's literal auxiliary inputs (Sales Quotation's {@code isQuotation=Y}, a return window's
   * {@code isReturnMaterial=Y}), as Classic puts them in the form context. A value the record
   * already carries under any casing of the name wins: the record, not the window, says.
   */
  private static void addAuxiliaryLiterals(Tab adTab, Map<String, String> context) {
    Set<String> present = new HashSet<>();
    for (String key : context.keySet()) {
      present.add(key.toLowerCase(Locale.ROOT));
    }
    for (Map.Entry<String, String> literal : NeoAuxiliaryInputResolver.literalValues(adTab)
        .entrySet()) {
      String name = literal.getKey();
      if (present.add(name.toLowerCase(Locale.ROOT))) {
        context.put(name, literal.getValue());
        context.put(name.toUpperCase(Locale.ROOT), literal.getValue());
        context.put(name.toLowerCase(Locale.ROOT), literal.getValue());
      }
    }
  }

  private static Entity parentEntity(Tab adTab) {
    Tab parentTab = adTab.getTabLevel() == null || adTab.getTabLevel() == 0 ? null
        : KernelUtils.getInstance().getParentTab(adTab);
    return parentTab == null || parentTab.getTable() == null ? null
        : ModelProvider.getInstance().getEntityByTableId(parentTab.getTable().getId());
  }

  private static Property parentLink(Tab adTab, Entity written) {
    for (Column column : adTab.getTable().getADColumnList()) {
      if (Boolean.TRUE.equals(column.isActive())
          && Boolean.TRUE.equals(column.isLinkToParentColumn())) {
        return written.getPropertyByColumnName(column.getDBColumnName());
      }
    }
    return null;
  }

  private static JSONObject flat(BaseOBObject entityRecord) {
    try {
      return OBProvider.getInstance().get(DataToJsonConverter.class)
          .toJsonObject(entityRecord, DataResolvingMode.FULL);
    } catch (RuntimeException e) {
      return new JSONObject();
    }
  }

  private static void publish(Map<String, String> context, Entity entity, JSONObject values) {
    Iterator<?> keys = values.keys();
    while (keys.hasNext()) {
      String key = String.valueOf(keys.next());
      Property property = entity.getProperty(key, false);
      Object value = values.opt(key);
      if (value instanceof JSONObject) {
        value = ((JSONObject) value).opt(ID);
      }
      if (property != null && value != null && !JSONObject.NULL.equals(value)
          && (value instanceof String || value instanceof Number || value instanceof Boolean)) {
        publishValue(context, property, String.valueOf(value));
      }
    }
  }

  /**
   * Publishes a value under the property's DAL name and its column name. Each layer overlays the
   * previous one (parent, stored row, request), so any other casing of those names an earlier layer
   * wrote is dropped first: the selector reads rule variables regardless of case (ETP-6452), and a
   * parent's {@code FIN_X} must not compete with the record's {@code Fin_X}.
   */
  private static void publishValue(Map<String, String> context, Property property, String value) {
    String text = value;
    if (property.isBoolean()) {
      text = Boolean.parseBoolean(value) || YES.equals(value) ? YES : NO;
    }
    String name = property.getName();
    String column = property.getColumnName();
    // The DAL and column names may differ only in case ("description" / "Description"): both are
    // kept, only casings an earlier layer wrote are dropped.
    context.keySet().removeIf(key -> !key.equals(name) && !key.equals(column)
        && (key.equalsIgnoreCase(name) || key.equalsIgnoreCase(column)));
    context.put(name, text);
    if (column != null) {
      context.put(column, classicDate(property, text));
    }
  }

  private static String classicDate(Property property, String value) {
    if (!property.isDate() && !property.isDatetime() || value.length() < 10) {
      return value;
    }
    try {
      return LocalDate.parse(value.substring(0, 10)).format(CLASSIC_DATE);
    } catch (DateTimeParseException e) {
      return value;
    }
  }

  /**
   * The window's sales flag is the document direction when the record does not say. A flag the
   * record already carries under any casing ({@code Isreceipt}) wins: the selector reads rule
   * variables regardless of case (ETP-6452), so a window value under another casing would shadow
   * the record's.
   */
  private static void addWindowDirection(Tab adTab, Map<String, String> context) {
    if (adTab.getWindow() == null || adTab.getWindow().isSalesTransaction() == null) {
      return;
    }
    Set<String> present = new HashSet<>();
    for (String key : context.keySet()) {
      present.add(key.toLowerCase(Locale.ROOT));
    }
    if (present.contains("issotrx")) {
      return;
    }
    String flag = adTab.getWindow().isSalesTransaction() ? YES : NO;
    context.put("IsSOTrx", flag);
    context.put("isSOTrx", flag);
    if (!present.contains("isreceipt")) {
      context.put("IsReceipt", flag);
      context.put("Isreceipt", flag);
    }
    context.putIfAbsent(YES.equals(flag) ? "isCustomer" : "isVendor", YES);
  }

  /** The field (DAL property name) refused, its target entity, the refused value and the cause. */
  public static final class RefusedReference {
    private final String fieldName;
    private final String targetEntityName;
    private final String value;
    private final boolean verified;
    private final String ownDetail;

    RefusedReference(String fieldName, String targetEntityName, String value, boolean verified) {
      this(fieldName, targetEntityName, value, verified, null);
    }

    /** A refusal that is not a selector answer, with the detail that says what was wrong. */
    RefusedReference(String fieldName, String targetEntityName, String value, boolean verified,
        String ownDetail) {
      this.fieldName = fieldName;
      this.targetEntityName = targetEntityName;
      this.value = value;
      this.verified = verified;
      this.ownDetail = ownDetail;
    }

    /** @return the DAL property name refused */
    public String fieldName() {
      return fieldName;
    }

    /** @return the DAL entity name the field references */
    public String targetEntityName() {
      return targetEntityName;
    }

    /** @return the refused candidate id, or {@code null} when the check itself failed */
    public String value() {
      return value;
    }

    /** @return {@code true} when the selector answered and did not offer the value */
    public boolean isVerified() {
      return verified;
    }

    /** @return the human detail shared by the REST and MCP 422 bodies */
    public String detail() {
      if (ownDetail != null) {
        return ownDetail;
      }
      if (verified) {
        return FIELD_PREFIX + fieldName + "' was set to '" + value + "', a record of '"
            + targetEntityName + "' that the app does not offer for this record; the write was"
            + " rejected exactly as the app would not have allowed the selection.";
      }
      String shown = value == null ? "" : " (value '" + value + "')";
      return FIELD_PREFIX + fieldName + "'" + shown
          + " could not be verified against the app's selector; the write was rejected.";
    }
  }
}
