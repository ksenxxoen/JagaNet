import { QueryClientProvider, useQuery } from "@tanstack/react-query";
import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, describe, expect, it, vi } from "vitest";
import { keys } from "@/api/keys";
import { createQueryClient } from "@/api/queries";
import { get, put } from "@/api/typed";
import { Button, Form } from "@/components/ui";
import { notify } from "@/lib/notify";
import { apiError, mockApi, resetApi } from "@/test/api";
import { siteInfo } from "@/test/app";
import { useApiMutation } from "../useApiMutation";
import { useSaveForm } from "../useSaveForm";

afterEach(() => {
  resetApi();
  vi.restoreAllMocks();
});

const wrap = (ui: React.ReactNode) => {
  const qc = createQueryClient();
  return render(<QueryClientProvider client={qc}>{ui}</QueryClientProvider>);
};

function SiteFlag() {
  const { data } = useQuery({ queryKey: keys.site(), queryFn: () => get("/site") });
  return <p>{data ? (data.testCodes ? "codes on" : "codes off") : "…"}</p>;
}

function Toggle() {
  const m = useApiMutation({
    mutationFn: () => put("/admin/settings/modes", { body: { showSignInCodes: true, testPayments: false } }),
    success: "Saved",
    invalidate: [keys.site()],
  });
  return <Button onClick={() => m.mutate()}>Turn on</Button>;
}

describe("useApiMutation", () => {
  it("toasts success and refreshes the listed queries", async () => {
    let on = false;
    mockApi({
      "GET /site": () => siteInfo({ testCodes: on }),
      "PUT /admin/settings/modes": () => {
        on = true;
        return { ok: true } as never;
      },
    });
    const success = vi.spyOn(notify, "success").mockImplementation(() => "");
    wrap(
      <>
        <SiteFlag />
        <Toggle />
      </>,
    );
    expect(await screen.findByText("codes off")).toBeInTheDocument();
    await userEvent.setup().click(screen.getByRole("button", { name: "Turn on" }));
    expect(await screen.findByText("codes on")).toBeInTheDocument();
    expect(success).toHaveBeenCalledWith("Saved");
  });

  it("toasts the server's message on failure", async () => {
    mockApi({ "PUT /admin/settings/modes": apiError(403, "Only the owner can do this") });
    const error = vi.spyOn(notify, "error").mockImplementation(() => "");
    wrap(<Toggle />);
    await userEvent.setup().click(screen.getByRole("button", { name: "Turn on" }));
    await waitFor(() => expect(error).toHaveBeenCalled());
    expect((error.mock.calls[0]![0] as Error).message).toBe("Only the owner can do this");
  });
});

function Channel({ input }: { input: string }) {
  const form = useSaveForm({
    mutationFn: (body: { channelMbps: number | null; monthlyTrafficGb: number | null }) => put("/admin/settings/network", { body }),
    success: "Saved",
  });
  return (
    <Form
      pending={form.pending}
      error={form.error}
      onSubmit={() =>
        void form.save(() => {
          if (!/^\d*$/.test(input)) throw new Error("Enter a whole number");
          return { channelMbps: input ? Number(input) : null, monthlyTrafficGb: null };
        })
      }
    >
      <Button type="submit">Save</Button>
    </Form>
  );
}

describe("useSaveForm", () => {
  it("a check before sending shows its message and sends nothing", async () => {
    const seen = mockApi({});
    wrap(<Channel input="12x" />);
    await userEvent.setup().click(screen.getByRole("button", { name: "Save" }));
    expect(await screen.findByRole("alert")).toHaveTextContent("Enter a whole number");
    expect(seen).toHaveLength(0);
  });

  it("the server's error under the form, no error toast; success toasts", async () => {
    let fail = true;
    mockApi({ "PUT /admin/settings/network": () => (fail ? apiError(400, "Too fast") : ({ ok: true } as never)) });
    const error = vi.spyOn(notify, "error").mockImplementation(() => "");
    const success = vi.spyOn(notify, "success").mockImplementation(() => "");
    wrap(<Channel input="1000" />);
    const user = userEvent.setup();
    await user.click(screen.getByRole("button", { name: "Save" }));
    expect(await screen.findByRole("alert")).toHaveTextContent("Too fast");
    expect(error).not.toHaveBeenCalled();

    fail = false;
    await user.click(screen.getByRole("button", { name: "Save" }));
    await waitFor(() => expect(success).toHaveBeenCalledWith("Saved"));
    expect(screen.queryByRole("alert")).not.toBeInTheDocument();
  });
});
