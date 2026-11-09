/**
 * React Query keys by domain, in one place, so a mutation invalidates exactly the queries it
 * changes: `invalidate: [keys.me()]`. A key is a tuple that starts with its domain, so
 * `keys.account.all` covers every account query.
 */
export const keys = {
  /** /v1/me for the given token (another token is another person). */
  me: (token?: string | null) => (token === undefined ? (["me"] as const) : (["me", token] as const)),
  site: () => ["site"] as const,
  plans: () => ["billing", "plans"] as const,
  account: {
    all: ["account"] as const,
    keys: () => ["account", "keys"] as const,
    payments: () => ["account", "payments"] as const,
    telegram: () => ["account", "telegram"] as const,
    order: (id: string) => ["account", "order", id] as const,
  },
  referrals: {
    all: ["referrals"] as const,
    stats: (period: string) => ["referrals", "stats", period] as const,
    admin: (period: string) => ["referrals", "admin", period] as const,
    invite: () => ["referrals", "invite"] as const,
    rewards: (suspicious: boolean) => ["referrals", "rewards", suspicious] as const,
    rewardsAll: ["referrals", "rewards"] as const,
  },
  support: () => ["support"] as const,
  admin: {
    all: ["admin"] as const,
    dashboard: () => ["admin", "dashboard"] as const,
    users: (q: string) => ["admin", "users", q] as const,
    usersAll: ["admin", "users"] as const,
    user: (id: string) => ["admin", "user", id] as const,
    payments: (filter: Record<string, string>) => ["admin", "payments", filter] as const,
    paymentsAll: ["admin", "payments"] as const,
    finance: (period: string) => ["admin", "finance", period] as const,
    audit: (user: string) => ["admin", "audit", user] as const,
    auditAll: ["admin", "audit"] as const,
    /** /admin/settings: plans, e-mail, alerts, modes, the note for everyone. */
    settings: () => ["admin", "settings"] as const,
    tariffs: () => ["admin", "tariffs"] as const,
    nodes: () => ["admin", "nodes"] as const,
    nodeInstall: (id: string) => ["admin", "nodes", "install", id] as const,
    testers: () => ["admin", "testers"] as const,
    failures: (server: string, user: string) => ["admin", "failures", server, user] as const,
    reports: (user: string) => ["admin", "reports", user] as const,
    report: (id: string) => ["admin", "report", id] as const,
  },
  auth: {
    all: ["auth"] as const,
    telegramConfig: () => ["auth", "telegram", "config"] as const,
    appLogin: (id: string) => ["auth", "app", id] as const,
  },
};
