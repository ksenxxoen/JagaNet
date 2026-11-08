import { AxiosHeaders, type AxiosAdapter } from "axios";
import { afterEach, describe, expect, it, vi } from "vitest";
import { ApiError, api, onUnauthorized } from "../client";
import { useSession } from "@/store/session";

const reply =
  (status: number, data: unknown): AxiosAdapter =>
  async (config) => {
    const res = { data, status, statusText: "", headers: {}, config, request: {} };
    if (status >= 400) {
      const { AxiosError } = await import("axios");
      throw new AxiosError("fail", "ERR", config, {}, res);
    }
    return res;
  };

afterEach(() => {
  api.defaults.adapter = undefined;
  localStorage.clear();
  useSession.setState({ token: null, role: null });
});

describe("API client", () => {
  it("sends the token and the language", async () => {
    useSession.getState().setToken("tok");
    let seen: AxiosHeaders | undefined;
    api.defaults.adapter = async (config) => {
      seen = config.headers as AxiosHeaders;
      return { data: { ok: true }, status: 200, statusText: "", headers: {}, config };
    };
    await api.get("/me");
    expect(seen?.get("Authorization")).toBe("Bearer tok");
    expect(seen?.get("Accept-Language")).toBeTruthy();
  });

  it("401 forgets the token and opens sign-in", async () => {
    useSession.getState().setToken("old");
    expect(localStorage.getItem("token")).toBe("old");
    const go = vi.spyOn(onUnauthorized, "go").mockImplementation(() => {});
    api.defaults.adapter = reply(401, { error: { code: "unauthorized", message: "Session expired" } });
    await expect(api.get("/me")).rejects.toMatchObject({ status: 401, message: "Session expired" });
    expect(useSession.getState().token).toBeNull();
    expect(localStorage.getItem("token")).toBeNull();
    expect(go).toHaveBeenCalledOnce();
  });

  it("uses error.message from the server, else a generic text", async () => {
    api.defaults.adapter = reply(400, { error: { code: "bad_request", message: "Enter a valid e-mail" } });
    const e = await api.get("/x").catch((x: unknown) => x);
    expect(e).toBeInstanceOf(ApiError);
    expect((e as ApiError).message).toBe("Enter a valid e-mail");
    api.defaults.adapter = reply(500, "oops");
    await expect(api.get("/x")).rejects.toMatchObject({ status: 500, message: "Something went wrong" });
  });
});
