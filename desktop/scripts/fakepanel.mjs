// A stand-in for the panel's /v1 API with made-up data, for scripts/e2e.mjs.
// The test steers it through the returned object: `down` drops connections, `status[path]` forces an
// HTTP status for a path (after /v1/), `calls` records what the client asked for, `expiresAt` is the
// plan's end that /v1/me sends (null: a plan without an end), `statuses` / `notices` (node id -> value)
// and `notice` are the owner's node statuses and notes that /v1/servers sends (issue #10); `diagnostics`
// holds the problem reports POST /v1/diagnostics received (issue #11). With `vless` on, an app that sends
// `supportedProtocols=…,vless` also gets two VLESS nodes (issue #29): Sweden, whose TCP ping goes to this
// panel's own port (it answers), and Poland, whose ping port is closed («недоступен»); `serversQuery`
// is the query of the last GET /v1/servers and `protocols` the protocol of each POST /v1/tunnel.
//
// Run on its own it serves until stopped, for trying the dev build by hand:
//
//   FAKE_EXPIRES_DAYS=2 node scripts/fakepanel.mjs      # prints the URL; plan ends in 2 days
//   FAKE_VLESS=1 node scripts/fakepanel.mjs              # with the two VLESS nodes
//   FAKE_NOTICE="Плановые работы" FAKE_STATUS=s-nl:maintenance,s-fi:degraded node scripts/fakepanel.mjs
//   FAKE_INVITE=1 node scripts/fakepanel.mjs             # «Пригласить друга» in settings (issue #40)
//   FAKE_BLOCKED=1 node scripts/fakepanel.mjs            # the owner blocked the account (issue #39)
// `blocked` answers every signed-in request 403 FORBIDDEN as the panel does; `invite` is GET
// /v1/referrals/invite (null: 404); a device code (auth/pair/redeem) signs in a Telegram-only account.
//   BOOP_API=<that URL> go run .                        # sign in with demo@example.com / 123456
import { createServer } from "node:http";
import { randomBytes } from "node:crypto";
import { fileURLToPath } from "node:url";

const DEFAULT_EXPIRES = "2027-03-15T00:00:00Z";

// GET /v1/referrals/invite as the panel sends it (InviteRes, issue #40), for `p.invite`.
export const SAMPLE_INVITE = {
  enabled: true, code: "BOOP42", webUrl: "https://boopvpn.app/r/BOOP42", telegramUrl: "https://t.me/boopvpn_bot?start=ref_BOOP42",
  rewardDays: 14, condition: "first_payment", inviteeDays: 7, invited: 3, paid: 1, daysEarned: 14,
  rewards: [{ at: "2026-10-20T10:00:00Z", who: "al***@gmail.com", days: 14, status: "granted", kind: "referrer" }],
};

const b64 = () => randomBytes(32).toString("base64");

export async function startPanel() {
  const serverPub = b64();
  const p = {
    down: false,
    status: {},
    calls: [],
    keys: [
      { id: "k1", name: "Demo phone", countryCode: "DE", lastSeenAt: null },
      { id: "k2", name: "Demo laptop", countryCode: null, lastSeenAt: null },
    ],
    pairCode: "654321",
    emailCode: "123456",
    webAllowed: false, // flip to true: the next poll of the website login succeeds
    expiresAt: DEFAULT_EXPIRES,
    statuses: {},
    notices: {},
    notice: null,
    diagnostics: [],
    vless: false,
    serversQuery: "",
    protocols: [],
    tunnels: {}, // session token -> { serverID, version }: each signed-in device has its own tunnel
    users: {}, // session token -> the account it belongs to (/me answers with it)
    blocked: false, // the owner blocked the account (issue #39): every signed-in request is 403 FORBIDDEN
    invite: null, // GET /v1/referrals/invite (issue #40); null: 404, the program is not open
    url: "",
    close: () => new Promise((r) => srv.close(r)),
    reset() {
      p.down = false; p.status = {}; p.calls = []; p.webAllowed = false; p.expiresAt = DEFAULT_EXPIRES;
      p.statuses = {}; p.notices = {}; p.notice = null; p.diagnostics = [];
      p.vless = false; p.serversQuery = ""; p.protocols = []; p.blocked = false; p.invite = null;
    },
  };
  const servers = [
    { id: "s-nl", name: "NL 1", countryCode: "NL", countryName: "Нидерланды", load: 0.1, protocols: ["amneziawg"], host: "203.0.113.5" },
    { id: "s-de", name: "DE 1", countryCode: "DE", countryName: "Германия", load: 0.1, protocols: ["amneziawg"], host: "203.0.113.6" },
    { id: "s-fi", name: "FI 1", countryCode: "FI", countryName: "Финляндия", load: 0.2, protocols: ["amneziawg"], host: "203.0.113.7" },
  ];
  // VLESS nodes (issue #28's shape), listed only with p.vless and only to an app that names vless.
  const vlessServers = () => [
    { id: "v-se", name: "SE 1", countryCode: "SE", countryName: "Швеция", load: 0.1, protocols: ["vless"], host: "198.51.100.30",
      ping: { host: "127.0.0.1", port: Number(new URL(p.url).port), method: "tcp" } },
    { id: "v-pl", name: "PL 1", countryCode: "PL", countryName: "Польша", load: 0.1, protocols: ["vless"], host: "198.51.100.31",
      ping: { host: "127.0.0.1", port: 1, method: "tcp" } },
  ];
  const pbk = randomBytes(32).toString("base64url");
  const vlessConfig = (s) => ({
    protocol: "vless", serverId: s.id, address: "10.9.0.2/32", dns: [], mtu: 1280,
    params: {
      uuid: "6286d380-3d9c-4118-b62e-8b761a279e20",
      variants: [
        { transport: "tcp", host: s.host, port: 443, flow: "xtls-rprx-vision", security: "reality", sni: "www.example.com", pbk, sid: "a1b2c3d4", fp: "firefox" },
        { transport: "xhttp", host: s.host, port: 8443, flow: "", security: "reality", sni: "www.example.com", pbk, sid: "a1b2c3d4", fp: "firefox", path: "/x", mode: "auto" },
      ],
    },
  });
  let sessions = 0;
  const DEMO = { id: "u1", email: "demo@example.com" };
  // A device code from the Telegram bot (/app) signs in an account made in Telegram: no e-mail, only
  // the panel's placeholder address (TelegramAccounts.placeholderEmail).
  const TG = { id: "u2", email: "tg42@telegram.invalid" };
  const session = (device, user = DEMO) => {
    const s = { token: `tok-${device}-${++sessions}`, user, deviceId: device };
    p.users["Bearer " + s.token] = user;
    return s;
  };
  const config = (id) => {
    const v = vlessServers().find((x) => x.id === id);
    if (v) return vlessConfig(v);
    const s = servers.find((x) => x.id === id);
    if (!s) return null;
    return {
      protocol: "amneziawg", serverId: s.id, address: "10.8.0.2/32", dns: ["10.8.0.1"], mtu: 1280,
      params: { serverPublicKey: serverPub, endpoint: s.host + ":51820", allowedIps: ["0.0.0.0/0"], persistentKeepalive: 25, obfuscation: { Jc: "4" } },
    };
  };

  const srv = createServer((req, res) => {
    const chunks = [];
    req.on("data", (c) => chunks.push(c));
    req.on("end", () => {
      let body = {};
      try { body = JSON.parse(Buffer.concat(chunks).toString() || "{}"); } catch {}
      const path = (req.url || "").replace(/^\/v1\//, "").split("?")[0];
      p.calls.push(`${req.method} ${path}`);
      if (p.down) return req.socket.destroy();
      const reply = (code, v) => { res.writeHead(code, { "content-type": "application/json" }); res.end(JSON.stringify(v)); };
      const fail = (code, message) => reply(code, { error: { code: "x", message } });
      // Blocked by the owner: as services/Auth.kt, for every signed-in request, the message translated.
      if (p.blocked && req.headers.authorization) return reply(403, { error: { code: "FORBIDDEN", message: "Этот аккаунт заблокирован" } });
      if (p.status[path]) return fail(p.status[path], "forced " + path);
      const tun = (p.tunnels[req.headers.authorization || ""] ||= { serverID: "", version: 0 });

      if (path === "auth/email/start") return reply(200, { devCode: p.emailCode });
      if (path === "auth/email/verify") return body.code === p.emailCode ? reply(200, session("dev-mail")) : fail(400, "Неверный код");
      if (path === "auth/pair/redeem") return body.code === p.pairCode ? reply(200, session("dev-pair", TG)) : fail(400, "Код не подходит");
      if (path === "referrals/invite") return p.invite ? reply(200, p.invite) : reply(404, { error: { code: "NOT_FOUND", message: "The referral program is not available" } });
      if (path === "auth/app/start") return reply(200, { id: "login-1", secret: "s", code: "4821", url: "http://127.0.0.1:1/allow" });
      if (path === "auth/app/poll") return reply(200, p.webAllowed ? { session: session("dev-web") } : {});
      if (path === "auth/logout") return reply(200, { ok: true });
      if (path === "me") return reply(200, { user: p.users[req.headers.authorization || ""] || DEMO, entitlement: { plan: "pro", tariffName: "Pro", expiresAt: p.expiresAt } });
      if (path === "servers") {
        p.serversQuery = (req.url || "").split("?")[1] || "";
        const named = (new URLSearchParams(p.serversQuery).get("supportedProtocols") || "").split(",");
        const all = p.vless && named.includes("vless") ? servers.concat(vlessServers()) : servers;
        const list = all.map((s) => ({ ...s, status: p.statuses[s.id] || "ok", ...(p.notices[s.id] ? { notice: p.notices[s.id] } : {}) }));
        return reply(200, { servers: list, ...(p.notice ? { notice: p.notice } : {}) });
      }
      if (path === "keys") return reply(200, { keys: p.keys });
      let m = path.match(/^keys\/([^/]+)\/tunnel$/);
      if (m) {
        const k = p.keys.find((x) => x.id === m[1]);
        if (!k) return fail(404, "no key");
        const id = k.countryCode === "DE" ? "s-de" : "s-nl";
        return reply(200, { config: config(id), privateKey: b64(), version: "k-" + k.id, countryCode: id === "s-de" ? "DE" : "NL" });
      }
      if (path === "tunnel/events") return reply(200, { ok: true });
      if (path === "diagnostics" && req.method === "POST") {
        p.diagnostics.push(body);
        return reply(200, { id: String(p.diagnostics.length) });
      }
      if (path === "tunnel" && req.method === "POST") {
        tun.serverID = body.serverId; tun.version++;
        p.protocols.push(body.protocol);
        return reply(200, config(tun.serverID));
      }
      if (path === "tunnel") return reply(200, { config: config(tun.serverID), version: "v" + tun.version, countryCode: (servers.concat(vlessServers()).find((x) => x.id === tun.serverID) || {}).countryCode || "", ...(p.notices[tun.serverID] ? { notice: p.notices[tun.serverID] } : {}) });
      m = path.match(/^devices\/(.+)$/);
      if (m && req.method === "PATCH") {
        const k = p.keys.find((x) => x.id === decodeURIComponent(m[1]));
        if (!k) return fail(404, "no key");
        k.name = body.name;
        return reply(200, { ok: true });
      }
      if (m && req.method === "DELETE") return reply(200, { ok: true });
      if (path === "app/desktop") return fail(404, "none");
      fail(404, "not found " + path);
    });
  });
  await new Promise((r) => srv.listen(0, "127.0.0.1", r));
  p.url = `http://127.0.0.1:${srv.address().port}`;
  return p;
}

if (process.argv[1] && fileURLToPath(import.meta.url) === process.argv[1]) {
  const p = await startPanel();
  if (process.env.FAKE_NOTICE) p.notice = process.env.FAKE_NOTICE;
  if (process.env.FAKE_VLESS) p.vless = true;
  if (process.env.FAKE_BLOCKED) p.blocked = true;
  if (process.env.FAKE_INVITE) p.invite = SAMPLE_INVITE;
  for (const pair of (process.env.FAKE_STATUS || "").split(",").filter(Boolean)) { const [id, st] = pair.split(":"); p.statuses[id] = st; }
  if (process.env.FAKE_EXPIRES_DAYS) p.expiresAt = new Date(Date.now() + Number(process.env.FAKE_EXPIRES_DAYS) * 86_400_000).toISOString();
  console.log(`fake panel on ${p.url} (plan ends ${p.expiresAt || "never"}); sign in with demo@example.com, code ${p.emailCode}`);
}
