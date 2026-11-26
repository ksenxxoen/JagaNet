# Payments through Platega

Issue #154. Platega ([docs.platega.io](https://docs.platega.io/)) takes real payments for Boop VPN
in the Telegram bot, the mini app (`/tg/app`) and the account (`#/account`). It plugs into the
existing sales flow: orders, tariffs and access work the same way as with the `test` provider.
Every renewal is a new one-off payment. Platega's recurring subscriptions are not used (no
automatic charges), and payouts (Payout API) are out of scope.

**Nothing in this change is deployed.** Turning it on in production needs the owner's approval
(see "Switching it on").

## Flow

```
buyer ──► POST /v1/orders  (bot: payAction/buyAction; mini app: Pay.tsx; account: PlanCards)
            │  1. order row, status pending, terms and price frozen from the tariff (as before)
            │  2. PlategaProvider.start → POST {api}/transaction/process   (X-MerchantId, X-Secret)
            │     ← transactionId, redirect
            │  3. orders.external_id = transactionId, orders.checkout_url = redirect
            ▼
        OrderRes.checkoutUrl = redirect ──► the buyer pays on Platega's page
                                                │
     Platega ── POST PUBLIC_URL/pay/platega/callback ──► Payments.providerUpdate
                                                │          CONFIRMED + (amount = order, or amount − fee = order) → markPaid
                                                │          (the same idempotent call the test checkout uses:
                                                │           extends access on the frozen terms, makes the key,
                                                │           the bot sends the key / new date)
     buyer ◄── return / failedUrl = PUBLIC_URL/pay/return/{order}
                 web, app → /#/account?order={id} (the account keeps asking GET /v1/orders/{id})
                 telegram → https://t.me/<bot> (the bot sends the result)
```

Fallback when the callback did not arrive: `GET /v1/orders/{id}` (the account and the mini app
already poll it while an order is pending) asks Platega `GET {api}/transaction/{transactionId}`,
at most every 15 seconds per order, and applies the answer with the same amount rule as a callback.
`provider_status` is not a stop: an order still `pending` with `AMOUNT_MISMATCH` is checked again,
and a transaction that covers the order confirms it through `markPaid` (key and bot notification).

### What is sent when a payment is created

| Field | Value |
| --- | --- |
| `paymentMethod` | `PLATEGA_PAYMENT_METHOD` if set (`/transaction/process`); unset → `/v2/transaction/process`, the buyer picks one of the shop's enabled methods on Platega's page |
| `paymentDetails.amount`, `.currency` | the order's frozen price and currency (from the active tariff and the existing currency rule: rubles for Russian, euros for German and English) |
| `description` | `Boop VPN: <tariff name>` |
| `return`, `failedUrl` | `PUBLIC_URL/pay/return/<order id>` |
| `payload`, `orderId` | our order id |
| `metadata.user_email` | the account's e-mail; for a Telegram-only account (placeholder `tg…@telegram.invalid`) `<numeric Telegram id>@telegram.org`. Always the same value for the same buyer, never per order |
| `metadata.userId` | our user id (stable, for Platega's anti-fraud) |
| `id` | never sent: Platega makes it |

Platega's method codes (from its docs, availability depends on the shop): `2` SBP QR, `3` ERIP,
`11` bank cards, `12` international cards, `13` crypto, `14` SberPay. Which ones the shop has
is agreed with Platega; nothing in the code assumes a particular one.

## Statuses

`PENDING` never comes in a callback. Handling is idempotent by Platega's payment `id`.

| Platega status | Order | Access | Notes |
| --- | --- | --- | --- |
| `CONFIRMED`, currency matches and the amount equals the order | `paid` (also from `cancelled`, since delivery order is not guaranteed) | extended once, through `markPaid` | merchant pays the fee, or there is no fee. A repeated `CONFIRMED` changes nothing |
| `CONFIRMED`, currency matches and `paymentDetails.amount` minus `comission` equals the order (within 1 minor unit) | `paid` | extended once, through `markPaid` | buyer pays the fee. The callback has no fee, so this is read from `GET /transaction/{id}` (missing `comission` is 0; `comissionType` is only logged). The same rule is used when an order is polled. The fee is stored on the payment event as `feeMinor` |
| `CONFIRMED`, otherwise | stays `pending` | none | `provider_status = AMOUNT_MISMATCH`, logged as an error; the owner sorts it out. A later poll can still confirm it |
| `CANCELED` | `pending` → `cancelled`; a paid order stays paid | none | |
| `DISPUTED` | unchanged | unchanged | money is held by Platega until the check ends |
| `PARTIALLY_REFUNDED` | unchanged | unchanged | part of the money went back |
| `CHARGEBACKED` | unchanged | unchanged | all the money went back (terminal) |

**Policy for `DISPUTED`, `PARTIALLY_REFUNDED`, `CHARGEBACKED`:** access is not taken away
automatically. The status is stored on the order and shown in red in the admin panel's Payments tab
(and in the CSV, column `provider_status`), and a warning is logged. The owner decides per case
(for a chargeback, typically shorten or end the subscription from the user's card in the admin
panel). Automatic revocation can be added later once the owner settles the rules.

Creating the payment fails (Platega answers an error, times out, or sends no link): the buyer sees
"Payments are temporarily unavailable", the order is cancelled with `provider_status = CREATE_FAILED`,
and no access is given. The error is logged without the secret.

## Callback

- Address to enter in Platega's dashboard, **Settings → Callback URLs**:
  **`PUBLIC_URL/pay/platega/callback`**, e.g. `https://boop.example/pay/platega/callback`.
  It is set there, not per payment. It must be public HTTPS with a valid certificate (Caddy in
  front of the server already does this for `PUBLIC_URL`).
- Platega sends `POST` with JSON (`id`, `amount`, `currency`, `status`, `paymentMethod`, `payload`;
  no fee) and the headers `X-MerchantId`, `X-Secret`, `User-Agent: Platega Payment`.
  When that amount is not the order's, the server reads `GET /transaction/{id}` and uses
  `paymentDetails.amount` and `comission` from there.
- Both headers are compared with `PLATEGA_MERCHANT_ID` / `PLATEGA_SECRET` in constant time
  (`Crypto.safeEqual`). Missing or wrong → `401`, nothing is read or stored.
- Answers: `200 OK` when handled (also for repeats and for statuses that change nothing);
  `400` for a body that isn't a callback; `404` for a payment we don't know yet, so Platega
  delivers it again (it retries right away, then after 5, 10 and 15 minutes, with a 30 s timeout;
  after that it can be re-sent from the dashboard). When Platega is not configured the path is `404`.

## Data model (migration V30, additive)

- `orders.checkout_url`: Platega's payment link, returned as `checkoutUrl` while the order is pending.
- `orders.external_id`: Platega's transaction id (already existed); unique for `provider = 'platega'`.
- `orders.provider_status`, `orders.provider_status_at`: Platega's latest meaningful status. A late
  `CANCELED` does not overwrite the status of a paid order.
- `payment_events`: every verified callback and status-check result: transaction id, status, amount,
  currency, what we did (`paid`, `already_paid`, `cancelled`, `mismatch`, `recorded`, `unknown_order`)
  and the body (callbacks carry no secrets; those are in headers). Repeats of the same result are
  stored once.

This is V30. The previous migration is `V29__ip_limits.sql`, so Flyway sees no gap.

## API changes (backwards compatible)

- `POST /v1/orders`, `GET /v1/orders/{id}`: unchanged shape. `checkoutUrl` is Platega's link for
  Platega orders. `GET /v1/orders/{id}` may now also refresh a pending Platega order (see fallback).
- `GET /v1/admin/payments`: `CtlPayment.providerStatus` (optional) added; CSV gets a last column
  `provider_status`.
- New non-API routes: `POST /pay/platega/callback`, `GET /pay/return/{id}`.
- `PaymentProvider` got `start(order, buyer)` (create the payment at the provider) and
  `check(order, externalId)` (ask for its status), both with defaults; `checkoutUrl(order)` is now
  optional. `TestPaymentProvider` is unchanged in behaviour.
- Telegram bot: the main menu (text menu and the status card) starts with
  "💳 Buy a subscription" / "💳 Extend" (`pay`); it stays in Settings too, and `/pay` still works.

Apps, nodes and agents are not affected.

## Configuration (`/etc/jaganet/env`, never in the repository)

| Variable | Meaning |
| --- | --- |
| `PAYMENT_PROVIDER=platega` | sell through Platega |
| `PLATEGA_MERCHANT_ID` | the merchant id (UUID) from Platega's dashboard |
| `PLATEGA_SECRET` | the API key; only in env, never logged, never returned by the API or sent to clients |
| `PLATEGA_PAYMENT_METHOD` | optional, one method code (`2`, `11`, …); unset = the buyer chooses on Platega's page |
| `PLATEGA_API_URL` | optional, default `https://api.payplatform.cc` |

Without both `PLATEGA_MERCHANT_ID` and `PLATEGA_SECRET`, Platega is not enabled and
`PAYMENT_PROVIDER=platega` leaves selling off (never falls back to `test`). `PAYMENT_PROVIDER=test`
stays for development and CI.

`scripts/server/install.sh` writes `PAYMENT_PROVIDER` and the `PLATEGA_*` keys only when they
are absent, so re-running the installer keeps an existing Platega setup.

## Switching it on (after the owner's approval)

1. Ask Platega which methods the shop has; decide on `PLATEGA_PAYMENT_METHOD` (or leave it unset).
   Check with Platega that the shop accepts every order currency in use (orders in German and
   English are in euros).
2. Put the variables above into `/etc/jaganet/env` and restart the server.
3. In Platega's dashboard set the callback URL to `PUBLIC_URL/pay/platega/callback`.
4. Buy the cheapest tariff once and check: the bot / account shows the payment, the admin panel's
   Payments tab shows the order as paid with `CONFIRMED`.

Rollback: set `PAYMENT_PROVIDER=test` (or empty) and restart. Orders already paid stay paid; V30 only
adds columns and a table.
