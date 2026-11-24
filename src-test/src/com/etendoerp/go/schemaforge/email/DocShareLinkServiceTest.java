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

package com.etendoerp.go.schemaforge.email;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Savepoint;
import java.sql.Timestamp;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Supplier;

import org.hibernate.Session;
import org.hibernate.jdbc.Work;
import org.hibernate.query.NativeQuery;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBDal;
import org.openbravo.database.ExternalConnectionPool;

import com.etendoerp.go.schemaforge.email.DocumentDownloadTokenService.Claims;

/**
 * Unit tests for {@link DocShareLinkService} (ETP-5035): code uniqueness, the row committed on a
 * connection of its own, expiry and revocation filtering, and open counting.
 *
 * @covers com.etendoerp.go.schemaforge.email.DocShareLinkService
 */
public class DocShareLinkServiceTest {

  private static final String CODE = "aB3dE5gH7j";

  @Test
  public void generatedCodesAreUniqueUrlSafeAndFixedLength() {
    Set<String> seen = new HashSet<>();
    for (int i = 0; i < 20000; i++) {
      String code = DocShareLinkService.generateCode();
      assertEquals(DocShareLinkService.CODE_LENGTH, code.length());
      assertTrue(code, DocShareLinkService.isShareCode(code));
      assertTrue("collision on " + code, seen.add(code));
    }
  }

  @Test
  public void signedTokensAndMalformedValuesAreNotShareCodes() {
    assertFalse(DocShareLinkService.isShareCode(null));
    assertFalse(DocShareLinkService.isShareCode("short"));
    assertFalse(DocShareLinkService.isShareCode("aB3dE5gH7."));
    assertFalse(DocShareLinkService.isShareCode("aB3dE5gH7jk"));
    assertFalse(DocShareLinkService.isShareCode("aB3dE5g-7j"));
    assertFalse(DocShareLinkService.isShareCode("eyJ2IjoidjEifQ.c2ln"));
  }

  /**
   * The row is written on an autocommit pooled connection of its own, so it is committed before
   * the email goes out and a later rollback of the send transaction cannot take it back. A code
   * that is already taken is drawn again.
   */
  @Test
  public void createCommitsTheRowOnItsOwnConnectionAndRetriesATakenCode() throws Exception {
    ExternalConnectionPool pool = mock(ExternalConnectionPool.class);
    Connection connection = mock(Connection.class);
    when(pool.getConnection()).thenReturn(connection);
    PreparedStatement exists = mock(PreparedStatement.class);
    ResultSet taken = mock(ResultSet.class);
    when(taken.next()).thenReturn(true);
    ResultSet free = mock(ResultSet.class);
    when(free.next()).thenReturn(false);
    when(exists.executeQuery()).thenReturn(taken, free);
    PreparedStatement insert = mock(PreparedStatement.class);
    when(connection.prepareStatement(argThat(sql -> sql != null && sql.startsWith("SELECT 1"))))
        .thenReturn(exists);
    when(connection.prepareStatement(argThat(sql -> sql != null && sql.startsWith("INSERT"))))
        .thenReturn(insert);
    OBDal dal = mock(OBDal.class);
    long expiresAt = System.currentTimeMillis() + 1000L;

    String code = withPool(pool, dal,
        () -> DocShareLinkService.create("c", "s", "r", "t", "k", expiresAt));

    assertTrue(DocShareLinkService.isShareCode(code));
    Mockito.verify(exists, Mockito.times(2)).executeQuery();
    Mockito.verify(connection).setAutoCommit(true);
    Mockito.verify(insert).setString(2, "t");
    Mockito.verify(insert).setString(8, code);
    Mockito.verify(insert).setString(11, "r");
    Mockito.verify(insert).setString(12, "k");
    Mockito.verify(insert).setTimestamp(13, new Timestamp(expiresAt));
    Mockito.verify(insert).executeUpdate();
    Mockito.verify(connection).close();
    // The send transaction is never touched: no DAL session, no commit of the caller's work.
    Mockito.verifyNoInteractions(dal);
  }

  /** A failed insert reaches the caller, which falls back to the signed token. */
  @Test
  public void failedInsertPropagatesAndReleasesTheConnection() throws Exception {
    ExternalConnectionPool pool = mock(ExternalConnectionPool.class);
    Connection connection = mock(Connection.class);
    when(pool.getConnection()).thenReturn(connection);
    PreparedStatement exists = mock(PreparedStatement.class);
    ResultSet free = mock(ResultSet.class);
    when(exists.executeQuery()).thenReturn(free);
    PreparedStatement insert = mock(PreparedStatement.class);
    when(insert.executeUpdate()).thenThrow(new SQLException("unique violation"));
    when(connection.prepareStatement(argThat(sql -> sql != null && sql.startsWith("SELECT 1"))))
        .thenReturn(exists);
    when(connection.prepareStatement(argThat(sql -> sql != null && sql.startsWith("INSERT"))))
        .thenReturn(insert);
    OBDal dal = mock(OBDal.class);

    try {
      withPool(pool, dal,
          () -> DocShareLinkService.create("c", "s", "r", "t", "k", System.currentTimeMillis()));
      fail("the insert failure must propagate");
    } catch (IllegalStateException expected) {
      assertTrue(expected.getMessage(), expected.getMessage().contains("unique violation"));
    }

    Mockito.verify(connection).close();
    Mockito.verifyNoInteractions(dal);
  }

  @Test
  public void createWithoutAConnectionPoolFails() {
    try {
      withPool(null, mock(OBDal.class),
          () -> DocShareLinkService.create("c", "s", "r", "t", "k", System.currentTimeMillis()));
      fail("no pool, no row");
    } catch (IllegalStateException expected) {
      assertTrue(expected.getMessage(), expected.getMessage().contains("connection pool"));
    }
  }

  @Test
  public void resolveOnlyReadsLiveRowsAndMapsThemToClaims() {
    Session session = mock(Session.class);
    Timestamp expires = new Timestamp(System.currentTimeMillis() + 60000L);
    NativeQuery<?> select = query(Collections.singletonList(
        new Object[] { "sales-order-send", "sales-order", "rec", "client", "key", expires }));
    ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
    when(session.createNativeQuery(sql.capture())).thenReturn((NativeQuery) select);

    Claims claims = withSession(session, () -> DocShareLinkService.resolve(CODE)
        .orElseThrow(() -> new AssertionError("live link resolves")));

    assertEquals("sales-order", claims.getSpecName());
    assertEquals("rec", claims.getRecordId());
    assertEquals("client", claims.getClientId());
    assertEquals(null, claims.getAttachmentId());
    assertEquals(expires.getTime() / 1000L, claims.getExpiresAtSeconds());
    // Expiry and revocation are filtered by the query itself, so neither can be served.
    assertTrue(sql.getValue().contains("revoked = 'N'"));
    assertTrue(sql.getValue().contains("expires_at >= :now"));
  }

  @Test
  public void resolveIsEmptyForUnknownExpiredOrRevokedCodesAndNeverQueriesBadCodes() {
    Session session = mock(Session.class);
    NativeQuery<?> emptyQuery = query(Collections.emptyList());
    when(session.createNativeQuery(Mockito.anyString()))
        .thenReturn((NativeQuery) emptyQuery);

    assertFalse(withSession(session, () -> DocShareLinkService.resolve(CODE)).isPresent());
    assertFalse(withSession(session, () -> DocShareLinkService.resolve("not.a.code")).isPresent());
    Mockito.verify(session, Mockito.times(1)).createNativeQuery(Mockito.anyString());
  }

  @Test
  public void revokeKillsALiveLinkAndRecordOpenCountsIt() {
    Session session = mock(Session.class);
    NativeQuery<?> update = query(Collections.emptyList());
    when(update.executeUpdate()).thenReturn(1, 0);
    ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
    when(session.createNativeQuery(sql.capture())).thenReturn((NativeQuery) update);

    assertTrue(withSession(session, () -> DocShareLinkService.revoke(CODE)));
    assertTrue(sql.getValue().contains("SET revoked = 'Y'"));
    assertFalse(withSession(session, () -> DocShareLinkService.revoke(CODE)));

    withSession(session, () -> {
      DocShareLinkService.recordOpen(CODE);
      return null;
    });
    assertTrue(sql.getValue().contains("open_count = open_count + 1"));
    assertTrue(sql.getValue().contains("COALESCE(first_opened_at"));
  }

  /**
   * A failed open count rolls back to its savepoint, so the download request's transaction stays
   * usable.
   */
  @Test
  public void failedOpenCountRollsBackToTheSavepointSoTheRequestTransactionSurvives()
      throws Exception {
    Session session = mock(Session.class);
    Connection connection = mock(Connection.class);
    Savepoint savepoint = mock(Savepoint.class);
    when(connection.setSavepoint()).thenReturn(savepoint);
    Mockito.doAnswer(invocation -> {
      invocation.<Work>getArgument(0).execute(connection);
      return null;
    }).when(session).doWork(Mockito.any(Work.class));
    NativeQuery<?> update = query(Collections.emptyList());
    when(update.executeUpdate()).thenThrow(new IllegalStateException("lock timeout"));
    when(session.createNativeQuery(anyString())).thenReturn((NativeQuery) update);

    try {
      withSession(session, () -> {
        DocShareLinkService.recordOpen(CODE);
        return null;
      });
      fail("the update failure must propagate");
    } catch (IllegalStateException expected) {
      assertEquals("lock timeout", expected.getMessage());
    }

    Mockito.verify(connection).rollback(savepoint);
    Mockito.verify(connection, Mockito.never()).releaseSavepoint(Mockito.any());
  }

  private static NativeQuery<?> query(List<?> rows) {
    NativeQuery<?> query = mock(NativeQuery.class);
    when(query.setParameter(Mockito.anyString(), Mockito.any())).thenReturn((NativeQuery) query);
    when(query.list()).thenReturn((List) rows);
    return query;
  }

  private static <T> T withSession(Session session, Supplier<T> action) {
    OBDal dal = mock(OBDal.class);
    when(dal.getSession()).thenReturn(session);
    try (MockedStatic<OBDal> obDal = Mockito.mockStatic(OBDal.class);
        MockedStatic<OBContext> context = Mockito.mockStatic(OBContext.class)) {
      obDal.when(OBDal::getInstance).thenReturn(dal);
      context.when(OBContext::getOBContext).thenReturn(null);
      return action.get();
    }
  }

  private static <T> T withPool(ExternalConnectionPool pool, OBDal dal, Supplier<T> action) {
    try (MockedStatic<ExternalConnectionPool> poolStatic =
        Mockito.mockStatic(ExternalConnectionPool.class);
        MockedStatic<OBDal> obDal = Mockito.mockStatic(OBDal.class);
        MockedStatic<OBContext> context = Mockito.mockStatic(OBContext.class)) {
      poolStatic.when(ExternalConnectionPool::getInstance).thenReturn(pool);
      obDal.when(OBDal::getInstance).thenReturn(dal);
      context.when(OBContext::getOBContext).thenReturn(null);
      return action.get();
    }
  }
}
