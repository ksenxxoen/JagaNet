import { act, renderHook } from "@testing-library/react";
import { afterEach, describe, expect, it } from "vitest";
import { useVisible } from "../useVisible";

const setVisibility = (state: "visible" | "hidden") => {
  Object.defineProperty(document, "visibilityState", { configurable: true, get: () => state });
  document.dispatchEvent(new Event("visibilitychange", { bubbles: true }));
};

afterEach(() => setVisibility("visible"));

describe("useVisible", () => {
  it("follows the tab being hidden and shown", () => {
    const { result } = renderHook(() => useVisible());
    expect(result.current).toBe(true);
    act(() => setVisibility("hidden"));
    expect(result.current).toBe(false);
    act(() => setVisibility("visible"));
    expect(result.current).toBe(true);
  });
});
