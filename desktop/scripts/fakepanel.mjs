// A stand-in for the panel's /v1 API with made-up data, for scripts/e2e.mjs.
// The test steers it through the returned object: `down` drops connections, `status[path]` forces an
// HTTP status for a path (after /v1/), `calls` records what the client asked for, `expiresAt` is the
// plan's end that /v1/me sends (null: a plan without an end), `statuses` / `notices` (node id -> value)
// and `notice` are the owner's node statuses and notes that /v1/servers sends (issue #10).
//
// Run on its own it serves until stopped, for trying the dev build by hand:
//
//   FAKE_EXPIRES_DAYS=2 node scripts/fakepanel.mjs      # prints the URL; plan ends in 2 days
//   FAKE_NOTICE="Плановые работы" FAKE_STATUS=s-nl:maintenance,s-fi:degraded node scripts/fakepanel.mjs
//   BOOP_API=<that URL> go run .                        # sign in with demo@example.com / 123456
import { createServer } from "node:http";
import { randomBytes } from "node:crypto";
import { fileURLToPath } from "node:url";

const DEFAULT_EXPIRES = "2027-03-15T00:00:00Z";

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
    tunnels: {}, // session token -> { serverID, version }: each signed-in device has its own tunnel
    url: "",
    close: () => new Promise((r) => srv.close(r)),
    reset() {
      p.down = false; p.status = {}; p.calls = []; p.webAllowed = false; p.expiresAt = DEFAULT_EXPIRES;
      p.statuses = {}; p.notices = {}; p.notice = null;
    },
  };
  const servers = [
    { id: "s-nl", name: "NL 1", countryCode: "NL", load: 0.1, protocols: ["amneziawg"], host: "203.0.113.5" },
    { id: "s-de", name: "DE 1", countryCode: "DE", load: 0.1, protocols: ["amneziawg"], host: "203.0.113.6" },
    { id: "s-fi", name: "FI 1", countryCode: "FI", load: 0.2, protocols: ["amneziawg"], host: "203.0.113.7" },
  ];
  let sessions = 0;
  const session = (device) => ({ token: `tok-${device}-${++sessions}`, user: { id: "u1", email: "demo@example.com" }, deviceId: device });
  const config = (id) => {
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
      if (p.status[path]) return fail(p.status[path], "forced " + path);
      const tun = (p.tunnels[req.headers.authorization || ""] ||= { serverID: "", version: 0 });

      if (path === "auth/email/start") return reply(200, { devCode: p.emailCode });
      if (path === "auth/email/verify") return body.code === p.emailCode ? reply(200, session("dev-mail")) : fail(400, "Неверный код");
      if (path === "auth/pair/redeem") return body.code === p.pairCode ? reply(200, session("dev-pair")) : fail(400, "Код не подходит");
      if (path === "auth/app/start") return reply(200, { id: "login-1", secret: "s", code: "4821", url: "http://127.0.0.1:1/allow" });
      if (path === "auth/app/poll") return reply(200, p.webAllowed ? { session: session("dev-web") } : {});
      if (path === "auth/logout") return reply(200, { ok: true });
      if (path === "me") return reply(200, { user: { id: "u1", email: "demo@example.com" }, entitlement: { plan: "pro", tariffName: "Pro", expiresAt: p.expiresAt } });
      if (path === "servers") {
        const list = servers.map((s) => ({ ...s, status: p.statuses[s.id] || "ok", ...(p.notices[s.id] ? { notice: p.notices[s.id] } : {}) }));
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
      if (path === "tunnel" && req.method === "POST") {
        tun.serverID = body.serverId; tun.version++;
        return reply(200, config(tun.serverID));
      }
      if (path === "tunnel") return reply(200, { config: config(tun.serverID), version: "v" + tun.version, countryCode: (servers.find((x) => x.id === tun.serverID) || {}).countryCode || "", ...(p.notices[tun.serverID] ? { notice: p.notices[tun.serverID] } : {}) });
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
  for (const pair of (process.env.FAKE_STATUS || "").split(",").filter(Boolean)) { const [id, st] = pair.split(":"); p.statuses[id] = st; }
  if (process.env.FAKE_EXPIRES_DAYS) p.expiresAt = new Date(Date.now() + Number(process.env.FAKE_EXPIRES_DAYS) * 86_400_000).toISOString();
  console.log(`fake panel on ${p.url} (plan ends ${p.expiresAt || "never"}); sign in with demo@example.com, code ${p.emailCode}`);
}
