/**
 * The website's language choice (the mini app uses the account's language and none of this):
 * ?lang= → saved (localStorage "lang") → browser language → ru, and a change is saved and written
 * to the "lang" cookie (the server's pages read the cookie).
 */
import LanguageDetector from "i18next-browser-languagedetector";
import { store } from "@/lib/storage";
import { isLang, type Lang } from "./format";

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

/** Saves the language and writes the "lang" cookie (registered with onLangChange()). */
export function rememberLang(lang: Lang) {
  store.set("lang", lang);
  try {
    document.cookie = `lang=${lang}; path=/; max-age=31536000; samesite=lax`;
  } catch {
    /* no cookies: the saved choice is enough */
  }
}
