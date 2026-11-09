/**
 * /tg/open (tg/open.js): hands an import link (happ://add/<subscription>, v2raytun://import/…) to
 * the app. The mini app opens this page in the browser because Telegram opens only web links
 * outside. The link comes in the URL fragment, so it never reaches the server or its logs. Only
 * links that pass the same check as the website's buttons (isImportLink in lib/links.ts) are opened.
 */
import { isImportLink } from "@/lib/links";

/** The link from the fragment, or null when it is not one we open. */
export function importLinkFrom(hash: string): string | null {
  let link: string;
  try {
    link = decodeURIComponent(hash.replace(/^#/, ""));
  } catch {
    return null;
  }
  return isImportLink(link) ? link : null;
}

export function openImportLink(doc: Document = document, loc: Location = window.location) {
  const link = importLinkFrom(loc.hash);
  const go = doc.getElementById("go") as HTMLAnchorElement | null;
  if (!link || !go) {
    const bad = doc.getElementById("bad");
    if (bad) bad.hidden = false;
    return null;
  }
  // Out of the address bar: the subscription link is a secret.
  try {
    history.replaceState(null, "", loc.pathname);
  } catch {
    /* keeps the fragment, which never leaves the browser anyway */
  }
  go.href = link;
  go.hidden = false;
  loc.href = link;
  return link;
}
