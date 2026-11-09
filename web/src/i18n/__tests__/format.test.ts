import { describe, expect, it } from "vitest";
import { fill, formatBits, formatBytes, formatDate, formatMoney, formatPercent, formatWhen, pluralForm } from "../format";

describe("tp plural rules (same as app.js)", () => {
  const ru = "{n} день|{n} дня|{n} дней";
  it.each([
    [1, "1 день"],
    [2, "2 дня"],
    [5, "5 дней"],
    [11, "11 дней"],
    [21, "21 день"],
    [22, "22 дня"],
    [12, "12 дней"],
    [104, "104 дня"],
  ])("ru %i", (n, want) => expect(pluralForm("ru", n, ru)).toBe(want));

  it("de and en have two forms", () => {
    expect(pluralForm("de", 1, "{n} Tag|{n} Tage")).toBe("1 Tag");
    expect(pluralForm("de", 2, "{n} Tag|{n} Tage")).toBe("2 Tage");
    expect(pluralForm("de", 0, "{n} Tag|{n} Tage")).toBe("0 Tage");
    expect(pluralForm("en", 1, "{n} day|{n} days")).toBe("1 day");
    expect(pluralForm("en", 21, "{n} day|{n} days")).toBe("21 days");
  });

  it("falls back to the last form and fills other placeholders", () => {
    expect(pluralForm("ru", 5, "{n} device|{n} devices")).toBe("5 devices");
    expect(pluralForm("en", 3, "{n} of {total}|{n} of {total}", { total: 7 })).toBe("3 of 7");
  });
});

describe("fill", () => {
  it("keeps unknown placeholders", () => expect(fill("{a} and {b}", { a: 1 })).toBe("1 and {b}"));
});

describe("money", () => {
  it("formats like app.js", () => {
    expect(formatMoney("ru", 30000, "RUB")).toBe("300 ₽");
    expect(formatMoney("ru", 250000, "RUB")).toBe("2 500 ₽");
    expect(formatMoney("ru", 12345, "RUB")).toBe("123,45 ₽");
    expect(formatMoney("de", 480000, "EUR")).toBe("4.800,00 €");
    expect(formatMoney("en", 500, "EUR")).toBe("€5.00");
    expect(formatMoney("en", 123456, "USD")).toBe("$1,234.56");
    expect(formatMoney("en", 500, "CHF")).toBe("5.00 CHF");
    expect(formatMoney("ru", null, "RUB")).toBe("");
  });
});

describe("date", () => {
  it("formats like app.js", () => {
    expect(formatDate("ru", "2026-03-05")).toBe("5 марта 2026");
    expect(formatDate("de", "2026-03-05T10:00:00Z")).toBe("5. März 2026");
    expect(formatDate("en", "2026-12-31")).toBe("31 Dec 2026");
    expect(formatDate("ru", null)).toBe("");
  });
});

describe("bytes", () => {
  it("formats like app.js", () => {
    expect(formatBytes("ru", 1_500_000_000)).toBe("1,5 ГБ");
    expect(formatBytes("en", 820_000_000)).toBe("820 MB");
    expect(formatBytes("de", 2_000)).toBe("2 KB");
    expect(formatBytes("en", 12)).toBe("12 B");
  });
});

describe("bits, percent and short times (bits(), percent(), shortDate() in app.js)", () => {
  it("bits per second in the language's units", () => {
    expect(formatBits("ru", 12_500_000)).toBe("13 Мбит/с");
    expect(formatBits("en", 1_200_000_000)).toBe("1.2 Gbit/s");
    expect(formatBits("de", 850_000)).toBe("850 Kbit/s");
    expect(formatBits("en", null)).toBe("0");
  });

  it("percent with the decimal separator", () => {
    expect(formatPercent("ru", 2.5)).toBe("2,5%");
    expect(formatPercent("en", 42)).toBe("42%");
    expect(formatPercent("en", undefined)).toBe("0%");
  });

  it("a time of today is the clock, another day has the short date", () => {
    const now = new Date(2026, 2, 5, 18, 0);
    expect(formatWhen("en", new Date(2026, 2, 5, 9, 7).toISOString(), now)).toBe("09:07");
    expect(formatWhen("ru", new Date(2026, 2, 4, 23, 30).toISOString(), now)).toBe("4.03 23:30");
    expect(formatWhen("de", new Date(2026, 2, 4, 23, 30).toISOString(), now)).toBe("4.3. 23:30");
  });
});
