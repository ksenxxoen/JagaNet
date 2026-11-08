/**
 * Typed calls to /v1: the path and the method pick the types of the path and query parameters,
 * the body and the answer from schema.d.ts (generated from server/src/main/resources/openapi.yaml
 * by `npm run gen:api`). They go through the same Axios client (Bearer, Accept-Language, 401).
 *
 *   const me = await get("/me");                                  // Schema<"MeRes">
 *   await post("/orders", { body: { tariffId } });                // Schema<"OrderRes">
 *   await get("/orders/{id}", { params: { id } });
 *   await get("/admin/payments", { query: { status: "paid" } });
 */
import type { components, paths } from "./schema";
import { api } from "./client";

/** A model of the API by its name in openapi.yaml: Schema<"MeRes">, Schema<"SiteInfo">. */
export type Schema<N extends keyof components["schemas"]> = components["schemas"][N];

export type Method = "get" | "post" | "put" | "patch" | "delete";

/** The /v1 paths without the prefix, as the client's baseURL already has it. */
type V1 = { [P in keyof paths as P extends `/v1${infer R}` ? R : never]: paths[P] };

type Op<P extends keyof V1, M extends Method> = NonNullable<V1[P][M]>;

/** The paths that have method M. */
export type PathsWith<M extends Method> = { [P in keyof V1]: V1[P][M] extends undefined | never ? never : P }[keyof V1];

type Content<C> = C extends { content: infer X }
  ? X extends { "application/json": infer J }
    ? J
    : X extends { "text/csv": infer T }
      ? T
      : never
  : never;

export type Answer<P extends keyof V1, M extends Method> = Op<P, M> extends { responses: { 200: infer R } } ? Content<R> : never;
export type Body<P extends keyof V1, M extends Method> = Op<P, M> extends { requestBody: infer B } ? Content<NonNullable<B>> : never;
export type Query<P extends keyof V1, M extends Method> = Op<P, M> extends { parameters: { query?: infer Q } } ? Q : never;
export type PathParams<P extends keyof V1, M extends Method> = Op<P, M> extends { parameters: { path: infer X } } ? X : never;

type IsNever<T> = [T] extends [never] ? true : false;

export type Init<P extends keyof V1, M extends Method> = (IsNever<Body<P, M>> extends true ? { body?: undefined } : { body: Body<P, M> }) &
  (IsNever<PathParams<P, M>> extends true ? { params?: undefined } : { params: PathParams<P, M> }) & {
    query?: IsNever<Query<P, M>> extends true ? undefined : Query<P, M>;
    signal?: AbortSignal;
  };

/** The init argument is optional only when the operation needs neither a body nor path parameters. */
type InitArg<P extends keyof V1, M extends Method> =
  IsNever<Body<P, M>> extends true
    ? IsNever<PathParams<P, M>> extends true
      ? [init?: Init<P, M>]
      : [init: Init<P, M>]
    : [init: Init<P, M>];

/** "/orders/{id}" with { id: "a b" } → "/orders/a%20b". A missing parameter is an error, not "undefined" in the URL. */
export function fillPath(template: string, params?: Record<string, unknown>): string {
  return template.replace(/\{(\w+)\}/g, (_, name: string) => {
    const v = params?.[name];
    if (v === undefined || v === null || v === "") throw new Error(`Missing path parameter ${name} for ${template}`);
    return encodeURIComponent(String(v));
  });
}

export async function call<M extends Method, P extends PathsWith<M>>(method: M, path: P, init?: Init<P, M>): Promise<Answer<P, M>> {
  const res = await api.request<Answer<P, M>>({
    method,
    url: fillPath(path, init?.params as Record<string, unknown> | undefined),
    params: init?.query,
    data: init?.body,
    signal: init?.signal,
  });
  return res.data;
}

export const get = <P extends PathsWith<"get">>(path: P, ...[init]: InitArg<P, "get">) => call("get", path, init);
export const post = <P extends PathsWith<"post">>(path: P, ...[init]: InitArg<P, "post">) => call("post", path, init);
export const put = <P extends PathsWith<"put">>(path: P, ...[init]: InitArg<P, "put">) => call("put", path, init);
export const patch = <P extends PathsWith<"patch">>(path: P, ...[init]: InitArg<P, "patch">) => call("patch", path, init);
export const del = <P extends PathsWith<"delete">>(path: P, ...[init]: InitArg<P, "delete">) => call("delete", path, init);
