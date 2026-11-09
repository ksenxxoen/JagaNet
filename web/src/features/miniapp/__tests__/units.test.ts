import { afterEach, describe, expect, it, vi } from "vitest";
import { importLinkFrom, openImportLink } from "../open";
import { firstScreen } from "../screens";
import { createTelegram, openPagePath } from "../telegram";
import { applyTheme } from "../theme";
import { fakeTg } from "./fake";

afterEach(() => {
  vi.restoreAllMocks();
  document.documentElement.removeAttribute("style");
  delete document.documentElement.dataset.scheme;
});

describe("the first screen", () => {
  it("comes from #/screen, ?screen= or startapp, else home", () => {
    expect(firstScreen("#/pay", "", undefined)).toBe("pay");
    expect(firstScreen("#devices", "", undefined)).toBe("devices");
    expect(firstScreen("", "?screen=support", undefined)).toBe("support");
    expect(firstScreen("", "", "profile")).toBe("profile");
    expect(firstScreen("", "", "connect")).toBe("connect");
    // The link wins over the start parameter, unknown screens and referral codes open home.
    expect(firstScreen("#/pay", "", "devices")).toBe("pay");
    expect(firstScreen("#/admin", "?screen=x", "ref_ABC")).toBe("home");
    expect(firstScreen("", "", undefined)).toBe("home");
  });
});

describe("Telegram links", () => {
  it("opens web pages through Telegram, absolute; a new tab without it", () => {
    const tg = fakeTg();
    createTelegram(tg, "bot").openLink("/privacy");
    expect(tg.opened).toEqual([`${window.location.origin}/privacy`]);
    const open = vi.spyOn(window, "open").mockReturnValue(null);
    createTelegram(null, "bot").openLink("https://pay.example/c/1");
    expect(open).toHaveBeenCalledWith("https://pay.example/c/1", "_blank", "noopener");
  });

  it("opens t.me links in Telegram from 6.1, else as a web page", () => {
    const tg = fakeTg();
    createTelegram(tg, "bot").openTelegram("https://t.me/bot?start=support");
    expect(tg.tgOpened).toEqual(["https://t.me/bot?start=support"]);
    const old = fakeTg({ isVersionAtLeast: (v) => v === "6.0" });
    createTelegram(old, "bot").openTelegram("https://t.me/bot");
    expect(old.tgOpened).toEqual([]);
    expect(old.opened).toEqual(["https://t.me/bot"]);
  });

  it("hands import links to /tg/open in the fragment, and only import links", () => {
    const tg = fakeTg();
    const t = createTelegram(tg, "bot");
    t.openApp("happ://add/https://boop.example/k/tok/vless");
    expect(tg.opened).toEqual([`${window.location.origin}/tg/open#${encodeURIComponent("happ://add/https://boop.example/k/tok/vless")}`]);
    t.openApp("javascript:alert(1)");
    t.openApp("happ://add/ftp://x");
    expect(tg.opened).toHaveLength(1);
    expect(openPagePath("/tg/app-next")).toBe("/tg/open-next");
    expect(openPagePath("/tg/app")).toBe("/tg/open");
  });

  it("haptics only from 6.1, and a broken client never throws", () => {
    const tg = fakeTg();
    createTelegram(tg, "bot").haptic.ok();
    expect(tg.HapticFeedback!.notificationOccurred).toHaveBeenCalledWith("success");
    const broken = fakeTg({
      HapticFeedback: {
        selectionChanged: () => {
          throw new Error("x");
        },
        notificationOccurred: () => {
          throw new Error("x");
        },
      },
    });
    expect(() => createTelegram(broken, "bot").haptic.tap()).not.toThrow();
    const old = fakeTg({ isVersionAtLeast: () => false });
    createTelegram(old, "bot").haptic.err();
    expect(old.HapticFeedback!.notificationOccurred).not.toHaveBeenCalled();
  });
});

describe("the theme", () => {
  it("takes Telegram's scheme and colors and gives Telegram the page's background", () => {
    const root = document.documentElement;
    const tg = fakeTg({
      colorScheme: "dark",
      themeParams: {
        bg_color: "#17212b",
        secondary_bg_color: "#0e1621",
        text_color: "#f5f5f5",
        hint_color: "#708499",
        link_color: "#6ab3f3",
      },
      setBackgroundColor: vi.fn(),
      setHeaderColor: vi.fn(),
      setBottomBarColor: vi.fn(),
    });
    applyTheme(tg, root);
    expect(root.dataset.scheme).toBe("dark");
    expect(root.style.getPropertyValue("--bg")).toBe("#0e1621");
    expect(root.style.getPropertyValue("--surface")).toBe("#17212b");
    expect(root.style.getPropertyValue("--heading")).toBe("#f5f5f5");
    expect(root.style.getPropertyValue("--muted")).toBe("#708499");
    expect(root.style.getPropertyValue("--link")).toBe("#6ab3f3");
    expect(tg.setBackgroundColor).toHaveBeenCalledWith("#0e1621");
    expect(tg.setHeaderColor).toHaveBeenCalledWith("#0e1621");
    expect(tg.setBottomBarColor).toHaveBeenCalledWith("#17212b");

    // A light chat without colors: the light tokens, nothing left over from before.
    applyTheme(fakeTg({ colorScheme: "light", themeParams: {} }), root);
    expect(root.dataset.scheme).toBe("light");
    expect(root.style.getPropertyValue("--bg")).toBe("");
  });

  it("outside Telegram follows the system", () => {
    vi.stubGlobal("matchMedia", () => ({ matches: true }));
    applyTheme(null);
    expect(document.documentElement.dataset.scheme).toBe("dark");
    vi.unstubAllGlobals();
  });
});

describe("/tg/open", () => {
  const page = () => {
    document.body.innerHTML = `<a id="go" href="#" hidden>Open</a><p id="bad" hidden>Bad</p>`;
  };

  it("accepts only Happ and v2RayTun links with a web subscription", () => {
    expect(importLinkFrom("#" + encodeURIComponent("happ://add/https://boop.example/k/t/vless"))).toBe(
      "happ://add/https://boop.example/k/t/vless",
    );
    expect(importLinkFrom("#v2raytun://import/https://boop.example/k/t/vless")).toBe("v2raytun://import/https://boop.example/k/t/vless");
    expect(importLinkFrom("#" + encodeURIComponent("happ://add/javascript:alert(1)"))).toBeNull();
    expect(importLinkFrom("#" + encodeURIComponent('happ://add/https://x"><script>'))).toBeNull();
    expect(importLinkFrom("#%E0%A4%A")).toBeNull();
    expect(importLinkFrom("")).toBeNull();
  });

  it("opens a good link and takes it out of the address bar; a bad one shows the warning", () => {
    page();
    const replace = vi.spyOn(history, "replaceState");
    const loc = { hash: "#" + encodeURIComponent("happ://add/https://boop.example/k/t/vless"), pathname: "/tg/open", href: "" } as Location;
    expect(openImportLink(document, loc)).toBe("happ://add/https://boop.example/k/t/vless");
    expect(loc.href).toBe("happ://add/https://boop.example/k/t/vless");
    expect(replace).toHaveBeenCalledWith(null, "", "/tg/open");
    const go = document.getElementById("go") as HTMLAnchorElement;
    expect(go.hidden).toBe(false);
    expect(go.getAttribute("href")).toBe("happ://add/https://boop.example/k/t/vless");

    page();
    const bad = { hash: "#javascript:alert(1)", pathname: "/tg/open", href: "" } as Location;
    expect(openImportLink(document, bad)).toBeNull();
    expect(bad.href).toBe("");
    expect(document.getElementById("bad")!.hidden).toBe(false);
    expect(document.getElementById("go")!.hidden).toBe(true);
  });
});
