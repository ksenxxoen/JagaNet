import { describe, expect, it } from "vitest";
import { moveItem } from "@/components/admin/SortableList";
import { adminNode, testAccount, vlessNode } from "@/test/app";
import { byOrder, moveTargets, nodeBody, nodeValues, sshBody, sshValues, vlessBody, vlessValues } from "../nodes/nodeForm";
import { planBody, planValues } from "../plans/Plans";
import { fromMinor, toMinor } from "../plans/money";
import { alertsBody, wholeOrNull } from "../settings/Alerts";
import { portFor } from "../settings/Email";
import { modesBody } from "../settings/Modes";
import { parseTesters, testersCsv } from "../testers/testers";

describe("nodeBody", () => {
  it("a new node: trimmed fields, upper-case country, defaults", () => {
    const v = { ...nodeValues(), name: " Germany 2 ", city: "Berlin", countryCode: "de", hostname: " ", sortOrder: "" };
    expect(nodeBody(v)).toEqual({
      name: "Germany 2",
      city: "Berlin",
      countryCode: "DE",
      maxPeers: 250,
      active: true,
      hostname: null,
      countryName: null,
      protocol: null,
      vless: null,
      displayName: "",
      sortOrder: 0,
      inSubscription: true,
      cascadeRole: "",
    });
  });

  it("a new VLESS node gets the default tags and flows", () => {
    const v = { ...nodeValues(), name: "F", city: "H", countryCode: "FI", protocol: "vless" as const };
    v.vless = { ...v.vless, host: "fi.example", sni: "fi.example", publicKey: "K", shortIds: "" };
    expect(nodeBody(v).vless).toEqual({
      host: "fi.example",
      sni: "fi.example",
      publicKey: "K",
      shortIds: [""],
      fingerprint: "firefox",
      transports: [
        { type: "tcp", port: 443, tag: "vision", flow: "xtls-rprx-vision" },
        { type: "xhttp", port: 8443, tag: "xhttp", flow: "", path: "", mode: "auto" },
      ],
    });
  });

  it("short ids split by commas; empty ports drop the transport; old tags stay", () => {
    const was = vlessNode().vless!;
    const v = { ...vlessValues(was), shortIds: "a1, b2,", xhttp: "" };
    expect(vlessBody(v, was)).toMatchObject({ shortIds: ["a1", "b2"], transports: [{ type: "tcp", port: 443, tag: "vision-in" }] });
  });

  it("the main server never sends VLESS settings", () => {
    const main = adminNode({ remote: false });
    expect(nodeBody({ ...nodeValues(main), protocol: "vless" }, { local: true, was: main })).toMatchObject({ protocol: null, vless: null });
  });
});

describe("SSH access and the order", () => {
  it("sshBody needs a password or a key; the defaults are port 22 and root", () => {
    expect(() => sshBody(sshValues("203.0.113.1"))).toThrow("Enter the password or the private key");
    expect(sshBody({ ...sshValues(" 203.0.113.1 "), port: "", user: "", privateKey: " KEY " })).toEqual({
      host: "203.0.113.1",
      port: 22,
      user: "root",
      password: null,
      privateKey: "KEY",
    });
  });

  it("byOrder: sort_order, then the name and the id, as the server lists locations", () => {
    const list = [
      adminNode({ id: "b", name: "B", sortOrder: 1 }),
      adminNode({ id: "a2", name: "A", sortOrder: 1 }),
      adminNode({ id: "z", name: "Z", sortOrder: -5 }),
    ];
    expect(byOrder(list).map((n) => n.id)).toEqual(["z", "a2", "b"]);
  });

  it("moveItem moves one item and ignores moves out of range", () => {
    expect(moveItem(["a", "b", "c"], 0, 2)).toEqual(["b", "c", "a"]);
    expect(moveItem(["a", "b", "c"], 2, 0)).toEqual(["c", "a", "b"]);
    expect(moveItem(["a", "b"], 0, 5)).toEqual(["a", "b"]);
  });

  it("devices move only to other nodes that are on and have a protocol", () => {
    const from = adminNode();
    const list = [from, vlessNode(), adminNode({ id: "off", active: false }), adminNode({ id: "new", protocols: [] })];
    expect(moveTargets(list, from).map((n) => n.id)).toEqual(["fi1"]);
  });
});

describe("plans, test accounts and settings", () => {
  it("prices: major units in the field, minor units in the API", () => {
    expect(toMinor("299")).toBe(29900);
    expect(toMinor(" 4,99 ")).toBe(499);
    expect(toMinor("1 299.5")).toBe(129950);
    expect(toMinor("4,999")).toBeNaN();
    expect(fromMinor(29900, "ru")).toBe("299");
    expect(fromMinor(499, "ru")).toBe("4,99");
    expect(fromMinor(499, "en")).toBe("4.99");
  });

  it("planBody: an empty data limit is unlimited; a price of zero is refused", () => {
    const v = { ...planValues(null, 2), name: "X", rub: "100", eur: "1" };
    expect(planBody(v)).toMatchObject({ sort: 3, trafficGb: null, priceRubMinor: 10000, priceEurMinor: 100, badge: null });
    expect(() => planBody({ ...v, eur: "0" })).toThrow("Enter prices as numbers above zero");
  });

  it("parseTesters reads tab and semicolon rows, with or without a number in front", () => {
    expect(parseTesters("Игорь + Таня\t5\n3\tЛеня\t2\tx\nno count\n;\nBob;0\nAnn;3")).toEqual([
      { name: "Игорь + Таня", devices: 5 },
      { name: "Леня", devices: 2 },
      { name: "Ann", devices: 3 },
    ]);
  });

  it("testersCsv: BOM, semicolons, one column per key", () => {
    const csv = testersCsv([testAccount(), testAccount({ name: 'A "B"', keys: ["k1"], vpnKeys: [] })]);
    expect(csv.startsWith("﻿")).toBe(true);
    const [head, first, second] = csv.slice(1).split("\r\n");
    expect(head).toBe('"Client";"Email";"Devices";"Valid until";"Key 1";"Key 2";"AmneziaVPN key 1";"AmneziaVPN key 2"');
    expect(first).toContain('"https://boop.example/k/key-a";"https://boop.example/k/key-b";"vpn://AAAA";"vpn://BBBB"');
    expect(second).toMatch(/^"A ""B""";.*"k1";"";"";""$/);
  });

  it("the SMTP port follows the encryption unless it was changed by hand", () => {
    expect(portFor("ssl", "587")).toBe("465");
    expect(portFor("none", "")).toBe("25");
    expect(portFor("ssl", "2525")).toBe("2525");
  });

  it("alerts and the channel", () => {
    expect(alertsBody("a@b.c, c@d.e\n", "1 ; -100")).toEqual({ emails: ["a@b.c", "c@d.e"], telegramChats: [1, -100] });
    expect(() => alertsBody("", "@me")).toThrow("A Telegram chat id is a number");
    expect(wholeOrNull(" ")).toBeNull();
    expect(wholeOrNull("1 000")).toBe(1000);
    expect(() => wholeOrNull("1.5")).toThrow("Enter a whole number");
  });

  it("test payments are never sent while a payment service is connected", () => {
    expect(modesBody(true, true, true)).toEqual({ showSignInCodes: true, testPayments: false });
    expect(modesBody(false, true, false)).toEqual({ showSignInCodes: false, testPayments: true });
  });
});
