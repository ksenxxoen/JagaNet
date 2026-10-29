"""Tests for the node agent (server/src/main/resources/node/agent.py), Xray's API mocked.

Run: python3 -m unittest discover -s server/src/test/python   (AgentScriptTest runs it in :server:test)
The fake Xray answers like Xray-core 26.x does (outputs recorded from `xray api` on a real one).
"""
import importlib.util
import json
import os
import pathlib
import subprocess
import tempfile
import unittest
from unittest import mock

AGENT = pathlib.Path(__file__).resolve().parents[2] / "main" / "resources" / "node" / "agent.py"
U1 = "6286d380-3d9c-4118-b62e-8b761a279e20"
U2 = "11111111-2222-4333-8444-555555555555"
U3 = "99999999-aaaa-4bbb-8ccc-dddddddddddd"


def load_agent(env):
    d = tempfile.mkdtemp()
    path = os.path.join(d, "env")
    with open(path, "w") as f:
        f.write("".join("%s=%s\n" % kv for kv in env.items()))
    with mock.patch.dict(os.environ, {"JAGANET_NODE_ENV": path}):
        spec = importlib.util.spec_from_file_location("agent_under_test_%d" % id(env), AGENT)
        mod = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(mod)
    return mod


class FakeXray:
    """Inbounds with their users and per-user counters, behind `xray api ...` as subprocess.run sees it."""

    def __init__(self, tags=("vision", "xhttp")):
        self.inbounds = {t: {} for t in tags}  # tag -> email -> account
        self.stats = {}  # counter name -> value
        self.calls = []
        self.up = True

    def restart(self):
        """Xray restarted: users added through the API are gone, counters start from zero."""
        for t in self.inbounds:
            self.inbounds[t] = {}
        self.stats = {}

    def run(self, cmd, capture_output=True, text=True, timeout=None, **kw):
        assert cmd[0] == "/usr/local/bin/xray" and cmd[1] == "api" and cmd[3] == "--server=127.0.0.1:10085", cmd
        sub, args = cmd[2], cmd[4:]
        self.calls.append((sub, args))
        if not self.up:
            return subprocess.CompletedProcess(cmd, 1, "", "failed to dial 127.0.0.1:10085\n")
        out = getattr(self, "do_" + sub)(args)
        if isinstance(out, tuple):
            return subprocess.CompletedProcess(cmd, out[0], "", out[1])
        return subprocess.CompletedProcess(cmd, 0, out, "")

    def do_inbounduser(self, args):
        tag = args[0].split("=", 1)[1]
        if tag not in self.inbounds:
            return 1, "failed to get inbound user: rpc error: code = Unknown desc = app/proxyman/command: failed to get handler: %s > app/proxyman/inbound: handler not found: %s\n" % (tag, tag)
        users = self.inbounds[tag]
        if not users:
            return "{}\n\n"
        return json.dumps({"users": [{"account": dict(a, _TypedMessage_="xray.proxy.vless.Account"), "email": e}
                                     for e, a in users.items()]}, indent=4) + "\n\n"

    def do_adu(self, args):
        with open(args[0]) as f:
            doc = json.load(f)
        lines, n = [], 0
        for inb in doc["inbounds"]:
            assert inb["port"] and inb["protocol"] == "vless" and inb["settings"]["decryption"] == "none"
            lines.append("processing inbound: " + inb["tag"])
            for c in inb["settings"]["clients"]:
                lines.append("add user: " + c["email"])
                users = self.inbounds[inb["tag"]]
                if c["email"] in users:
                    lines.append("rpc error: code = Unknown desc = proxy/vless: User %s already exists." % c["email"])
                else:
                    users[c["email"]] = {"id": c["id"], **({"flow": c["flow"]} if c.get("flow") else {})}
                    lines.append("result: ok")
                    n += 1
        return "\n".join(lines + ["Added %d user(s) in total." % n]) + "\n"

    def do_rmu(self, args):
        tag, emails = args[0].split("=", 1)[1], args[1:]
        lines, n = [], 0
        for e in emails:
            lines.append("remove user: " + e)
            if self.inbounds[tag].pop(e, None) is None:
                lines.append("rpc error: code = Unknown desc = proxy/vless: User %s not found." % e)
            else:
                n += 1
        return "\n".join(lines + ["Removed %d user(s) in total." % n]) + "\n"

    def do_statsquery(self, args):
        assert args == ["-pattern", "user>>>"], args
        stat = [{"name": k, **({"value": v} if v else {})} for k, v in self.stats.items()]
        if not stat:
            return "{}\n\n"
        return json.dumps({"stat": stat}, indent=4) + "\n\n"


VLESS_ENV = {"JAGANET_URL": "https://panel.example.com/", "NODE_TOKEN": "t" * 30, "PROTOCOL": "vless",
             "XRAY_BIN": "/usr/local/bin/xray", "XRAY_API": "127.0.0.1:10085", "XRAY_INBOUNDS": "vision,xhttp", "WAN_IF": "eth0"}


class VlessAgentTest(unittest.TestCase):
    def setUp(self):
        self.agent = load_agent(VLESS_ENV)
        self.xray = FakeXray()
        p = mock.patch.object(self.agent.subprocess, "run", self.xray.run)
        p.start()
        self.addCleanup(p.stop)

    def peers(self, *uuids):
        return [{"publicKey": u, "address": "10.9.0.%d" % (i + 2)} for i, u in enumerate(uuids)]

    def test_adds_users_to_every_inbound_with_the_panels_flows(self):
        inbounds = [{"tag": "vision", "flow": "xtls-rprx-vision"}, {"tag": "xhttp", "flow": ""}]
        self.assertEqual((4, 0), self.agent.apply_vless(self.peers(U1, U2), inbounds))
        self.assertEqual({U1: {"id": U1, "flow": "xtls-rprx-vision"}, U2: {"id": U2, "flow": "xtls-rprx-vision"}}, self.xray.inbounds["vision"])
        self.assertEqual({U1: {"id": U1}, U2: {"id": U2}}, self.xray.inbounds["xhttp"])

    def test_env_flow_wins_over_the_panel(self):
        self.agent.XRAY_INBOUNDS = "vision:xtls-rprx-vision,xhttp"
        self.agent.apply_vless(self.peers(U1), [])
        self.assertEqual("xtls-rprx-vision", self.xray.inbounds["vision"][U1]["flow"])
        self.assertNotIn("flow", self.xray.inbounds["xhttp"][U1])

    def test_removes_only_managed_users_and_is_idempotent(self):
        self.xray.inbounds["vision"]["owner-test@example.com"] = {"id": U3}
        self.agent.apply_vless(self.peers(U1, U2))
        self.assertEqual((0, 2), self.agent.apply_vless(self.peers(U2)))
        self.assertEqual({U2, "owner-test@example.com"}, set(self.xray.inbounds["vision"]))
        self.assertEqual({U2}, set(self.xray.inbounds["xhttp"]))
        calls = len(self.xray.calls)
        self.assertEqual((0, 0), self.agent.apply_vless(self.peers(U2)))
        self.assertEqual(["inbounduser", "inbounduser"], [c[0] for c in self.xray.calls[calls:]])

    def test_rejects_anything_but_uuids(self):
        self.agent.apply_vless(self.peers("-tag=evil", "not a uuid", U1.upper()))
        self.assertEqual({U1}, set(self.xray.inbounds["vision"]))

    def test_users_come_back_after_an_xray_restart(self):
        answers = [{"version": "v1", "peers": self.peers(U1, U2), "inbounds": [{"tag": "vision", "flow": "xtls-rprx-vision"}]}] * 2
        with mock.patch.object(self.agent, "api", side_effect=lambda *a, **k: answers.pop(0)) as api:
            self.assertEqual("v1", self.agent.sync_once(None))
            self.xray.restart()
            # Same version: the long poll timed out, the users are put back anyway.
            self.assertEqual("v1", self.agent.sync_once("v1"))
        self.assertEqual({U1, U2}, set(self.xray.inbounds["vision"]))
        self.assertEqual({U1, U2}, set(self.xray.inbounds["xhttp"]))
        self.assertEqual("/v1/node/peers?version=v1", api.call_args_list[1][0][1])

    def test_xray_down_is_an_error_for_the_retry_loop(self):
        self.xray.up = False
        with self.assertRaises(self.agent.XrayError):
            self.agent.apply_vless(self.peers(U1))

    def test_unknown_inbound_tag_is_an_error(self):
        self.agent.XRAY_INBOUNDS = "vision,nope"
        with self.assertRaises(self.agent.XrayError) as e:
            self.agent.apply_vless(self.peers(U1))
        self.assertIn("handler not found: nope", str(e.exception))

    def test_reports_traffic_from_statsquery(self):
        up, down = "user>>>%s>>>traffic>>>uplink" % U1, "user>>>%s>>>traffic>>>downlink" % U1
        self.xray.stats = {up: 164, down: 0, "inbound>>>vision>>>traffic>>>uplink": 5, "user>>>someone@x>>>traffic>>>uplink": 9}
        self.assertEqual([{"publicKey": U1, "rx": 164, "tx": 0, "lastHandshake": 0, "torrentRx": 0, "torrentTx": 0}], self.agent.vless_peers(now=1000))
        self.xray.stats[down] = 5000
        self.assertEqual(2000, self.agent.vless_peers(now=2000)[0]["lastHandshake"])
        self.assertEqual(2000, self.agent.vless_peers(now=3000)[0]["lastHandshake"])  # no traffic: unchanged
        self.xray.stats = {}
        self.assertEqual([], self.agent.vless_peers(now=4000))

    def test_single_stat_object_and_report_body(self):
        with mock.patch.object(self.agent.subprocess, "run",
                               lambda cmd, **k: subprocess.CompletedProcess(cmd, 0, json.dumps({"stat": {"name": "user>>>%s>>>traffic>>>uplink" % U2, "value": 7}}), "")):
            self.assertEqual({U2: [7, 0]}, self.agent.xray_traffic())
        with mock.patch.object(self.agent, "system", return_value=({"cpu": 0.1}, (1, 1))), \
                mock.patch.object(self.agent, "api") as api:
            self.agent.report_once(None)
        method, path, body = api.call_args[0]
        self.assertEqual(("POST", "/v1/node/report"), (method, path))
        self.assertEqual("vless", body["protocol"])
        self.assertEqual("off", body["system"]["classes"])

    def test_requests_carry_the_agent_user_agent_and_token(self):
        seen = {}

        class Res:
            def __enter__(self): return self
            def __exit__(self, *a): return False
            def read(self): return b'{"ok": true}'

        def urlopen(req, timeout):
            seen["req"] = req
            return Res()

        with mock.patch.object(self.agent.urllib.request, "urlopen", urlopen):
            self.assertEqual({"ok": True}, self.agent.api("GET", "/v1/node/peers"))
        req = seen["req"]
        self.assertEqual("https://panel.example.com/v1/node/peers", req.full_url)
        self.assertEqual("BoopNodeAgent/1", req.get_header("User-agent"))
        self.assertEqual("Bearer " + "t" * 30, req.get_header("Authorization"))


class AwgAgentTest(unittest.TestCase):
    """The AmneziaWG mode keeps working as before."""

    def setUp(self):
        self.agent = load_agent({"JAGANET_URL": "https://panel.example.com", "NODE_TOKEN": "t" * 30, "IFACE": "awg0",
                                 "TOOL": "awg", "PROTOCOL": "amneziawg", "WAN_IF": "eth0"})
        self.cmds = []

        def run(cmd, capture_output=False, text=False, check=False, **kw):
            self.cmds.append(cmd)
            out = "priv\tpub\t51820\toff\nKEY1=\t(none)\t1.2.3.4:5\t10.8.0.2/32\t1700000000\t10\t20\toff\n" if cmd[1] == "show" else ""
            return subprocess.CompletedProcess(cmd, 0, out, "")

        p = mock.patch.object(self.agent.subprocess, "run", run)
        p.start()
        self.addCleanup(p.stop)

    def test_sync_applies_peers_and_limit_only_on_a_new_version(self):
        res = {"version": "v2", "peers": [{"publicKey": "KEY2=", "address": "10.8.0.3"}], "limit": None}
        with mock.patch.object(self.agent, "api", return_value=res), mock.patch.object(self.agent, "write_limits") as wl:
            self.assertEqual("v2", self.agent.sync_once("v1"))
            self.assertEqual("v2", self.agent.sync_once("v2"))
        self.assertEqual(1, wl.call_count)
        self.assertIn(["awg", "set", "awg0", "peer", "KEY1=", "remove"], self.cmds)
        self.assertIn(["awg", "set", "awg0", "peer", "KEY2=", "allowed-ips", "10.8.0.3/32"], self.cmds)
        self.assertFalse(any(c[0] == "xray" for c in self.cmds))

    def test_report_uses_the_interface_dump(self):
        with mock.patch.object(self.agent, "system", return_value=({"cpu": 0.1}, (1, 1))), \
                mock.patch.object(self.agent, "classes", return_value=({}, "off")), \
                mock.patch.object(self.agent, "api") as api:
            self.agent.report_once(None)
        body = api.call_args[0][2]
        self.assertEqual("amneziawg", body["protocol"])
        self.assertEqual([{"publicKey": "KEY1=", "rx": 10, "tx": 20, "lastHandshake": 1700000000, "torrentRx": 0, "torrentTx": 0}], body["peers"])


if __name__ == "__main__":
    unittest.main()
