/**
 * One Axios client for /v1, like api() in app.js: Bearer token and Accept-Language on every
 * request; 401 forgets the token and opens #/signin; errors carry the server's error.message,
 * otherwise "Something went wrong".
 */
import axios, { AxiosError, type AxiosInstance } from "axios";
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

/** Where a 401 sends the person (replaced in tests). */
export const onUnauthorized = { go: () => void (window.location.hash = "#/signin") };

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
    (e: AxiosError<ErrorBody>) => {
      const status = e.response?.status ?? 0;
      const body = e.response?.data;
      const message = body?.error?.message;
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
