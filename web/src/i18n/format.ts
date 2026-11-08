/**
 * Formatting helpers moved from app.js with the same output: plural forms, dates, money, bytes.
 * Pure functions of the language, so they are easy to test; components use the bound versions
 * from useI18n() / the i18n module.
 */
export type Lang = "ru" | "de" | "en";
export const LANGS: ReadonlyArray<readonly [Lang, string]> = [
  ["ru", "Русский"],
  ["de", "Deutsch"],
  ["en", "English"],
];
export const isLang = (x: unknown): x is Lang => LANGS.some(([c]) => c === x);

/** {name} placeholders; unknown ones stay as they are (fill() in app.js). */
export const fill = (s: string, args?: Record<string, unknown>) =>
  s.replace(/\{(\w+)\}/g, (m, k: string) => (args && k in args ? String(args[k]) : m));

/** Index of the plural form: ru has 3 forms (1, 2–4, 5+), de and en have 2 (1, other). */
export function pluralIndex(lang: Lang, n: number): number {
  if (lang === "ru") {
    const a = n % 10;
    const b = n % 100;
    return a === 1 && b !== 11 ? 0 : a >= 2 && a <= 4 && (b < 12 || b > 14) ? 1 : 2;
  }
  return n === 1 ? 0 : 1;
}

/** "a|b|c" (already translated) for n, with {n} and the other placeholders filled. */
export function pluralForm(lang: Lang, n: number, forms: string, args?: Record<string, unknown>) {
  const v = forms.split("|");
  return fill(v[Math.min(pluralIndex(lang, n), v.length - 1)], { n, ...args });
}

const MONTHS: Record<Lang, string[]> = {
  en: ["Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec"],
  ru: ["января", "февраля", "марта", "апреля", "мая", "июня", "июля", "августа", "сентября", "октября", "ноября", "декабря"],
  de: ["Januar", "Februar", "März", "April", "Mai", "Juni", "Juli", "August", "September", "Oktober", "November", "Dezember"],
};

/** "5 марта 2026", "5. März 2026", "5 Mar 2026" from an ISO date or date-time (its date part). */
export function formatDate(lang: Lang, iso: string | null | undefined): string {
  if (!iso) return "";
  const [y, m, d] = iso.slice(0, 10).split("-").map(Number);
  return lang === "de" ? `${d}. ${MONTHS.de[m - 1]} ${y}` : `${d} ${MONTHS[lang][m - 1]} ${y}`;
}

const pad = (n: number) => String(n).padStart(2, "0");
export const clock = (iso: string) => {
  const d = new Date(iso);
  return `${pad(d.getHours())}:${pad(d.getMinutes())}`;
};
export const localDay = (iso: string) => {
  const d = new Date(iso);
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}`;
};
/** Local date and time: "5 марта 2026 14:07". */
export const formatDateTime = (lang: Lang, iso: string | null | undefined) =>
  iso ? `${formatDate(lang, localDay(iso))} ${clock(iso)}` : "";

/** Minor units to text: "300 ₽", "2,50 €", "$2.50" (money() in app.js). */
export function formatMoney(lang: Lang, minor: number | null | undefined, currency: string): string {
  if (minor == null) return "";
  const sym = ({ USD: "$", EUR: "€", GBP: "£", RUB: "₽" } as Record<string, string>)[currency] || currency;
  const cents = String(minor % 100).padStart(2, "0");
  const whole = String(Math.floor(minor / 100)).replace(/\B(?=(\d{3})+(?!\d))/g, { ru: " ", de: ".", en: "," }[lang]);
  if (lang === "en") return sym.length === 1 ? `${sym}${whole}.${cents}` : `${whole}.${cents} ${sym}`;
  return currency === "RUB" && cents === "00" ? `${whole} ${sym}` : `${whole},${cents} ${sym}`;
}

/** 12.5 with the language's decimal separator; whole numbers from 10 up (num() in app.js). */
export function formatNum(lang: Lang, v: number, digits = v < 10 && !Number.isInteger(v) ? 1 : 0): string {
  const s = (Math.round(v * 10 ** digits) / 10 ** digits).toFixed(digits);
  return lang === "en" ? s : s.replace(".", ",");
}

/** Same units as the apps (Format.bytes): "1,5 ГБ", "820 MB". */
export function formatBytes(lang: Lang, b: number): string {
  const u = (x: "B" | "KB" | "MB" | "GB" | "TB") => (lang === "ru" ? { B: "Б", KB: "КБ", MB: "МБ", GB: "ГБ", TB: "ТБ" }[x] : x);
  if (b >= 1e12) return `${formatNum(lang, b / 1e12, 1)} ${u("TB")}`;
  if (b >= 1e9) return `${formatNum(lang, b / 1e9, 1)} ${u("GB")}`;
  if (b >= 1e6) return `${Math.round(b / 1e6)} ${u("MB")}`;
  if (b >= 1e3) return `${Math.round(b / 1e3)} ${u("KB")}`;
  return `${b} ${u("B")}`;
}
