import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { useState } from "react";
import { describe, expect, it, vi } from "vitest";
import { Button, CodeInput, codeDigits, Form, FormError, Input, Notice, Select } from "..";

function Code({ onComplete }: { onComplete?: (c: string) => void }) {
  const [v, setV] = useState("");
  return <CodeInput aria-label="Code" value={v} onChange={setV} onComplete={onComplete} />;
}

describe("CodeInput", () => {
  it("keeps six digits from typed or pasted text and tells when it is complete", async () => {
    const done = vi.fn();
    render(<Code onComplete={done} />);
    const input = screen.getByLabelText("Code");
    expect(input).toHaveAttribute("inputmode", "numeric");
    expect(input).toHaveAttribute("autocomplete", "one-time-code");
    const user = userEvent.setup();
    await user.click(input);
    await user.paste("12 34-5");
    expect(input).toHaveValue("12345");
    expect(done).not.toHaveBeenCalled();
    await user.type(input, "6789");
    expect(input).toHaveValue("123456");
    expect(done).toHaveBeenCalledTimes(1);
    expect(done).toHaveBeenCalledWith("123456");
  });

  it("codeDigits", () => {
    expect(codeDigits("a1b2 c3-4.5_6 7")).toBe("123456");
    expect(codeDigits("12", 4)).toBe("12");
  });
});

describe("Form", () => {
  it("submits without reloading, locks the controls while pending and shows the error", async () => {
    const submit = vi.fn();
    const { rerender } = render(
      <Form onSubmit={submit}>
        <input aria-label="Name" />
        <Button type="submit">Save</Button>
      </Form>,
    );
    expect(screen.queryByRole("alert")).not.toBeInTheDocument();
    await userEvent.setup().click(screen.getByRole("button", { name: "Save" }));
    expect(submit).toHaveBeenCalledTimes(1);

    rerender(
      <Form onSubmit={submit} pending error="Wrong code">
        <input aria-label="Name" />
        <Button type="submit">Save</Button>
      </Form>,
    );
    expect(screen.getByRole("button", { name: "Save" })).toBeDisabled();
    expect(screen.getByLabelText("Name")).toBeDisabled();
    expect(screen.getByRole("alert")).toHaveTextContent("Wrong code");
  });

  it("FormError renders nothing without a message", () => {
    const { container } = render(<FormError>{null}</FormError>);
    expect(container).toBeEmptyDOMElement();
  });
});

describe("Notice", () => {
  it("problems are alerts, the rest is status", () => {
    render(
      <>
        <Notice tone="bad">Payments are off</Notice>
        <Notice tone="ok">Signed in</Notice>
      </>,
    );
    expect(screen.getByRole("alert")).toHaveTextContent("Payments are off");
    expect(screen.getByRole("status")).toHaveTextContent("Signed in");
  });
});

describe("Select", () => {
  it("has the surface background, not the read-only grey of text fields, and a chevron", () => {
    render(
      <>
        <Select aria-label="Plan" className="sm:max-w-xs">
          <option>Pro</option>
        </Select>
        <Input aria-label="Name" readOnly />
      </>,
    );
    const select = screen.getByRole("combobox", { name: "Plan" });
    expect(select.className).not.toMatch(/read-only:bg-subtle/);
    expect(select.className).toMatch(/bg-surface/);
    expect(select.parentElement).toHaveClass("sm:max-w-xs");
    expect(select.parentElement!.querySelector("svg")).toHaveAttribute("aria-hidden", "true");
    expect(screen.getByRole("textbox", { name: "Name" }).className).toMatch(/read-only:bg-subtle/);
  });
});
