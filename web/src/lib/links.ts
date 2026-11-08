/**
 * Import links that hand the VLESS subscription to an app in one tap (tg/app.js: connect()), and the
 * check /tg/open uses before it opens one (ALLOWED in tg/open.js). Shared by the account, the mini
 * app and /tg/open, so the list of apps and the check are in one place.
 */
export const HAPP_URL = "https://www.happ.su/main/";
export const V2RAYTUN_URL = "https://v2raytun.com/";

export interface ImportApp {
  name: string;
  /** Where to get the app. */
  site: string;
  /** The import link for a subscription URL. */
  link: (subscriptionUrl: string) => string;
}

export const IMPORT_APPS: readonly ImportApp[] = [
  { name: "Happ", site: HAPP_URL, link: (u) => `happ://add/${u}` },
  { name: "v2RayTun", site: V2RAYTUN_URL, link: (u) => `v2raytun://import/${u}` },
];

/** Only these app links with a web subscription URL, nothing that could run or break out of an attribute. */
export const ALLOWED_IMPORT = /^(happ:\/\/add\/|v2raytun:\/\/import\/)https?:\/\/[^\s"'<>]+$/;
export const isImportLink = (link: string) => ALLOWED_IMPORT.test(link);

/** A link with ?lang= (or &lang=) for pages the server renders in the person's language. */
export const withLang = (url: string, lang: string) => url + (url.includes("?") ? "&" : "?") + "lang=" + lang;

/**
 * Location names of a VLESS key: every link ends with "#Boop <location>" (Vless.links on the server).
 * Duplicates (one location with several transports) are shown once.
 */
export function vlessLocations(links: readonly string[]): string[] {
  const out: string[] = [];
  for (const l of links) {
    const i = l.indexOf("#");
    if (i < 0) continue;
    let name: string;
    try {
      name = decodeURIComponent(l.slice(i + 1));
    } catch {
      name = l.slice(i + 1);
    }
    name = name.replace(/^Boop\s+/, "").trim();
    if (name && !out.includes(name)) out.push(name);
  }
  return out;
}
