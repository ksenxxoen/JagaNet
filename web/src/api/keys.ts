/**
 * React Query keys by domain, in one place, so a mutation invalidates exactly the queries it
 * changes: `invalidate: [keys.me()]`. A key is a tuple that starts with its domain, so
 * `keys.account.all` covers every account query (W-4 adds them).
 */
export const keys = {
  /** /v1/me for the given token (another token is another person). */
  me: (token?: string | null) => (token === undefined ? (["me"] as const) : (["me", token] as const)),
  site: () => ["site"] as const,
  plans: () => ["billing", "plans"] as const,
  auth: {
    all: ["auth"] as const,
    telegramConfig: () => ["auth", "telegram", "config"] as const,
    appLogin: (id: string) => ["auth", "app", id] as const,
  },
};
