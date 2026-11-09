/**
 * The account's data from /v1, shared by the website's #/account and the mini app: keys (with the
 * VLESS key), devices, payments, Telegram, support, the invite link, the Windows app; and the
 * changes to devices and keys, with what each change makes stale.
 */
import { queryOptions, type QueryKey } from "@tanstack/react-query";
import { ApiError } from "./client";
import { keys } from "./keys";
import { del, get, patch } from "./typed";

/** The website and the mini app run every protocol, so they ask for the VLESS key too (as app.js does). */
export const SUPPORTED_PROTOCOLS = "amneziawg,wireguard,vless";

export const keysQuery = queryOptions({
  queryKey: keys.account.keys(),
  queryFn: () => get("/keys", { query: { supportedProtocols: SUPPORTED_PROTOCOLS } }),
});

export const devicesQuery = queryOptions({
  queryKey: keys.account.devices(),
  queryFn: () => get("/devices", { query: { supportedProtocols: SUPPORTED_PROTOCOLS } }),
});

export const paymentsQuery = queryOptions({ queryKey: keys.account.payments(), queryFn: () => get("/me/payments") });

/** Not an error when it fails on the website: the Telegram card is simply not shown (tgAccountCard() in telegram.js). */
export const telegramQuery = queryOptions({ queryKey: keys.account.telegram(), queryFn: () => get("/telegram"), retry: false });

/** A failed check counts as "not available", as support.js and tg/app.js do. */
export const supportQuery = queryOptions({
  queryKey: keys.support(),
  queryFn: () => get("/support").catch(() => ({ available: false })),
  retry: false,
});

/** The person's invite link and its numbers (GET /referrals, the apps' summary). */
export const invitesQuery = queryOptions({ queryKey: keys.referrals.mine(), queryFn: () => get("/referrals"), retry: false });

/** Is the Windows app published? /v1/app/desktop is 404 until it is; any failure counts as "no". */
export const desktopQuery = queryOptions({
  queryKey: keys.desktop(),
  queryFn: () =>
    get("/app/desktop").then(
      () => true,
      (e: unknown) => (e instanceof ApiError ? false : Promise.reject(e)),
    ),
  retry: false,
  staleTime: Infinity,
});

/** What a change to devices or keys makes stale: the lists and the device count in /me. */
export const deviceChanges = (token: string | null): QueryKey[] => [keys.account.keys(), keys.account.devices(), keys.me(token)];

/** A device key is the device: renaming goes to /devices/{id}. */
export const renameDevice = (id: string, name: string) => patch("/devices/{id}", { params: { id }, body: { name } });

/** Deleting a device key goes to /keys/{id}, any other device to /devices/{id}. */
export const deleteDevice = (id: string, isKey: boolean) =>
  isKey ? del("/keys/{id}", { params: { id } }) : del("/devices/{id}", { params: { id } });
