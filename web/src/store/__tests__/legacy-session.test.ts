import { AxiosHeaders } from "axios";
import { afterEach, describe, expect, it } from "vitest";
import { api } from "@/api/client";
import { pickLang } from "@/i18n/site";
import { store } from "@/lib/storage";
import { createSessionStore, installSession, useSession } from "../session";

// W-10: the new website replaces the old one on /. Someone signed in on the old site (it wrote
// localStorage "token", "lang", "ref" as raw strings) opens the new one and is still signed in.
afterEach(() => {
  api.defaults.adapter = undefined;
  localStorage.clear();
  installSession(createSessionStore(store));
  useSession.setState({ token: null, role: null });
});

describe("a session saved by the old website", () => {
  it("is picked up by the new one without signing in again", async () => {
    // Exactly what the old site left behind.
    localStorage.setItem("token", "old-site-token");
    localStorage.setItem("lang", "de");
    localStorage.setItem("ref", "FRIEND");

    // What entries/site.tsx does on start.
    installSession(createSessionStore(store));
    expect(useSession.getState().token).toBe("old-site-token");
    expect(pickLang()).toBe("de");
    expect(store.get("ref")).toBe("FRIEND");

    // The first API call already carries the old token.
    let seen: AxiosHeaders | undefined;
    api.defaults.adapter = async (config) => {
      seen = config.headers as AxiosHeaders;
      return { data: {}, status: 200, statusText: "", headers: {}, config };
    };
    await api.get("/me");
    expect(seen?.get("Authorization")).toBe("Bearer old-site-token");
  });

  it("is written back under the same key, so a rollback to the old website keeps it too", () => {
    installSession(createSessionStore(store));
    useSession.getState().setToken("new-site-token");
    expect(localStorage.getItem("token")).toBe("new-site-token");
    useSession.getState().signOut();
    expect(localStorage.getItem("token")).toBeNull();
  });
});
