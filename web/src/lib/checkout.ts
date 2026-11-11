/**
 * Buying a plan: without a session the plan is remembered ("afterSignIn") and
 * sign-in opens; after signing in the order is created and the payment page opens in the
 * person's language. Shared by the landing page, sign-in and (W-4) the account.
 */
import { post } from "@/api/typed";
import { currentLang } from "@/i18n";
import { store } from "@/lib/storage";
import { useSession } from "@/store/session";
import { page } from "./navigate";

/** The payment page with ?lang= (or &lang=) added. */
export const checkoutUrl = (url: string, lang: string) => url + (url.includes("?") ? "&" : "?") + "lang=" + lang;

/** Returns "signin" when the person has to sign in first (the caller opens #/signin), else opens the payment page. */
export async function startCheckout(tariffId: string): Promise<"signin" | "paying"> {
  if (!useSession.getState().token) {
    store.set("afterSignIn", tariffId);
    return "signin";
  }
  const order = await post("/orders", { body: { tariffId } });
  // No payment page (the order is not pending any more): the account shows the order.
  page.open(order.checkoutUrl ? checkoutUrl(order.checkoutUrl, currentLang()) : `#/account?order=${encodeURIComponent(order.id)}`);
  return "paying";
}
