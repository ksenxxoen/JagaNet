/**
 * localStorage under the same keys as the old website (token, lang, ref, afterSignIn, returnTo,
 * monRange, anPeriod, refPeriod, finPeriod…), so switching sites keeps people signed in.
 * Like `store` in app.js: every access is guarded (private mode, blocked storage).
 */
export const store = {
  get(key: string): string | null {
    try {
      return localStorage.getItem(key);
    } catch {
      return null;
    }
  },
  set(key: string, value: string | null | undefined) {
    try {
      if (value == null) localStorage.removeItem(key);
      else localStorage.setItem(key, value);
    } catch {
      /* storage blocked: keep going without it */
    }
  },
};
