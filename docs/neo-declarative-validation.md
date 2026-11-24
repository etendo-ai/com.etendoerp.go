# NEO declarative validation (ETP-4558)

NEO enforces per-field `validation` constraints on REST POST/PUT/PATCH and every batch create,
inside `NeoWritePreflight`, before any customization (`NeoHandler`) or persistence runs.

## Source of the constraints
`ETGO_SF_FIELD.MCP_CONFIG`, section `validation` (field level), registered in `McpConfigSections`:

```json
{ "validation": { "required": true, "minLength": 1, "maxLength": 60, "minimum": 0, "maximum": 100,
                  "format": "email", "enum": ["A","B"], "allowedSchemes": ["https"] } }
```
This is the object `schema_forge_core` already publishes per field in the contract; the push step
may copy it verbatim into `MCP_CONFIG`. A malformed section is skipped and logged (never half-applied);
an unknown `format` is not enforced.

## Semantics
- `required`: demanded on POST only; PATCH/PUT are partial. An explicit null/blank is refused.
- Other constraints apply only to a sent, non-null value. `0` and `false` are values.
- Field found under its API key or DAL property name. Fields not included, read-only or with a
  configured default are not constrained.
- Formats: `email`, `uuid`, `date` (yyyy-MM-dd), `uri`/`url`.
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
