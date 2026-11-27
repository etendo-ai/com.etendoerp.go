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
 * All portions are Copyright (C) 2026 FUTIT SERVICES, S.L
 * All Rights Reserved.
 * Contributor(s): Futit Services S.L.
 * *************************************************************************
 */
package com.etendoerp.go.schemaforge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

import java.io.PrintWriter;
import java.io.StringWriter;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBDal;
import org.openbravo.dal.service.OBQuery;

import com.etendoerp.psd2.bank.integration.data.PisPayment;

/**
 * Output of the bare, session-less PIS return servlet when it has no redirect target (ETP-4561,
 * sink inventory row 16). The fallback page is a static constant with an inline script: attacker
 * controlled query parameters must never reach it.
 *
 * @covers com.etendoerp.go.schemaforge.PisReturnCallbackServlet
 */
class PisReturnCallbackServletFallbackBodyTest {

  private static final String HOSTILE = "\"><script>alert(1)</script>'&javascript:x";

  private static String bodyFor(String paymentId, String errorClass, boolean unknownRow)
      throws Exception {
    HttpServletRequest request = mock(HttpServletRequest.class);
    when(request.getParameter("payment_id")).thenReturn(paymentId);
    when(request.getParameter("error_class")).thenReturn(errorClass);
    HttpServletResponse response = mock(HttpServletResponse.class);
    StringWriter out = new StringWriter();
    when(response.getWriter()).thenReturn(new PrintWriter(out));

    try (MockedStatic<OBContext> ctx = mockStatic(OBContext.class);
        MockedStatic<OBDal> dal = mockStatic(OBDal.class)) {
      if (unknownRow) {
        OBDal obDal = mock(OBDal.class);
        @SuppressWarnings("unchecked")
        OBQuery<PisPayment> query = mock(OBQuery.class);
        when(obDal.createQuery(org.mockito.ArgumentMatchers.eq(PisPayment.class), anyString()))
            .thenReturn(query);
        when(query.uniqueResult()).thenReturn(null);
        dal.when(OBDal::getInstance).thenReturn(obDal);
      }
      new PisReturnCallbackServlet().doGet(request, response);
    }
    return out.toString();
  }

  @Test
  void missingPaymentIdYieldsTheSameBodyWhateverTheOtherParamsSay() throws Exception {
    String plain = bodyFor(null, null, false);
    String hostile = bodyFor(" ", HOSTILE, false);
    assertEquals(plain, hostile);
    assertTrue(plain.contains("pis-completed"));
    assertFalse(hostile.contains("alert(1)"));
  }

  @Test
  void unknownHostilePaymentIdYieldsTheSameBodyAndIsNeverEchoed() throws Exception {
    String plain = bodyFor(null, null, false);
    String hostile = bodyFor(HOSTILE, HOSTILE, true);
    assertEquals(plain, hostile);
    assertFalse(hostile.contains("alert(1)"));
  }
}
