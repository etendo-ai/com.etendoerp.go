# ADR-0006: GO feature access (GO Access subtab)

Status: proposed (ETP-4802) — for the reviewer to validate. Copy of the PR's "Design decision" section.

## Design decision

The ticket left the mechanism undesigned. This change takes the smallest option that does not change
who has access to what today.

**Chosen: a "GO Access" subtab in the Role window.** New AD table `ETGO_Go_Access` (role, feature,
`IsReadWrite`; unique per role+feature) with its tab under Role (window 111). A feature is declared
in `GoFeature` (identifier, the classic record it was proxied through, its support tables); the
`Feature` list reference `ETGO_GoFeature` carries the same identifiers.

**One shared check:** `GoFeatureAccess` ("may this role use feature X with this method"):
1. admin / client-admin roles pass (exactly `NeoAccessHelper.isAdminOrClientAdmin`, the shortcut
   `hasWindowAccess` / `hasObuiappProcessAccess` already apply, so it grants nothing they do not);
2. an active GO Access row of the role decides (read: any row; write: `IsReadWrite = Y`, the
   `NeoAccessHelper.isWriteMethod` mapping);
3. with no row, the legacy classic grant (proxy window / OBUIAPP process) decides: a safety net for
   one release;
4. nothing else: a feature with neither is denied (no permissive fallback).

**Migrated to the shared check:** `AbstractFiscalHandler`, `NeoBuiltInEndpointHandler` (fiscal
models), `NotPostedDocumentsHandler`, `InventoryStockReportHandler`, the five financial report
handlers, `RoleAccessMatrix` / `ReportAccessCatalog` tiers (overlay), and `NeoAttachmentAuthorizer`
(`PROXY_WINDOWS_BY_TABLE` removed: table -> feature through `GoFeature.supportTableIds`).

**Alternatives discarded**
- Auto-provisioned dummy classic records (`dummy` flag in decisions.json / report-contract.json,
  provisioned by push-to-neo): needs generator work in schema_forge_core, UUID/export.database
  handling and keeps fabricating classic records; no repeatable way to guarantee they survive
  export.database. Larger scope and a second source of truth.
- Declaring features in decisions.json / the report contract: the access check lives in the backend
  and has to work for features that have no spec at all (Smart Scan, fiscal models).
- Declaring features in a data table (`ETGO_Go_Feature`): extra AD window/table with no benefit while
  the declaration also names Java-side support tables. **This deviates from the guidance** ("declare
  the features and their support tables in that data"): feature ids are data (the list reference),
  the legacy record and support-table mapping is the `GoFeature` enum. Accept or reject it here;
  follow-up if features must be added without a release.

## Role-template grants, existing roles and write paths (criterion met in this PR)

A GO Access row decides ahead of the classic grant, so a row that outlives a revoked classic grant
would keep a feature open. That is why the rows are kept in step with the classic grants in every
write path, and only then seeded:

- **`GoAccessLegacyMirror`** sets a role's row to the tier its classic grant resolves to, or deletes
  it when no active grant is left ("last write wins" per role and feature; features whose classic
  grant did not change are never touched, so an administrator's own rows survive unrelated edits).
- **`GoAccessLegacyMirrorObserver`** (DAL observer on `AD_Window_Access` and
  `OBUIAPP_Process_Access`) calls it for the six legacy records: Role window, template injectors,
  role composition and the roles-matrix editor all write through the DAL.
- **`TemplateAccessPropagationService#sweepRoles`** is native SQL, so it snapshots the six classic
  grants of the roles before the sweep and mirrors the differences after it (this is the path of
  template propagation, `UserRoleCompositionService` and overlap reconciliation).
- **`EnsureSystemRoleTemplatesScript`** keeps managing the four templates' classic grants (they are
  now declared through `GoFeature`, see `TemplateRoleWindowAccess#featureGrantsByRoleId`);
  **`SeedGoAccessScript`** (module script, every `update.database`, idempotent) mirrors the four
  templates exactly and seeds one row, at the same tier, for every other active role whose classic
  grant resolves today. It inserts with `ON CONFLICT DO NOTHING` on the unique index, so it never
  overwrites an existing row and a re-run adds nothing. Inactive grants and inactive roles are not
  seeded. A role keeps exactly the access it had.
- **Consequence for functional users:** GO Access is now where grants of these features are managed.
  Changing the classic Window/Process Access of a role for one of the six records overwrites that
  role's row of the feature; a direct GO Access edit stands until the classic grant of the same
  feature is next changed for the role.
- The legacy fallback in `GoFeatureAccess` stays for one release as a safety net. Retiring it and the
  classic proxy/anchor grants is clean-up beyond the criterion and has a follow-up issue.

## Frontend copies

`SFWindowAccessMap` adds the six GO feature ids (`fiscal-models`, `fiscal-monitor`,
`not-posted-documents`, `financial-reports`, `smart-scan`, `inventory-stock-report`) as keys of the
existing flat `windowAccess` map, with `full` / `read-only` from `GoFeatureAccess.effectiveTiers`
(absent = no access). A feature id never collides with a 32-character `AD_Window_ID`, and the core
`AuthContext` / `useWindowAccess` / `WindowAccessGuard` (schema_forge_core) read the map
unchanged, so the SPA needs no core release. (Deviation from the first plan, which had a separate
`featureAccess` map and a `useFeatureAccess` hook: that needed schema_forge_core changes in ten
session-controller paths for no behavioural gain.) In schema_forge the page guards
(`fiscal-models`, Fiscal Monitor, Smart Scan, Report viewer, not-posted documents) and the
`menu.json` entries of the six features use the feature ids instead of the proxy/anchor window
ids. The legacy anchor keys stay in the map, overlaid with the same tier, so an SPA build that still
reads them agrees. `SFListMenu` already resolves the six records through `GoFeatureAccess`. The
schema_forge PR is merged in the same release as this one; the backend change is backward
compatible, so deploying it first is safe.

- An inactive GO Access row is ignored (the legacy grant decides again).
- Fiscal Monitor: its anchor is the real SII Monitor window, so its row is not overlaid on the
  window-keyed surfaces (it would change SII Monitor's tier); it governs the `fiscal-monitor`
  feature access, which the Fiscal Monitor page guard now reads.

## Deferred (listed in the PR "Design decision" section)

- **tax-report** keeps today's behaviour (open to every authenticated role through the permissive
  fallback in `NeoAccessHelper#hasAccessToConstituentWindows`). The criterion "no permissive
  fallback" is **not met for tax-report**: the issue says it needs a functional decision on its grant
  source, and the human guidance says to keep the behaviour and mark it TODO. A follow-up issue
  (decide the grant source, declare it as a feature, close the fallback) has to be opened and linked
  from the PR, because ETP-4802 will be closed; its key then replaces `TODO(ETP-4802)` in
  `GoFeature`.
- Retiring the legacy fallback and the classic proxy/anchor grants after one release.
- Identifier uniqueness validation in sf-validate-pipeline: the DB unique index covers role +
  feature; `GoFeature.ids()` is the single identifier list, checked against the list reference by a
  test.
