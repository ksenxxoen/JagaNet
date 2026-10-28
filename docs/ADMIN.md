# Admin panel, real mode and monitoring

## Real mode (default)

The installer turns test modes off: sign-in codes are never shown on screen and there is no
test checkout. Until something is set up, the products say so instead of pretending:

| Missing | What people see |
| --- | --- |
| E-mail (SMTP) | "Sign-in by e-mail is temporarily unavailable"; the website offers sign-in through the Telegram bot (`/login`) |
| Payment service | "Payments are temporarily unavailable" on the website, in the bot |
| Store purchases (App Store, Google Play) | "In-app purchases are temporarily unavailable" in the apps |

The owner can turn test modes on deliberately in the admin panel (shown with a warning).

## Getting into the admin panel

On the server: `jaganet-admin` prints a one-time link (15 minutes) that signs the owner
(`OWNER_EMAIL`) in on the website. The installer prints one at the end. It works only on the
server itself (127.0.0.1, not through the web proxy).

Website `#/admin` (owners only):

- **Money**: revenue per currency, paid orders, new subscriptions, renewals, active subscribers,
  recurring revenue, unpaid checkouts, by channel and plan, daily charts, recent payments.
- **Plans**: the tariff builder. Any number of tariffs: name, length in days or months, price in
  rubles and euros, devices, data per month (empty = unlimited), badge, order, status. Changes
  apply to new purchases only; bought subscriptions keep their terms. Tariffs are archived, not
  deleted. Below: free plan limits and invite rewards.
- **Connection failures**: failed connections the Boop apps report (`handshake_failed`: the node
  never answered; `no_rx`: a working tunnel went quiet), the latest 200 with user, device, node,
  the node's address, the app's reason (`blocked`, `offline`, `no_handshake`, `no_rx`,
  `stale_handshake`), network kind and app version; filters by node and user (e-mail or id) and
  a count per node for the last 15 minutes. One report per device per minute is kept (the rest
  are dropped quietly), for 30 days. Table `connection_failures` (migration V14).
- **Nodes** (status and notes, issue #10): each node's edit form has **In the apps**: a status
  (`ok`; `degraded`: works with problems, the apps pick it last; `blocked`: blocked by some
  networks, "Fastest" skips it but a country picked by hand still connects; `maintenance`: the
  apps don't pick it) and a note for the node's users (up to 200 characters). Above the list,
  **Note for everyone**. The Boop app for Windows shows the note in a banner the person can close
  (the same text does not come back). The node itself keeps working and keeps its devices; the
  server's own pick (an app that asks for no node) puts `ok` first and `blocked` / `maintenance`
  last. API: `PUT /v1/admin/nodes/{id}/status` `{status, notice}`, `PUT /v1/admin/settings/notice`
  `{text}` (owner only); `GET /v1/servers` sends `status`, `notice` per node and `notice` for
  everyone, `GET`/`POST /v1/tunnel` the node's `notice`. All optional: older apps ignore them.
  Columns `servers.status`, `servers.notice` (migration V15); the note for everyone is in `settings`.
- **E-mail**: SMTP of any provider (Brevo, Mailgun, Postmark, Amazon SES, Gmail, Zoho…), test e-mail.
  The password is stored encrypted.
- **Alerts**: e-mail recipients and Telegram chat ids (the bot's `/myid` shows a chat's id).
- **Test modes**.

Settings saved here are stored in the database and win over `/etc/jaganet/env`.

## Monitoring (`server/.../monitor/`)

Every minute, kept 30 days, page `#/status`:

- **Internet channel**: download / upload speed of the internet interface, load against its capacity
  (`CHANNEL_MBPS`, else what the network card reports), interface errors and drops, ping and packet
  loss to 1.1.1.1 and 8.8.8.8, DNS, traffic this month against the hosting allowance (`HOST_TRAFFIC_LIMIT_GB`).
- **Services**: VPN interface (devices, online now), database, website over HTTPS, certificate, Telegram bot.
- **Resources**: CPU, memory, disk.

An alert opens when a problem lasts a few minutes (2 for the VPN and database, 3 for ping, 5 for
channel load…), is sent once by e-mail and Telegram, repeated every 6 hours while critical, and a
"Fixed" message follows when it is over.
