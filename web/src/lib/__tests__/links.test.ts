import jsQR from "jsqr";
import { describe, expect, it } from "vitest";
import { IMPORT_APPS, isImportLink, vlessLocations, withLang } from "../links";
import { qrMatrix, qrPixels } from "../qr";

describe("import links", () => {
  const sub = "https://boop.example/k/abc/vless";
  it("makes the Happ and v2RayTun links, which pass the /tg/open check", () => {
    expect(IMPORT_APPS.map((a) => a.link(sub))).toEqual([`happ://add/${sub}`, `v2raytun://import/${sub}`]);
    for (const a of IMPORT_APPS) expect(isImportLink(a.link(sub))).toBe(true);
  });
  it("refuses anything else", () => {
    for (const bad of [
      "javascript:alert(1)",
      "happ://add/javascript:x",
      'happ://add/https://a.b/"onload',
      "v2raytun://import/ftp://x",
      "https://boop.example",
      "happ://add/https://a b",
    ])
      expect(isImportLink(bad)).toBe(false);
  });
  it("adds the language to a key page link", () => {
    expect(withLang("https://b/k/t", "de")).toBe("https://b/k/t?lang=de");
    expect(withLang("https://b/k/t?x=1", "ru")).toBe("https://b/k/t?x=1&lang=ru");
  });
  it("reads the locations from the link names, each once", () => {
    expect(
      vlessLocations(["vless://a@h:1#Boop%20Germany", "vless://a@h:2#Boop%20Germany", "vless://a@h:3#Boop%20Finland", "vless://a@h:4"]),
    ).toEqual(["Germany", "Finland"]);
  });
});

describe("QR pixels", () => {
  it.each(["https://boop.example/k/abc/vless", "https://панель.example/k/" + "x".repeat(120) + "/vless"])("decode back to %s", (text) => {
    for (const scale of [1, 3]) {
      const { width, data } = qrPixels(qrMatrix(text), scale);
      expect(jsQR(data, width, width)?.data).toBe(text);
    }
  });
  it("keeps a 4-module white margin", () => {
    const m = qrMatrix("hello");
    const { width, data } = qrPixels(m, 2);
    expect(width).toBe((m.size + 8) * 2);
    for (let x = 0; x < width; x++) expect(data[(3 * width + x) * 4]).toBe(255);
  });
});
