# NEO declarative validation (ETP-4558)

NEO enforces per-field `validation` constraints on REST POST/PUT/PATCH and every batch create,
inside `NeoWritePreflight`, before any customization (`NeoHandler`) or persistence runs.

## Source of the constraints
`ETGO_SF_FIELD.MCP_CONFIG`, section `validation` (field level), registered in `McpConfigSections`.
schema_forge's `push-to-neo` writes it (`upsertField`, `cli/src/neo-writer.js`), merged with any other
section; a field with no constraints clears it:

```json
{ "validation": { "required": true, "minLength": 1, "maxLength": 60, "minimum": 0, "maximum": 100,
                  "format": "email", "enum": ["A","B"], "allowedSchemes": ["https"] } }
```
This is the object `schema_forge_core` publishes per field in the contract. Keys NEO does not know
(a newer contract) are ignored and logged once; they never make the field's other `MCP_CONFIG`
sections unusable. A wrongly typed known key makes the section unusable: it is skipped and logged
(never half-applied). An unknown `format` is not enforced (logged once). The rules are read in admin
mode, since `ETGO_SF_FIELD` is system-level metadata.

## Semantics
- `required`: demanded on POST only; PATCH/PUT are partial. An explicit null/blank is refused.
- Other constraints apply only to a sent, non-null value. `0` and `false` are values.
- Field found under its API key or DAL property name. Fields not included, read-only or with a
  configured default are not constrained.
- Formats: `email`, `uuid`, `date` (yyyy-MM-dd), `uri`/`url`.
- Length counts UTF-16 code units, as the contract documents. A numeric `enum` compares numerically.
- Where it runs: `NeoWritePreflight`, after the update target guard and before the reference
  checks, so REST POST/PUT/PATCH and every batch create share it. `BatchService` supports only
  creates (`POST`, create semantics) and deletes, so there is no batch update. A batch is atomic:
  one invalid record fails the batch (`failedAt`) and nothing is persisted.
- Import: NEO has no generic row-import endpoint (the bank-statement import is a handler action
  with its own file parsing), so there is nothing to route here.
- Unknown / non-exposed fields and request size keep their existing handling
  (`NeoFieldFilter`, `NeoServlet` max request size); not changed here.

## Error contract
HTTP 400:
```json
{"error":{"code":"VALIDATION_FAILED","message":"Validation failed","status":400,
  "fields":{"etgoEmail":[{"code":"INVALID_FORMAT","format":"email"}]}}}
```
Codes: `REQUIRED`, `INVALID_TYPE`, `TOO_SHORT`, `TOO_LONG`, `BELOW_MINIMUM`, `ABOVE_MAXIMUM`,
`INVALID_FORMAT`, `INVALID_ENUM`, `DISALLOWED_SCHEME`. Values are never echoed or logged.
