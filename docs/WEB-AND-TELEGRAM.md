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

A user can do everything in the bot without the website (issue #38): sign up, buy and renew,
get the subscription link and QR code, manage devices, get reminders, talk to support, and sign
in on the website. Code: `server/.../telegram/` (bot, accounts, transport), `http/TelegramWeb.kt`
(website endpoints), `web/telegram.js` (Login Widget, linking card).

Turn it on: create a bot with [@BotFather](https://t.me/BotFather), then on the server

```
curl -fsSL https://raw.githubusercontent.com/ksenxxoen/JagaNet/claude/adoring-brown-s8fqex/scripts/server/install.sh | TELEGRAM_BOT_TOKEN=123456:ABC… bash
```

(or put `TELEGRAM_BOT_TOKEN=…` in `/etc/jaganet/env` and `systemctl restart jaganet`).

| Variable | Default | What it does |
| --- | --- | --- |
| `TELEGRAM_BOT_TOKEN` | unset = no bot | Token from @BotFather. Never logged. |
| `TELEGRAM_MODE` | `polling` | `polling` (no open port needed, works when only outbound HTTPS reaches Telegram) or `webhook`. |
| `TELEGRAM_WEBHOOK_URL` | `$PUBLIC_URL/telegram/webhook` | Webhook mode: the HTTPS URL Telegram posts to. |
| `TELEGRAM_WEBHOOK_SECRET` | derived from `AUTH_SECRET` | Webhook mode: checked in `X-Telegram-Bot-Api-Secret-Token`. |
| `TELEGRAM_SUPPORT_CHAT_ID` | first of `ALERT_TELEGRAM_CHAT_IDS` | Chat (a person or a group) that gets support messages. The bot's `/myid` shows a chat's id. |
| `TELEGRAM_LOGIN_WIDGET` | off | `1` shows the Telegram Login Widget on the sign-in page (after `/setdomain`, below). |
| `TELEGRAM_API_URL` | `https://api.telegram.org` | Another Bot API server (a local `telegram-bot-api`, or a reverse proxy abroad). |
| `TELEGRAM_PROXY` | none | HTTP proxy for the Bot API, `host:port`. |

If Telegram can't be reached the bot retries with growing pauses (5 s up to 2 min); every call has
a short timeout, payments and the website never wait for Telegram, and the status page shows
"The bot can't reach Telegram".

Commands: `/start` (menu), `/keys`, `/pay`, `/devices`, `/support`, `/help`, `/login`, plus the older
`/key`, `/app`, `/status`, `/language`, `/invite`, `/myid`. Everything is also on inline buttons.

- **Accounts.** The first message makes an account bound to the Telegram id (placeholder e-mail
  `tg<id>@telegram.invalid`). `users.telegram_id` is unique, so there is one account per Telegram.
- **Linking an e-mail account.** Account page on the website, "Link Telegram": a one-time link
  `t.me/<bot>?start=link_<code>` (15 minutes). If the person had an empty bot account it is
  removed; one with purchases is left alone and the bot asks them to write to support.
- **Website sign-in.** "Sign in with Telegram" opens `t.me/<bot>?start=login`; the bot sends a
  one-time link valid 10 minutes. With `TELEGRAM_LOGIN_WIDGET=1` the Login Widget is shown too; its
  data is checked with HMAC-SHA256 against the bot token and `auth_date` must be under 1 hour old.
- **Buying.** Tariffs and the same checkout as the website (`Payments`, channel `telegram`). After
  payment the bot sends the date and the keys.
- **Keys.** The subscription link `/k/<token>/vless` with its QR code, the key page `/k/<token>`,
  and short steps (Happ, v2RayTun, then Hiddify). No server or protocol names. Without VLESS nodes
  the AmneziaWG key is sent as before (QR code and `BoopVPN.conf`).
- **Devices.** Devices with a tunnel, "N of limit", delete with confirmation. The subscription key
  (issue #33) is outside the limit and can't be deleted.
- **Reminders.** 3 days and 1 day before the end, at the end (each once per end date), and on
  payment. "Notifications" turns them off; users who blocked the bot are skipped.
- **Support.** "Support", then the next messages (30 minutes) are forwarded to the support chat
  with who wrote. The admin *replies* to the forwarded message and the answer goes to the user.
  In a group, the bot sees replies to its own messages even with privacy mode on.
- **Referrals.** `t.me/<bot>?start=ref_<code>` (older links without `ref_` work too) counts a click
  and a new account remembers the inviter (`referred_by`, `referral_link_id`) and the raw code
  (`users.tg_start_ref`).

### Login Widget domain (@BotFather)

1. In @BotFather: `/setdomain`, choose the bot, send the site's domain (as in `PUBLIC_URL`, without
   `https://`, e.g. `vpn.example.com`).
2. Put `TELEGRAM_LOGIN_WIDGET=1` in `/etc/jaganet/env`, `systemctl restart jaganet`.

Without step 1 the widget says "Bot domain invalid", so it stays off until both are done. The
"Sign in with Telegram" button (through the bot) needs no domain setup.

### Webhook instead of polling

Polling is the default and needs nothing else. For a webhook the site must be reachable by
Telegram over HTTPS on 443, 80, 88 or 8443: set `TELEGRAM_MODE=webhook` (and
`TELEGRAM_WEBHOOK_URL` if the public URL differs), restart. The bot registers the webhook itself
and checks it every minute. Back to polling: remove `TELEGRAM_MODE`, restart (the webhook is
deleted on start).

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

### Invite a friend, get +14 days (issue #40)

Every user (not only admins) gets "Invite a friend" while rewards are on: website `#/invite`
(menu «Пригласить друга»), the bot's /invite and «🎁 Пригласить друзей» button, the apps through
`GET /v1/referrals` (`rewardDays`, `daysEarned`, `shareUrl`, `telegramUrl`) and
`GET /v1/referrals/invite` (links, rules, invited / paid / days received, the list of rewards).
The detailed analytics with campaign links (`#/referrals`) stay admin-only unless `REFERRALS_PUBLIC=1`.

- Links: `https://<site>/r/<CODE>` (counts the visit, then opens `https://<site>/?ref=<CODE>`; a
  plain `?ref=<CODE>` works too) and `https://t.me/<bot>?start=ref_<CODE>`. The website keeps the code
  in the browser until sign-up (e-mail or Telegram Login Widget); the bot uses the `/start` payload.
- The inviter (`users.referred_by`) is set once, when the account is made: an existing account
  never gets or changes one (a database trigger refuses changes), nobody is their own inviter
  (check `users_not_self_referred`).
- Reward: when the invited person pays for the first time, the inviter gets `REFERRAL_REWARD_DAYS`
  (14) of Pro, added after the end of their current subscription, or from now if it has ended.
  Stored as a `subscriptions` row with source `referral` (`external_id` `reward:<id>`) and a row in
  `referral_rewards`, unique per invited person: a repeated webhook or a second purchase gives
  nothing. `REFERRAL_REWARD_CONDITION=signup` rewards at sign-up instead (easier to abuse).
  `REFERRAL_INVITEE_DAYS` > 0 also gives the invited person a bonus (off by default).
- Anti-fraud: at most `REFERRAL_MONTHLY_CAP` (10) rewards per inviter per calendar month (UTC), 0 = no
  limit; over it the reward is recorded as "capped" without days. A reward is flagged "same network"
  / "same browser" when the invited person signed in (or opened the site signed in) from the
  inviter's IP / IP + browser in the last 90 days. Only HMACs are kept (`user_fingerprints`), no IP
  addresses. Flagged rewards are still given; the owner decides.
- The recipient is told in the bot (Telegram linked, notifications on), else by e-mail (if set up).
- The owner: Referral program → «Награды за приглашения» (`#/invite?view=admin`): the rules (saved in
  the settings table, they win over the variables), totals, top inviters, every reward with its
  flag and how the person was told, a "suspicious only" filter and «Отменить начисление» (the days
  are taken back and later subscription time moves back by what was left of the reward).
- `REFERRAL_REWARD_DAYS=0` turns the rewards off and hides "Invite a friend" from users again.
