import { afterEach, describe, expect, it } from "vitest";
import { del, fillPath, get, post, put } from "../typed";
import { apiError, mockApi, resetApi } from "@/test/api";
import type { Schema } from "../typed";
import { useSession } from "@/store/session";

const site: Schema<"SiteInfo"> = {
  testPayments: false,
  paymentsEnabled: true,
  emailSignIn: true,
  testCodes: false,
  referralsPublic: false,
  inviteRewardDays: 0,
};

afterEach(() => {
  resetApi();
  localStorage.clear();
  useSession.setState({ token: null, role: null });
});

describe("typed API", () => {
  it("fills path parameters and refuses a missing one", () => {
    expect(fillPath("/orders/{id}", { id: "a b/c" })).toBe("/orders/a%20b%2Fc");
    expect(fillPath("/admin/users/{id}/devices/{device}", { id: "u1", device: "d1" })).toBe("/admin/users/u1/devices/d1");
    expect(() => fillPath("/orders/{id}", {})).toThrow(/id/);
  });

  it("gets with the answer's type and the session token", async () => {
    useSession.getState().setToken("tok");
    const seen = mockApi({ "GET /site": site });
    const s = await get("/site");
    expect(s.paymentsEnabled).toBe(true);
    expect(seen[0]!.headers["Authorization"]).toBe("Bearer tok");
  });

  it("sends the body, the query and path parameters", async () => {
    const seen = mockApi({
      "POST /orders": (req) =>
        ({ id: "o1", status: "pending", amount: 100, currency: "RUB", tariffId: (req.body as { tariffId: string }).tariffId }) as never,
      "GET /orders/{id}": (req) => ({ id: req.params.id }) as never,
      "GET /admin/payments": { rows: [] } as never,
      "PUT /me/lang": { ok: true },
      "DELETE /devices/{id}": { ok: true },
    });
    await post("/orders", { body: { tariffId: "t1" } });
    expect(seen[0]!.body).toEqual({ tariffId: "t1" });
    expect(await get("/orders/{id}", { params: { id: "o 1" } })).toEqual({ id: "o 1" });
    expect(seen[1]!.url).toBe("/orders/o%201");
    await get("/admin/payments", { query: { status: "paid", from: "2026-10-01" } });
    expect(seen[2]!.query).toEqual({ status: "paid", from: "2026-10-01" });
    await put("/me/lang", { body: { lang: "de" } });
    await del("/devices/{id}", { params: { id: "d1" } });
    expect(seen.map((r) => `${r.method} ${r.url}`)).toEqual([
      "post /orders",
      "get /orders/o%201",
      "get /admin/payments",
      "put /me/lang",
      "delete /devices/d1",
    ]);
  });

  it("server errors keep their message", async () => {
    mockApi({ "POST /auth/email/start": apiError(400, "Invalid email") });
    await expect(post("/auth/email/start", { body: { email: "x" } })).rejects.toMatchObject({ status: 400, message: "Invalid email" });
  });

  it("types follow the spec", () => {
    // Compile-time checks: a wrong path, a missing body or path parameter does not type-check.
    // @ts-expect-error no such endpoint
    void (() => get("/no-such-endpoint"));
    // @ts-expect-error POST /orders needs a body
    void (() => post("/orders"));
    // @ts-expect-error GET /orders/{id} needs the id
    void (() => get("/orders/{id}"));
    // @ts-expect-error the body has the wrong shape
    void (() => post("/orders", { body: { tariff: "x" } }));
    expect(true).toBe(true);
  });
});
