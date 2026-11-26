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

import org.apache.commons.lang3.StringUtils;
import org.hibernate.criterion.Restrictions;
import org.openbravo.dal.service.OBCriteria;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.common.businesspartner.BusinessPartner;
import org.openbravo.model.pricing.pricelist.PriceList;

/**
 * The purchase price list that will price an invoice created from the inbox (ETP-6051), resolved in
 * ONE place for the review answer, the manual-line pricing and the tax-included confirm check.
 *
 * <p>The rule is the purchase invoice header's: the vendor's purchase price list
 * ({@code BusinessPartner.getPurchasePricelist()}); a vendor without one, a new vendor (not created
 * yet) or no vendor gets the default purchase price list (active, not a sales list, default), the
 * way the classic callout {@code SE_Invoice_BPartner} assigns it. Both lists must be visible to the
 * current tenant. Several default lists are possible in the data: the first by name, then id, wins,
 * so the answer is deterministic. Stateless and total: {@code null} when no list can be found.</p>
 */
final class InvoiceInboxPriceList {

  /** The resolved list: id and whether its prices include tax. */
  record Resolved(String id, boolean includesTax) {
  }

  private InvoiceInboxPriceList() {
  }

  /**
   * The price list of a document of this vendor.
   *
   * @param vendorId the linked vendor, {@code null} or blank when there is none yet
   * @return the list, {@code null} when neither the vendor nor the default has a visible one
   */
  static Resolved forVendor(String vendorId) {
    PriceList list = null;
    BusinessPartner vendor = StringUtils.isBlank(vendorId) ? null
        : OBDal.getInstance().get(BusinessPartner.class, vendorId);
    if (vendor != null && TenantOwnership.isVisibleToCurrentTenant(vendor)) {
      list = vendor.getPurchasePricelist();
    }
    if (list == null || !TenantOwnership.isVisibleToCurrentTenant(list)) {
      list = defaultPurchaseList();
    }
    if (list == null) {
      return null;
    }
    return new Resolved(list.getId(), Boolean.TRUE.equals(list.isPriceIncludesTax()));
  }

  private static PriceList defaultPurchaseList() {
    OBCriteria<PriceList> criteria = OBDal.getInstance().createCriteria(PriceList.class);
    criteria.add(Restrictions.eq(PriceList.PROPERTY_SALESPRICELIST, false));
    criteria.add(Restrictions.eq(PriceList.PROPERTY_DEFAULT, true));
    criteria.addOrderBy(PriceList.PROPERTY_NAME, true);
    criteria.addOrderBy("id", true);
    for (PriceList candidate : criteria.list()) {
      if (TenantOwnership.isVisibleToCurrentTenant(candidate)) {
        return candidate;
      }
    }
    return null;
  }
}
