/**
 * One Axios client for /v1, like api() in app.js: Bearer token and Accept-Language on every
 * request; 401 forgets the token and opens #/signin; errors carry the server's error.message,
 * otherwise "Something went wrong". The token comes from the session store, so the mini app's
 * stays in memory. The mini app sets onUnauthorized.renew: an ended session (12 hours) signs in
 * again once with the same initData and the request is repeated (api() in tg/app.js).
 */
import axios, { AxiosError, type AxiosInstance, type InternalAxiosRequestConfig } from "axios";
import { currentLang, t } from "@/i18n";
import { useSession } from "@/store/session";

export class ApiError extends Error {
  constructor(
    message: string,
    readonly status: number,
    readonly code?: string,
  ) {
    super(message);
    this.name = "ApiError";
  }
}

interface ErrorBody {
  error?: { code?: string; message?: string };
}

/**
 * What a 401 does. `renew` (the mini app) gets a new token and says whether it did; then the request
 * is sent once more. Otherwise, or when that fails too, the token is forgotten and `go` runs
 * (the website opens #/signin; replaced in tests).
 */
export const onUnauthorized: { go: () => void; renew?: () => Promise<boolean> } = {
  go: () => void (window.location.hash = "#/signin"),
};

type Retried = InternalAxiosRequestConfig & { _renewed?: boolean };

export function createApi(): AxiosInstance {
  const api = axios.create({ baseURL: "/v1", headers: { "Content-Type": "application/json" } });
  api.interceptors.request.use((config) => {
    const token = useSession.getState().token;
    config.headers.set("Accept-Language", currentLang());
    if (token) config.headers.set("Authorization", `Bearer ${token}`);
    return config;
  });
  api.interceptors.response.use(
    (r) => r,
    async (e: AxiosError<ErrorBody>) => {
      const status = e.response?.status ?? 0;
      const body = e.response?.data;
      const message = body?.error?.message;
      const config = e.config as Retried | undefined;
      // Sign-in itself (/auth/…) is never renewed: its 401 is the answer.
      if (status === 401 && onUnauthorized.renew && config && !config._renewed && !config.url?.startsWith("/auth/")) {
        config._renewed = true;
        let renewed = false;
        try {
          renewed = await onUnauthorized.renew();
        } catch {
          renewed = false;
        }
        if (renewed) return api.request(config);
      }
      if (status === 401) {
        useSession.getState().signOut();
        onUnauthorized.go();
        return Promise.reject(new ApiError(message || t("Please sign in again"), 401, body?.error?.code));
      }
      return Promise.reject(new ApiError(message || t("Something went wrong"), status, body?.error?.code));
    },
  );
  return api;
}

export const api = createApi();
