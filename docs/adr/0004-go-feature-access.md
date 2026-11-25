# ADR-0004: GO feature access (GO Access subtab)

Status: proposed (ETP-4802) — for the reviewer to validate. Copy of the PR's "Design decision" section.

## Design decision

The ticket left the mechanism undesigned. This change takes the smallest option that does not change
who has access to what today.

**Chosen: a "GO Access" subtab in the Role window.** New AD table `ETGO_Go_Access` (role, feature,
`IsReadWrite`; unique per role+feature) with its tab under Role (window 111). A feature is declared
in `GoFeature` (identifier, the classic record it was proxied through, its support tables); the
`Feature` list reference `ETGO_GoFeature` carries the same identifiers.

**One shared check:** `GoFeatureAccess` ("may this role use feature X with this method"):
1. admin / client-admin roles pass (same as `NeoAccessHelper`);
2. an active GO Access row of the role decides (read: any row; write: `IsReadWrite = Y`);
3. with no row, the legacy classic grant (proxy window / OBUIAPP process) decides;
4. nothing else: a feature with neither is denied (no permissive fallback).

Because no GO Access rows exist after deploy, step 3 keeps every role's current access. Rows are
created later (by hand, or by migrating the role templates) and from then on replace the proxy.

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
  the declaration also names Java-side support tables. Follow-up if features must be added without a
  release.

**Seeding and migration (this PR)**
- `SeedGoAccessScript` (module script, compiled class committed) inserts one GO Access row per role
  that holds an active classic grant on a feature's proxy/anchor (`AD_Window_Access` or
  `OBUIAPP_Process_Access`), at the same `IsReadWrite`, skipping inactive grants. Idempotent:
  `INSERT ... WHERE NOT EXISTS` on role + feature, backed by the unique index; it never touches an
  existing row. System templates are roles too, so their grants are seeded by the same script.
- The legacy fallback stays for one release as a safety net (roles created from a template after
  the seed hold only the classic grant and are still decided by it). Dropping it, the classic
  proxy grants in `TemplateRoleWindowAccess` / `EnsureSystemRoleTemplatesScript` and propagating GO
  Access rows from the templates is the follow-up.
- SPA: all SPA access goes through `SFWindowAccessMap` (page guards, `menu.json` anchors) and
  `SFListMenu` (`allowedIds`); both apply the GO Access tier, so the SPA answers like the backend.
- An inactive GO Access row is ignored (the legacy grant decides again): deactivating a row does
  not revoke while the classic grant exists; revoke by deleting the row and the classic grant.
- Fiscal Monitor limitation: its anchor is the real SII Monitor window, so its row is not overlaid
  on the SPA surfaces (it would change SII Monitor's tier while the real window's check stays
  classic). Its row only governs `GoFeatureAccess` gates until the SPA addresses the feature by id.

**Not done here (follow-ups)**
- tax-report keeps today's behaviour (open to every authenticated role through the permissive
  fallback in `NeoAccessHelper#hasAccessToConstituentWindows`). TODO(ETP-4802): functional decision
  on its grant source, then declare it as a feature and close the fallback. This is an explicit
  exception to the ticket's "no permissive fallback" criterion, taken from the human guidance.
- Retiring the classic proxies/anchors and the SPA ids; propagating GO Access rows from templates.
- Identifier uniqueness validation in sf-validate-pipeline: the DB unique index covers role +
  feature; `GoFeature.ids()` is the single identifier list.
- The AD dictionary records were written by hand: `update.database` hung against the shared local
  Tomcat database in this environment, so they were not exported. Run `update.database` and
  `export.database` before merge and commit the exported sourcedata.
