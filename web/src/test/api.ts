/**
 * Mocks /v1 for tests on the Axios adapter, typed by the same schema.d.ts as the app:
 *
 *   mockApi({ "GET /me": { user, entitlement, usage }, "POST /orders": (req) => ({ ... }) });
 *
 * A route answers with its value (or what its function returns for the request); an Error
 * response is `apiError(status, message)`. Unknown routes fail the test with 404 and the URL.
 * Every request is recorded in the returned list.
 */
import { AxiosError, type AxiosAdapter, type InternalAxiosRequestConfig } from "axios";
import { api } from "@/api/client";
import type { Answer, Method, PathsWith } from "@/api/typed";

type Key<M extends Method> = `${Uppercase<M>} ${PathsWith<M>}`;
type AnswerOf<K> = K extends `${infer U} ${infer P}`
  ? Lowercase<U> extends infer M extends Method
    ? P extends PathsWith<M>
      ? Answer<P, M>
      : never
    : never
  : never;

export interface MockRequest {
  method: string;
  url: string;
  params: Record<string, unknown>;
  query: Record<string, unknown>;
  body: unknown;
  headers: Record<string, unknown>;
}

export type MockRoutes = {
  [K in Key<"get"> | Key<"post"> | Key<"put"> | Key<"patch"> | Key<"delete">]?:
    AnswerOf<K> | ApiErrorReply | ((req: MockRequest) => AnswerOf<K> | ApiErrorReply);
};

export class ApiErrorReply {
  constructor(
    readonly status: number,
    readonly message: string,
    readonly code = "bad_request",
  ) {}
}
export const apiError = (status: number, message: string, code?: string) => new ApiErrorReply(status, message, code);

function matcher(template: string): { re: RegExp; names: string[] } {
  const names: string[] = [];
  const re = template.replace(/[.*+?^$()|[\]\\]/g, "\\$&").replace(/\{(\w+)\}/g, (_, name: string) => {
    names.push(name);
    return "([^/]+)";
  });
  return { re: new RegExp(`^${re}$`), names };
}

export function mockApi(routes: MockRoutes): MockRequest[] {
  const seen: MockRequest[] = [];
  const table = Object.entries(routes).map(([key, value]) => {
    const [method, template] = key.split(" ", 2) as [string, string];
    return { method: method.toLowerCase(), ...matcher(template), value };
  });
  const adapter: AxiosAdapter = async (config: InternalAxiosRequestConfig) => {
    const method = (config.method ?? "get").toLowerCase();
    const url = config.url ?? "";
    const reply = (status: number, data: unknown) => ({ data, status, statusText: "", headers: {}, config, request: {} });
    const fail = (status: number, data: unknown) => Promise.reject(new AxiosError("fail", "ERR", config, {}, reply(status, data)));
    for (const r of table) {
      if (r.method !== method) continue;
      const m = r.re.exec(url);
      if (!m) continue;
      const req: MockRequest = {
        method,
        url,
        params: Object.fromEntries(r.names.map((n, i) => [n, decodeURIComponent(m[i + 1] ?? "")])),
        query: (config.params as Record<string, unknown>) ?? {},
        body: typeof config.data === "string" ? JSON.parse(config.data) : config.data,
        headers: Object.fromEntries(Object.entries(config.headers ?? {})),
      };
      seen.push(req);
      const out = typeof r.value === "function" ? (r.value as (q: MockRequest) => unknown)(req) : r.value;
      if (out instanceof ApiErrorReply) return fail(out.status, { error: { code: out.code, message: out.message } });
      return reply(200, out);
    }
    return fail(404, { error: { code: "not_found", message: `No mock for ${method.toUpperCase()} ${url}` } });
  };
  api.defaults.adapter = adapter;
  return seen;
}

export function resetApi() {
  api.defaults.adapter = undefined;
}
