/** The account's data from /v1: keys (with the VLESS key), payments, Telegram, an order. */
import { queryOptions } from "@tanstack/react-query";
import { keys } from "@/api/keys";
import { get } from "@/api/typed";

/** The website runs every protocol, so it asks for the VLESS key too (as app.js does). */
export const SUPPORTED_PROTOCOLS = "amneziawg,wireguard,vless";

export const keysQuery = queryOptions({
  queryKey: keys.account.keys(),
  queryFn: () => get("/keys", { query: { supportedProtocols: SUPPORTED_PROTOCOLS } }),
});

export const paymentsQuery = queryOptions({ queryKey: keys.account.payments(), queryFn: () => get("/me/payments") });

/** Not an error when it fails: the Telegram card is simply not shown (tgAccountCard() in telegram.js). */
export const telegramQuery = queryOptions({ queryKey: keys.account.telegram(), queryFn: () => get("/telegram"), retry: false });
