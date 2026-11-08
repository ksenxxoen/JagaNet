import { render, screen } from "@testing-library/react";
import { describe, expect, it } from "vitest";
import { Page, PageHeader, Section, Toolbar } from "..";

describe("layout", () => {
  it("a page with its heading, actions, sections and a toolbar", () => {
    render(
      <Page>
        <PageHeader title="My account" sub="a@b.c" actions={<button type="button">Sign out</button>} />
        <Section title="Devices" actions={<button type="button">Add</button>}>
          <Toolbar>
            <input aria-label="Search" />
          </Toolbar>
        </Section>
        <Section>
          <p>No heading</p>
        </Section>
      </Page>,
    );
    expect(screen.getByRole("heading", { level: 1, name: "My account" })).toBeInTheDocument();
    expect(screen.getByText("a@b.c")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Sign out" })).toBeInTheDocument();
    expect(screen.getByRole("heading", { level: 2, name: "Devices" })).toBeInTheDocument();
    expect(screen.getByRole("toolbar")).toContainElement(screen.getByLabelText("Search"));
    expect(screen.getAllByRole("heading")).toHaveLength(2);
  });

  it("narrow pages are narrower", () => {
    const { container } = render(<Page narrow>x</Page>);
    expect(container.firstElementChild!.className).toContain("max-w-[840px]");
  });
});
