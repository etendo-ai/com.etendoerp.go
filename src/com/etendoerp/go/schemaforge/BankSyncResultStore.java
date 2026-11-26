/*
 * *************************************************************************
 * The contents of this file are subject to the Etendo License
 * (the "License"), you may not use this file except in compliance with
 * the License.
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

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.apache.commons.lang3.StringUtils;

/**
 * Remembers, per financial account, how the last bank sync that ran on this node ended (ETP-6154):
 * the same {@code {status, message}} the {@code sync} action answered, whether it was started from
 * the SPA or from the MCP's {@code syncBank} (both go through the one handler), stamped with the
 * moment it finished. {@code syncStatus} / {@code bankSyncStatus} reads it as {@code lastResult}.
 *
 * <p>Keyed by the account id, so users syncing different accounts never touch each other's entry,
 * and the tenant guard stays where it already is ({@code loadAccount} before every write and
 * read). The store is in memory: it survives neither a restart nor reaches another node, and in
 * those cases {@code syncStatus} falls back to the Salt Edge fetch health of the account's
 * connection. It never holds the in-progress flag, which is the database-wide advisory lock of the
 * PSD2 module.</p>
 */
final class BankSyncResultStore {

  /** One finished sync: the wire status, the message, and when it finished. */
  static final class Result {
    private final String status;
    private final String message;
    private final Instant at;

    Result(String status, String message, Instant at) {
      this.status = status;
      this.message = message;
      this.at = at;
    }

    String getStatus() {
      return status;
    }

    String getMessage() {
      return message;
    }

    Instant getAt() {
      return at;
    }
  }

  // Bounded by the number of financial accounts: one entry per account, overwritten in place.
  private static final Map<String, Result> LAST_BY_ACCOUNT = new ConcurrentHashMap<>();

  private BankSyncResultStore() {
  }

  /**
   * Records the outcome of a sync that ran to its end (any status but {@code IN_PROGRESS}).
   *
   * @param accountId the financial account id; ignored when blank
   * @param status    the wire status ({@code OK}, {@code WARNING}, {@code ERROR})
   * @param message   the message the sync answered, possibly {@code null}
   */
  static void saveResult(String accountId, String status, String message) {
    if (StringUtils.isBlank(accountId) || StringUtils.isBlank(status)) {
      return;
    }
    LAST_BY_ACCOUNT.put(accountId, new Result(status, message, Instant.now()));
  }

  /**
   * The last recorded outcome of the account.
   *
   * @param accountId the financial account id
   * @return the result, or {@code null} when none was recorded on this node
   */
  static Result last(String accountId) {
    return accountId == null ? null : LAST_BY_ACCOUNT.get(accountId);
  }

  /** Clears every entry; for tests. */
  static void clear() {
    LAST_BY_ACCOUNT.clear();
  }
}
