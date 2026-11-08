/**
 * Query parameters stay plain strings, exactly as the server, the bot and the e-mails write them
 * (#/account?order=42 keeps "42"), instead of TanStack Router's default JSON encoding.
 */
export const parseSearch = (search: string): Record<string, string> => Object.fromEntries(new URLSearchParams(search.replace(/^\?/, "")));

export const stringifySearch = (search: Record<string, unknown>): string => {
  const q = new URLSearchParams();
  for (const [k, v] of Object.entries(search)) if (v != null && v !== "") q.set(k, String(v));
  const s = q.toString();
  return s ? `?${s}` : "";
};

/** Picks the named string parameters: validateSearch for a route. */
export const pick =
  <K extends string>(...keys: K[]) =>
  (raw: Record<string, unknown>): Partial<Record<K, string>> => {
    const out: Partial<Record<K, string>> = {};
    for (const k of keys) if (typeof raw[k] === "string" && raw[k] !== "") out[k] = raw[k] as string;
    return out;
  };

/** "#pricing", "#faq"…: a section of the landing page, not a route (isAnchor() in app.js). */
export const isAnchor = (hash: string) => /^#[a-z]/.test(hash);
