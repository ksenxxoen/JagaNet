/**
 * What happens after a sign-in on the website (by e-mail code, link or Telegram): the token is kept, a plan picked before signing in goes to payment,
 * otherwise the screen the person was going to ("returnTo") or the account opens.
 */
import { startCheckout } from "@/lib/checkout";
import { notify } from "@/lib/notify";
import { store } from "@/lib/storage";
import { useSession } from "@/store/session";

/** Where to go after signing in: a remembered "#/…" address of this site, else the account. */
export function nextAfterSignIn(): string {
  const back = store.get("returnTo");
  store.set("returnTo", null);
  // Only an address inside the website (as the guards save it), never another site.
  return back && /^#\/[^/\\]/.test(back) ? back.slice(1) : "/account";
}

/** The referral code to send with a sign-up: ?ref= of the sign-in address, of the page, or the saved one. */
export function referralCode(fromRoute?: string): string | null {
  return fromRoute || new URLSearchParams(window.location.search).get("ref") || store.get("ref");
}

/** Keeps the session and moves on: to payment of a remembered plan, else `go(next address)`. */
export async function finishSignIn(token: string, go: (href: string) => unknown) {
  useSession.getState().setToken(token);
  const plan = store.get("afterSignIn");
  store.set("afterSignIn", null);
  if (plan) {
    try {
      await startCheckout(plan);
      return;
    } catch (e) {
      notify.error(e);
    }
  }
  await go(nextAfterSignIn());
}
