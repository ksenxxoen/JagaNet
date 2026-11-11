/**
 * Landing page anchors (#features, #how, #pricing, #faq, #download): they are sections, not routes. On the landing page a link to one
 * only scrolls there (smoothly, unless the person prefers reduced motion); from another screen the
 * link opens the landing page (the router's not-found route) and it scrolls there once drawn.
 */
/** "#pricing", "#faq"…: a section of the landing page, not a route ("#/account" is a route). */
export const isAnchor = (hash: string) => /^#[a-z]/.test(hash);

const reducedMotion = () => typeof matchMedia === "function" && matchMedia("(prefers-reduced-motion: reduce)").matches;

/** The section of the landing page for "#pricing", if the landing page is on screen. */
export function anchorTarget(hash: string): HTMLElement | null {
  if (!isAnchor(hash)) return null;
  const el = document.getElementById(hash.slice(1));
  return el && el.closest("[data-page=home]") ? el : null;
}

/** Scrolls to the section; false when it is not on screen. */
export function scrollToAnchor(hash: string, smooth: boolean): boolean {
  const el = anchorTarget(hash);
  if (!el) return false;
  el.scrollIntoView({ behavior: smooth && !reducedMotion() ? "smooth" : "auto" });
  return true;
}

/**
 * A click on an anchor link while the landing page is shown: scroll instead of navigating, and
 * put the anchor into the address (without a new history entry or a new render), so a reload
 * comes back to the same section. Returns true when it handled the click.
 */
export function handleAnchorClick(e: MouseEvent): boolean {
  if (e.defaultPrevented || e.button !== 0 || e.metaKey || e.ctrlKey || e.shiftKey || e.altKey) return false;
  const a = (e.target as Element | null)?.closest?.("a[href]");
  const href = a?.getAttribute("href") ?? "";
  if (!scrollToAnchor(href, true)) return false;
  e.preventDefault();
  try {
    history.replaceState(history.state, "", href);
  } catch {
    /* the address stays as it is */
  }
  return true;
}
