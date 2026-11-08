import { QueryClientProvider } from "@tanstack/react-query";
import { act, render, screen } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { createQueryClient } from "@/api/queries";
import { copyText } from "../useCopy";
import { pollInterval, usePolling } from "../usePolling";

afterEach(() => {
  vi.restoreAllMocks();
  vi.unstubAllGlobals();
  vi.useRealTimers();
});

describe("pollInterval", () => {
  const until = (s: string) => s === "paid";
  it("asks again until the answer is final", () => {
    expect(pollInterval(1000, until)({ state: { data: undefined } })).toBe(1000);
    expect(pollInterval(1000, until)({ state: { data: "pending" } })).toBe(1000);
    expect(pollInterval(1000, until)({ state: { data: "paid" } })).toBe(false);
  });
});

function Poll({ fn }: { fn: () => Promise<string> }) {
  const q = usePolling({ queryKey: ["poll"], queryFn: fn, interval: 1000, until: (s) => s === "done" });
  return <p>{q.data ?? "…"}</p>;
}

describe("usePolling", () => {
  it("stops once the answer is final", async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true });
    const answers = ["wait", "done"];
    const fn = vi.fn(() => Promise.resolve(answers.shift() ?? "done"));
    render(
      <QueryClientProvider client={createQueryClient()}>
        <Poll fn={fn} />
      </QueryClientProvider>,
    );
    expect(await screen.findByText("wait")).toBeInTheDocument();
    await act(() => vi.advanceTimersByTimeAsync(1100));
    expect(await screen.findByText("done")).toBeInTheDocument();
    await act(() => vi.advanceTimersByTimeAsync(5000));
    expect(fn).toHaveBeenCalledTimes(2);
  });
});

describe("copyText", () => {
  it("uses the clipboard when there is one", async () => {
    const writeText = vi.fn(() => Promise.resolve());
    vi.stubGlobal("navigator", { clipboard: { writeText } });
    expect(await copyText("x")).toBe(true);
    expect(writeText).toHaveBeenCalledWith("x");
  });
  it("falls back to execCommand, then to the prompt", async () => {
    vi.stubGlobal("navigator", {});
    const exec = vi.fn(() => true);
    Object.defineProperty(document, "execCommand", { value: exec, configurable: true });
    expect(await copyText("y")).toBe(true);
    exec.mockReturnValue(false);
    const prompt = vi.spyOn(window, "prompt").mockReturnValue(null);
    expect(await copyText("z")).toBe(false);
    expect(prompt).toHaveBeenCalledWith("", "z");
  });
});
