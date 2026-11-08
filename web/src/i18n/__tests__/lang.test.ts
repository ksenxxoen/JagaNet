import { afterEach, beforeAll, describe, expect, it, vi } from "vitest";
import { currentLang, i18n, initI18n, pickLang, setLang, t, tp } from "..";

const dict: Record<string, Record<string, string>> = {
  ru: { "Sign in": "Войти", "{n} day|{n} days": "{n} день|{n} дня|{n} дней", "Hi {name}": "Привет, {name}" },
  de: { "Sign in": "Anmelden", "{n} day|{n} days": "{n} Tag|{n} Tage" },
};

beforeAll(() => {
  vi.stubGlobal(
    "fetch",
    vi.fn(async (url: string) => {
      const lang = String(url).split("/").pop()!;
      return new Response(JSON.stringify(dict[lang] ?? {}), { status: 200, headers: { "Content-Type": "application/json" } });
    }),
  );
});

afterEach(() => {
  localStorage.clear();
  window.history.replaceState(null, "", "/");
});

describe("language choice", () => {
  it("?lang= first, then the saved one, then the browser, then ru", () => {
    window.history.replaceState(null, "", "/?lang=de");
    localStorage.setItem("lang", "en");
    expect(pickLang()).toBe("de");
    window.history.replaceState(null, "", "/");
    expect(pickLang()).toBe("en");
    localStorage.clear();
    const nav = vi.spyOn(navigator, "languages", "get").mockReturnValue(["fr-FR", "de-AT"]);
    expect(pickLang()).toBe("de");
    nav.mockReturnValue(["fr-FR"]);
    vi.spyOn(navigator, "language", "get").mockReturnValue("fr-FR");
    expect(pickLang()).toBe("ru");
    vi.restoreAllMocks();
  });
});

describe("switching the language", () => {
  it("loads the texts, saves the choice and writes the lang cookie", async () => {
    await initI18n("ru");
    expect(t("Sign in")).toBe("Войти");
    expect(t("Hi {name}", { name: "Аня" })).toBe("Привет, Аня");
    expect(tp(22, "{n} day|{n} days")).toBe("22 дня");
    expect(document.documentElement.lang).toBe("ru");

    await setLang("de");
    expect(currentLang()).toBe("de");
    expect(t("Sign in")).toBe("Anmelden");
    expect(tp(2, "{n} day|{n} days")).toBe("2 Tage");
    expect(localStorage.getItem("lang")).toBe("de");
    expect(document.cookie).toContain("lang=de");

    const calls = (fetch as unknown as ReturnType<typeof vi.fn>).mock.calls.length;
    await setLang("en");
    expect(t("Sign in")).toBe("Sign in");
    expect(tp(1, "{n} day|{n} days")).toBe("1 day");
    expect(t("Unknown {x}")).toBe("Unknown {x}");
    // English is the key itself: nothing is downloaded.
    expect((fetch as unknown as ReturnType<typeof vi.fn>).mock.calls.length).toBe(calls);

    await setLang("xx");
    expect(i18n.language).toBe("ru");
  });
});
