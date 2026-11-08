/**
 * Texts: the key is the English text, translations come from /v1/i18n/<lang> (built from
 * shared/.../i18n/strings), English needs no download. Placeholders are {name} as in the old site,
 * plurals are "a|b|c" strings picked by tp() with the ru/de/en rules from app.js.
 *
 * Language order: ?lang= → saved (localStorage "lang") → browser language → ru.
 * setLang() saves the choice and writes the "lang" cookie like setLang() in app.js.
 */
import i18next, { type i18n as I18n } from "i18next";
import LanguageDetector from "i18next-browser-languagedetector";
import HttpBackend from "i18next-http-backend";
import { initReactI18next, useTranslation } from "react-i18next";
import { store } from "@/lib/storage";
import { fill, formatBytes, formatDate, formatDateTime, formatMoney, formatNum, isLang, pluralForm, type Lang } from "./format";

export { LANGS, type Lang } from "./format";

export const i18n: I18n = i18next.createInstance();

/** First supported language from the detector (query, saved, browser), else ru. */
export function pickLang(): Lang {
  // With languageUtils present the detector returns every candidate, not just the first one.
  const services = { languageUtils: { getBestMatchFromCodes: () => undefined } } as unknown as ConstructorParameters<
    typeof LanguageDetector
  >[0];
  const detector = new LanguageDetector(services, {
    order: ["querystring", "localStorage", "navigator"],
    lookupQuerystring: "lang",
    lookupLocalStorage: "lang",
    caches: [],
  });
  let found: string | string[] | undefined;
  try {
    found = detector.detect();
  } catch {
    found = undefined;
  }
  const list = (Array.isArray(found) ? found : found ? [found] : []).map((x) => x.toLowerCase().split("-")[0]);
  return list.find(isLang) ?? "ru";
}

export async function initI18n(lang: Lang = pickLang()) {
  if (!i18n.isInitialized) {
    await i18n
      .use(HttpBackend)
      .use(initReactI18next)
      .init({
        lng: lang,
        supportedLngs: ["ru", "de", "en"],
        fallbackLng: false,
        // English is the key itself: an empty bundle, so it is never downloaded.
        resources: { en: { translation: {} } },
        partialBundledLanguages: true,
        keySeparator: false,
        nsSeparator: false,
        returnEmptyString: false,
        interpolation: {
          escapeValue: false,
          prefix: "{",
          suffix: "}",
        },
        // An unknown placeholder stays as it is, like fill() in app.js.
        missingInterpolationHandler: (_text: string, value: RegExpExecArray) => value[0],
        backend: { loadPath: (lngs: string[]) => `/v1/i18n/${lngs[0]}` },
        react: { useSuspense: false },
      });
  }
  await setLang(lang);
  return i18n;
}

export const currentLang = (): Lang => (isLang(i18n.language) ? i18n.language : "ru");

export async function setLang(code: string) {
  const lang: Lang = isLang(code) ? code : "ru";
  store.set("lang", lang);
  try {
    document.cookie = `lang=${lang}; path=/; max-age=31536000; samesite=lax`;
  } catch {
    /* no cookies: the saved choice is enough */
  }
  document.documentElement.lang = lang;
  if (i18n.isInitialized) await i18n.changeLanguage(lang);
  document.title = t("Boop VPN");
}

/** The translated text with {placeholders} filled (before the texts load: the English one, filled too). */
export function t(en: string, args?: Record<string, unknown>): string {
  return i18n.isInitialized ? i18n.t(en, { ...args, defaultValue: en }) : fill(en, args);
}

/** Plural text: tp(n, "{n} day|{n} days"), the translation has as many forms as the language needs. */
export function tp(n: number, forms: string, args?: Record<string, unknown>): string {
  const translated = (i18n.isInitialized && (i18n.getResource(currentLang(), "translation", forms) as string | undefined)) || forms;
  return pluralForm(currentLang(), n, translated, args);
}

export const date = (iso: string | null | undefined) => formatDate(currentLang(), iso);
export const dateTime = (iso: string | null | undefined) => formatDateTime(currentLang(), iso);
export const money = (minor: number | null | undefined, currency: string) => formatMoney(currentLang(), minor, currency);
export const bytes = (b: number) => formatBytes(currentLang(), b);
export const num = (v: number, digits?: number) => formatNum(currentLang(), v, digits);

/** Re-renders the component when the language or its texts change; returns the helpers. */
export function useI18n() {
  const { i18n: inst } = useTranslation();
  return { t, tp, date, dateTime, money, bytes, num, lang: (isLang(inst.language) ? inst.language : "ru") as Lang };
}
