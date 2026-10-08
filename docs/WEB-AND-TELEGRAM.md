# Website and Telegram bot

Both are part of the backend (`server/`): one process, one database, same accounts.

| Where they buy | What happens after payment |
| --- | --- |
| **JagaNet app** (App Store / Google Play) | Pro turns on immediately. The app makes its own key on the phone. |
| **Website** | Pro turns on, a **VPN key** is made, and the account page shows it with a QR code, `.conf` download and a link, plus app download links. |
| **Telegram bot** | Same as the website; the bot sends the QR code, the `JagaNet.conf` file and the key link to the chat. |

A **VPN key** is a config made on the server (`awg-quick` format) that AmneziaVPN, AmneziaWG
or WireGuard import. Each key is a device of the account, so plan limits apply. The private
key is stored encrypted (AES-GCM, key derived from `AUTH_SECRET`). The link
`https://<site>/k/<token>` is the secret; deleting the key kills the link.

## Website

Static files in `server/src/main/resources/web/` (plain HTML/CSS/JS, no build step), served at `/`.
Server-rendered pages: `/k/<token>` (key page), `/pay/test/<order>` (test checkout).

API (all under `/v1`, bearer token from the normal email sign-in):

- `GET /site` – app links, Telegram bot link, whether payments are in test mode
- `GET /keys`, `POST /keys`, `DELETE /keys/{id}`
- `POST /orders {productId}` → `{id, checkoutUrl, …}`, `GET /orders/{id}`

## Telegram bot

Turn it on: create a bot with [@BotFather](https://t.me/BotFather), then on the server

```
curl -fsSL https://raw.githubusercontent.com/ksenxxoen/JagaNet/claude/adoring-brown-s8fqex/scripts/server/install.sh | TELEGRAM_BOT_TOKEN=123456:ABC… bash
```

(or put `TELEGRAM_BOT_TOKEN=…` in `/etc/jaganet/env` and `systemctl restart jaganet`).
It uses long polling, so no webhook or extra port is needed.

Commands: `/start` (plans), `/key`, `/app` (download links + a device code that signs the
JagaNet app into the same account), `/status`. `/start <invite code>` applies a referral.
Telegram users get an account with the placeholder email `tg<id>@telegram.invalid`.

## Payments

`PAYMENT_PROVIDER=test` (the installer's default) is a built-in checkout page with a
"Pay (test)" button: **no money moves, and anyone can mark an order paid.** Switch it off
before real customers arrive.

Adding the real service (`server/.../services/Payments.kt`):

1. Implement `PaymentProvider` (`checkoutUrl(order)` → the provider's payment page).
2. Add it to `Payments.providers` and set `PAYMENT_PROVIDER=<its id>`.
3. Add a webhook route that verifies the provider's signature and calls
   `payments.markPaid(orderId, providerPaymentId)`. That call is idempotent and does the rest:
   extends Pro, makes the VPN key, notifies the Telegram bot.

## Android download

The site offers the APK if `/opt/jaganet/downloads/jaganet.apk` exists
(or set `ANDROID_APP_URL` / `IOS_APP_URL` in `/etc/jaganet/env`).

## Languages

Russian (default), German and English everywhere: apps, website, bot, server pages and API errors.
All texts live in `shared/src/commonMain/kotlin/dev/jaganet/api/i18n/strings/` as
`S("English", "Русский", "Deutsch")`; the English text is the key (`t("…")` in code).

- App: Settings → Language. Sends `Accept-Language`, so server errors come translated.
- Website: RU / DE / EN in the header (remembered; `?lang=de` also works). Tables come from `/v1/i18n/<lang>`.
- Bot: Russian until the user picks another language with /language (stored per user).
- Key and checkout pages: `?lang=`, then the website's choice, else Russian.

House style, checked by `I18nTest`: no long dashes, no "·" separators, at most one colon per text,
and every text has Russian and German.

## Referral program

Every user has a main link and can add up to 50 more, one per campaign ("Instagram", "YouTube"),
optionally with their own code. Links: `https://<site>/r/<CODE>` and, with the bot on,
`https://t.me/<bot>?start=<CODE>`. Adding `?utm_source=name` to a web link tags its clicks.

What is counted (`server/.../services/Referrals.kt`, tables `referral_links`, `referral_clicks`):

| Step | How |
| --- | --- |
| Click | each visit of `/r/<code>` or Telegram `/start <code>` |
| Unique visitor | HMAC of IP + browser (or Telegram id). No IP addresses are stored |
| Sign-up | first touch: the website keeps the code until sign-up; the app takes it in the invite field |
| Paid | referred people whose first paid purchase falls in the period |
| Purchases | all paid purchases of referred people, renewals included |
| Revenue | website and Telegram orders, per currency (store purchases have no amount here) |

Per link and in total, for 7, 30 or 90 days or all time, with a daily series, click sources
(utm_source or referring site), sign-up channels (website, app, Telegram) and the list of invited
people (emails masked). Archived links stop counting clicks; their history stays in the totals.
The owner sees the whole program and the top partners (`/v1/admin/referrals`).

Where: website `#/referrals`, the app's Invite friends screen and owner dashboard, the bot's /invite.
