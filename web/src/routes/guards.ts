/** Route guards: sign-in for personal screens, the owner for owner screens. */
import { redirect } from "@tanstack/react-router";
import type { QueryClient } from "@tanstack/react-query";
import { meQuery } from "@/api/queries";
import { store } from "@/lib/storage";
import { useSession, type Role } from "@/store/session";

export interface RouterContext {
  queryClient: QueryClient;
}

/** No token: remember where the person was going and open #/signin. */
export function requireSignIn(href: string) {
  if (useSession.getState().token) return;
  store.set("returnTo", href);
  throw redirect({ to: "/signin" });
}

/** Not the owner: #/account. */
export async function requireOwner(ctx: RouterContext, href: string) {
  requireSignIn(href);
  const token = useSession.getState().token;
  let role: Role | null = useSession.getState().role;
  if (role == null) {
    try {
      role = (await ctx.queryClient.ensureQueryData(meQuery(token))).user.role;
      useSession.getState().setRole(role);
    } catch {
      role = null;
    }
  }
  if (!useSession.getState().token) throw redirect({ to: "/signin" });
  if (role !== "owner") throw redirect({ to: "/account" });
}
