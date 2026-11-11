/** Prices in the plan form: what people type in major units and what the API keeps in minor units. */
import { currentLang } from "@/i18n";

/** "299" or "4,99" → 29900 or 499; NaN when it isn't a price. */
export function toMinor(text: string): number {
  const s = String(text)
    .trim()
    .replace(/[\s\u00a0]/g, "")
    .replace(",", ".");
  return /^\d+(\.\d{1,2})?$/.test(s) ? Math.round(Number(s) * 100) : NaN;
}

/** 29900 → "299", 499 → "4,99" (or "4.99" in English), as the field shows it. */
export function fromMinor(minor: number, lang = currentLang()): string {
  const s = minor % 100 ? (minor / 100).toFixed(2) : String(minor / 100);
  return lang === "en" ? s : s.replace(".", ",");
}
