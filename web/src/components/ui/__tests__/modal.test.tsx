import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { useState } from "react";
import { describe, expect, it } from "vitest";
import { useConfirm } from "@/hooks/useConfirm";

function Asker() {
  const { confirm, dialog } = useConfirm();
  const [answer, setAnswer] = useState("none");
  return (
    <>
      <button
        type="button"
        onClick={() => void confirm({ title: "Delete it?", confirm: "Delete", danger: true }).then((yes) => setAnswer(String(yes)))}
      >
        ask
      </button>
      <p>answer: {answer}</p>
      {dialog}
    </>
  );
}

describe("ConfirmDialog / useConfirm", () => {
  it("resolves true on the action, focusing the safe button first", async () => {
    const user = userEvent.setup();
    render(<Asker />);
    await user.click(screen.getByRole("button", { name: "ask" }));
    const dialog = screen.getByRole("dialog", { name: "Delete it?" });
    expect(dialog).toHaveAttribute("aria-modal", "true");
    expect(screen.getByRole("button", { name: "Cancel" })).toHaveFocus();
    await user.click(screen.getByRole("button", { name: "Delete" }));
    expect(screen.getByText("answer: true")).toBeInTheDocument();
    expect(screen.queryByRole("dialog")).toBeNull();
  });

  it("Esc says no and gives the focus back", async () => {
    const user = userEvent.setup();
    render(<Asker />);
    const ask = screen.getByRole("button", { name: "ask" });
    await user.click(ask);
    await user.keyboard("{Escape}");
    expect(screen.getByText("answer: false")).toBeInTheDocument();
    await waitFor(() => expect(ask).toHaveFocus());
  });

  it("Tab stays inside the dialog", async () => {
    const user = userEvent.setup();
    render(<Asker />);
    await user.click(screen.getByRole("button", { name: "ask" }));
    await user.tab();
    expect(screen.getByRole("button", { name: "Delete" })).toHaveFocus();
    await user.tab();
    expect(screen.getByRole("button", { name: "Cancel" })).toHaveFocus();
  });
});
