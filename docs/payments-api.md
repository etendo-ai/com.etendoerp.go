# Payments API — `/sws/go/payments/v1` (ETP-6322)

The versioned API through which the ERP that issues Etendo's own invoices learns about every
Stripe charge, issues the legal invoice, mails it to the customer and reports back.

- **One payment per paid Stripe invoice with an amount above zero**: a new purchase, a renewal or
  the proration of a plan change. A free first month or a full discount is no payment.
- Recorded by the payment webhook (`POST /sws/go/checkout/webhook`) **in the same transaction**
  that applies the `invoice.paid` event. If the row cannot be written, the webhook fails and
  Stripe delivers the event again: a payment is never acknowledged without being kept. Only
  charges made after this was deployed are recorded; there is no backfill.
- **Out of scope:** refunds and disputes (corrective invoices).

Code: `com.etendoerp.go.payment.invoicing` — `StripeInvoicePayment` (reads the event),
`PaymentMethodReader` (asks Stripe for the card), `PaymentLedger` (rules), `PaymentLedgerStore`
(SQL), `PaymentStatus` (states), `PaymentsApiServlet` (wire). Keys: `com.etendoerp.go.common.ApiKeyRing`,
shared with the signup API. Tables: `ETGO_PAYMENT`, `ETGO_PAYMENT_EVENT`.

## Decisions (2026-11-17)

- **Plan prices are without VAT, and Stripe Tax charges it** (2026-11-24). The checkout session
  sends `automatic_tax[enabled]=true`, `billing_address_collection=required`,
  `tax_id_collection[enabled]=true` and `tax_id_collection[required]=if_supported`: the buyer
  types a full billing address and, in every country where Stripe collects one (Spain among
  them), a name and tax id, and Stripe adds the VAT that address calls for (21 % in Spain, a
  reverse charge for an EU business with a VAT number, none outside the EU). A subscription keeps
  it for its renewals. So `amountPaid` includes the VAT: the base of the invoice is `taxBase`,
  the tax `taxAmount`, and `taxes` says which tax and why.
- **Stripe Tax must be configured before the code that asks for it is deployed**: Stripe Tax
  active with Spain as head office, `exclusive` as the default tax behavior and a default tax code
  (`txcd_10103001`, SaaS for business use), and a standard VAT registration in Spain. A price with
  `tax_behavior: unspecified` takes the default; a product with its own tax code uses that one
  instead, so check it is the SaaS one. Without Stripe Tax, or with no tax behavior at all, the
  session is refused and nobody can buy; without the registration Stripe charges 0 % and says
  nothing. Test and live are configured separately: nothing is copied between them.
- **Billing email = the account's email**, else the address Stripe billed.
- **The ERP decides** whether an invoice may be simplified (no customer data, up to 400 € VAT
  included for Spanish customers) or must be complete. Fiscal data are **not** required at
  purchase and there is no waiting state: every payment is `pending_invoice` at once, with
  whatever fiscal data exist.
- **The fiscal snapshot** (`fiscalData`) is one per payment, so a renewal carries the data of its
  own date. It is taken when the payment is recorded, **refreshed on every read while the payment
  has no invoice number**, and **frozen by the `PATCH` that reports the invoice number**: from then
  on it is the snapshot that invoice was issued with.
- **The snapshot is the checkout's data, and only those** (2026-11-24). The checkout is what
  Stripe computed the VAT from, the only source with an address, and it exists from the first
  webhook, before the environment does. The signup's company data are optional and often left
  for later, so they never complete or replace it. The tax id is required at the checkout wherever
  Stripe can collect one: the buyer is a company or a self-employed person, both with one (a
  person types their own name as the company and their DNI/NIE as "ES NIF"). Where Stripe
  collects none (Argentina, the United States…) the invoice goes to the cardholder's name with no
  tax id. Stripe checks a Spanish tax id's shape only, so the snapshot checks its check digit too
  (`SpanishTaxIdValidator`, `es_vat` without its `ES` prefix) and leaves out one that fails. The
  tenant's company is used only for a payment whose checkout told nothing (recorded before
  2026-11-24).
- **Stripe's default tax id type in Spain is `es_vat`**, which needs the `ES` prefix; a bare CIF
  is refused until the buyer picks "España (ES NIF)" (`es_cif`). Stripe offers no way to change
  the default.

## Authentication: API keys

Every call carries `X-Api-Key: <key>`, server to server only (no CORS). Keys are configured as
`etgo.payments.api.keys` (system property) or `ETGO_PAYMENTS_API_KEYS` (environment):

```
ETGO_PAYMENTS_API_KEYS=erp:<sha256 hex>
```

Same format, hashing and rotation as the signup API (`signup-api.md`), but **its own ring**: a
signup key does not open this API. The key's name is recorded on every event the caller reports.
No key configured: every call answers `401 INVALID_API_KEY`.

## Endpoints

| Call | What |
|---|---|
| `GET /payments?status=&limit=&after=` | A page of payments, oldest first. `status`: `pending_invoice`, `invoiced`, `mail_sent`, `completed` or `error` (omitted: all). `limit`: 1–200, default 50. `after`: the `nextCursor` of the previous page. |
| `GET /payments/{id}` | One payment, with its history in `events`. |
| `PATCH /payments/{id}` | `{"invoiceNumber":"F2026-0001"}`. The first number moves the payment to `invoiced` and freezes its fiscal snapshot. The same number again is a no-op (200); another number is `409 INVOICE_NUMBER_CONFLICT`. |
| `POST /payments/{id}/events` | `{"event":"invoiced|mail_sent|completed|error","step":"…","reason":"…"}`. `step` and `reason` are required for `error`. |

The ERP's loop: list `pending_invoice` (and `error`, to retry), issue the invoice, `PATCH` its
number, mail it, `POST mail_sent`, then `POST completed`.

### List answer

```json
{ "data": [ { …payment… } ], "hasMore": true, "nextCursor": "<payment id>" }
```

The cursor is keyset (created, id): a payment that changes state while the ERP pages through
`pending_invoice` does not shift the pages.

### A payment

```json
{
  "id": "…", "status": "pending_invoice", "paymentType": "renewal",
  "billingReason": "subscription_cycle", "accountId": "…", "billingEmail": "owner@example.com",
  "environmentClientId": "…", "currency": "EUR",
  "amountPaid": "54.45", "subtotal": "45.00", "taxBase": "45.00", "taxAmount": "9.45",
  "totalAmount": "54.45",
  "taxes": [ { "taxableAmount": "45.00", "amount": "9.45", "rate": "21.00",
               "reason": "standard_rated" } ],
  "paidAt": "2026-11-17T10:00:00Z", "periodStart": "…", "periodEnd": "…",
  "lines": [ { "description": "1 × Pro", "quantity": 1, "amount": "45.00",
               "priceId": "price_…", "periodStart": "…", "periodEnd": "…" } ],
  "fiscalData": { "name": "…", "legalName": "…", "taxIdType": "es_cif", "taxId": "B12345678",
                  "address1": "…", "address2": null, "postalCode": "…", "city": "…",
                  "region": "…", "countryCode": "ES", "country": "Spain" },
  "fiscalDataAt": "…",
  "paymentMethod": { "type": "card", "brand": "visa", "last4": "4242", "wallet": "apple_pay",
                     "receiptUrl": "https://pay.stripe.com/…", "chargeId": "ch_…" },
  "invoiceNumber": null, "invoicedAt": null,
  "stripe": { "invoiceId": "in_…", "invoiceNumber": "…", "subscriptionId": "sub_…",
              "customerId": "cus_…",
              "billingDetails": { "name": "…", "taxIdType": "es_cif", "taxId": "B12345678",
                                  "address1": "…", "address2": null, "postalCode": "…",
                                  "city": "…", "region": "…", "countryCode": "ES" } },
  "created": "…", "updated": "…"
}
```

- Amounts are decimal strings with the currency's decimals, never floats.
- `paymentType`: `new` (`subscription_create`), `renewal` (`subscription_cycle`), `plan_change`
  (`subscription_update`) or `other`.
- `amountPaid` is what was collected, VAT included. `taxBase` is the base of the invoice: Stripe's
  total excluding tax, **after discounts** (`subtotal` is before them, so with a promotion code it
  overstates the base). Both `taxBase` and `taxes` are `null`/empty for payments recorded before
  2026-11-24, and `taxes` is empty when Stripe computed no tax.
- `taxes[].rate` is the percentage, worked out from `amount` and `taxableAmount` (the event names
  the tax rate only by id). `taxes[].reason` is Stripe's `taxability_reason`: `standard_rated`,
  `reverse_charge` (an EU business with a VAT number: no VAT, the invoice carries the reverse
  charge mention), `not_collecting` (outside the EU, or a country without a registration),
  `not_supported`, and others. A 0 % tax can mean any of them: decide by `reason`, never by the
  amount alone.
- `fiscalData` is `null` only when neither the checkout nor the tenant tells anything; each field
  is `null` when blank. `name` and `legalName` are the same: the company name typed at the
  checkout, or the cardholder's name where no tax id was asked. `taxIdType` is Stripe's tax id
  type (`es_cif`, `es_vat`, `eu_vat`…); `taxId` and `taxIdType` are `null` when none was given or
  a Spanish one fails its check digit. `stripe.billingDetails` is the checkout's data exactly as
  Stripe copied them onto its invoice, the rejected tax id included. For payments recorded before
  2026-11-24, `fiscalData` is the tenant's company: its legal entity, else its first organization
  (the one with a tax id first), with no `taxIdType`.
- `paymentMethod` is asked to Stripe while recording: the invoice with its payment intent
  expanded (`payments.data.payment.payment_intent`), then the intent's latest charge by id.
  Stripe expands at most 4 levels and answers 400 to a deeper path, which until 2026-11-24 left
  every payment without its method. A failing or slow Stripe never fails the webhook: the payment
  is then recorded with `paymentMethod: null`.
- `GET /payments/{id}` adds `events`: `[{ "event", "step", "reason", "channel", "at" }]`, oldest
  first, starting with `recorded`.

## States

```
pending_invoice ─(number)─▶ invoiced ─▶ mail_sent ─▶ completed (final)
  any state but completed ─error─▶ error ─(the failed step, again)─▶ forward
```

| Refusal | When |
|---|---|
| `409 INVOICE_NUMBER_MISSING` | `invoiced`, `mail_sent` or `completed` before the `PATCH` with the number. |
| `409 INVALID_TRANSITION` | A step back (e.g. `invoiced` after `mail_sent`). |
| `409 PAYMENT_COMPLETED` | Anything after `completed`. |

Reporting the state the payment already has is accepted again (a retried call) and recorded.

## Errors

`{ "error": { "code", "message", "field" } }` — callers branch on `code`; `field` only when it
applies; `message` is English, for logs.

| Status | Code |
|---|---|
| 400 | `INVALID_REQUEST` (bad `status`, `limit`, body, `invoiceNumber`, `event`, `step`, `reason`) |
| 401 | `INVALID_API_KEY` |
| 404 | `NOT_FOUND` (path), `PAYMENT_NOT_FOUND` |
| 405 | `METHOD_NOT_ALLOWED` |
| 409 | `INVOICE_NUMBER_CONFLICT`, `INVOICE_NUMBER_MISSING`, `INVALID_TRANSITION`, `PAYMENT_COMPLETED` |
| 500 | `PAYMENTS_SERVER_ERROR` (details only in the server log, never fiscal data) |

## Local testing

With `stripe listen --forward-to localhost:<port>/etendo/sws/go/checkout/webhook` and the
listener's secret in `etendo.go.checkout.webhook.secret`, a test-mode purchase or
`stripe trigger invoice.paid` records a row. Then:

```bash
curl -H "X-Api-Key: <key>" "localhost:<port>/etendo/sws/go/payments/v1/payments?status=pending_invoice"
```
