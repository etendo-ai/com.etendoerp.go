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

**Not done here (follow-ups)**
- tax-report keeps today's behaviour (open to every authenticated role through the permissive
  fallback in `NeoAccessHelper#hasAccessToConstituentWindows`). TODO(ETP-4802): functional decision
  on its grant source, then declare it as a feature and close the fallback.
- Role-template migration (`TemplateRoleWindowAccess`, `EnsureSystemRoleTemplatesScript`) to GO
  Access rows, and the SPA copies (schema_forge `menu.json`, route guards): the legacy grant still
  feeds them, so they keep working; removing the proxies needs both.
- Identifier uniqueness validation in sf-validate-pipeline: the DB unique index covers role+feature;
  `GoFeature.ids()` is the single identifier list.
