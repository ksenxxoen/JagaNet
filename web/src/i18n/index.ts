/**
 * Texts: the key is the English text, translations come from /v1/i18n/<lang> (built from
 * shared/.../i18n/strings), English needs no download. Placeholders are {name} as in the old site,
 * plurals are "a|b|c" strings picked by tp() with the ru/de/en rules of the old site.
 *
 * The website picks the language with pickLang() from i18n/site.ts (?lang= → saved → browser → ru)
 * and remembers a change there (localStorage "lang" and the "lang" cookie), with a listener
 * registered with onLangChange(). The mini app passes the account's language and remembers nothing.
 * This module itself touches no web storage, so the mini app's bundle has none.
 */
import i18next, { type i18n as I18n } from "i18next";
import HttpBackend from "i18next-http-backend";
import { initReactI18next, useTranslation } from "react-i18next";
import {
  fill,
  formatBits,
  formatBytes,
  formatDate,
  formatDateTime,
  formatMoney,
  formatNum,
  formatPercent,
  formatWhen,
  isLang,
  pluralForm,
  type Lang,
} from "./format";

export { LANGS, localDay, type Lang } from "./format";

export const i18n: I18n = i18next.createInstance();

/** Called with every language set (the website saves it); nothing by default. */
let remember: (lang: Lang) => void = () => {};
export function onLangChange(fn: (lang: Lang) => void) {
  remember = fn;
}

/** The website names the tab "Boop VPN" in the language; the mini app keeps its page's title. */
let setsTitle = true;

export async function initI18n(lang: Lang, { title = true }: { title?: boolean } = {}) {
  setsTitle = title;
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
        // An unknown placeholder stays as it is.
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
  remember(lang);
  document.documentElement.lang = lang;
  if (i18n.isInitialized) await i18n.changeLanguage(lang);
  if (setsTitle) document.title = t("Boop VPN");
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
export const bits = (bps: number | null | undefined) => formatBits(currentLang(), bps);
export const percent = (v: number | null | undefined) => formatPercent(currentLang(), v);
export const when = (iso: string) => formatWhen(currentLang(), iso);

/** Re-renders the component when the language or its texts change; returns the helpers. */
export function useI18n() {
  const { i18n: inst } = useTranslation();
  return { t, tp, date, dateTime, money, bytes, num, bits, percent, when, lang: (isLang(inst.language) ? inst.language : "ru") as Lang };
}
