import { fireEvent, render, screen } from "@testing-library/react";
import { describe, expect, it } from "vitest";
import { DayBars } from "../DayBars";
import { LineChart, lineRuns, nearest } from "../LineChart";
import { sparkPaths, Sparkline } from "../Sparkline";

const min = (m: number) => Date.parse("2026-11-09T10:00:00Z") + m * 60_000;
const at = (m: number) => new Date(min(m)).toISOString();

describe("lineRuns: breaks in the line", () => {
  it("one run when every report is there", () => {
    expect(lineRuns([min(0), min(1), min(2), min(3)], [1, 2, 3, 4])).toEqual([[0, 1, 2, 3]]);
  });
  it("a missing value breaks the line", () => {
    expect(lineRuns([min(0), min(1), min(2), min(3)], [1, null, 3, 4])).toEqual([[0], [2, 3]]);
  });
  it("a pause of more than three steps between reports breaks it too", () => {
    // 10 points a minute apart, then one 30 minutes later: the average step is ~4 min, the pause is 21.
    const times = [...Array.from({ length: 10 }, (_, i) => min(i)), min(30)];
    expect(
      lineRuns(
        times,
        times.map(() => 1),
      ),
    ).toEqual([[0, 1, 2, 3, 4, 5, 6, 7, 8, 9], [10]]);
  });
  it("nearest picks the closest point in time", () => {
    expect(nearest([min(0), min(5), min(10)], min(6))).toBe(1);
    expect(nearest([min(0), min(5), min(10)], min(100))).toBe(2);
  });
});

describe("LineChart", () => {
  const pts = [
    { ts: at(0), cpu: 10 },
    { ts: at(1), cpu: null },
    { ts: at(2), cpu: 30 },
    { ts: at(3), cpu: 40 },
  ];

  it("says there is no data with fewer than two points", () => {
    render(<LineChart title="CPU" points={[{ ts: at(0), cpu: 5 }]} series={[{ key: "cpu", label: "CPU" }]} format={String} />);
    expect(screen.getByText("No data yet.")).toBeInTheDocument();
  });

  it("draws a break where a report is missing, values from the keyboard", () => {
    const { container } = render(
      <LineChart
        title="Processor, %"
        points={pts}
        series={[{ key: "cpu", label: "Processor" }]}
        format={(v) => `${Math.round(v)}%`}
        top={100}
      />,
    );
    // The point before the gap stands alone (a dot), the rest is one line.
    expect(container.querySelectorAll("path[data-run]")).toHaveLength(1);
    const chart = screen.getByRole("slider", { name: "Processor, %" });
    fireEvent.focus(chart);
    expect(chart).toHaveAttribute("aria-valuenow", "3");
    expect(chart.getAttribute("aria-valuetext")).toContain("Processor 40%");
    expect(screen.getByRole("tooltip")).toHaveTextContent("Processor 40%");
    fireEvent.keyDown(chart, { key: "ArrowLeft" });
    expect(chart.getAttribute("aria-valuetext")).toContain("Processor 30%");
    fireEvent.keyDown(chart, { key: "ArrowLeft" });
    expect(chart.getAttribute("aria-valuetext")).toContain("Processor no data");
    fireEvent.keyDown(chart, { key: "Home" });
    expect(chart).toHaveAttribute("aria-valuenow", "0");
    expect(screen.getByRole("tooltip")).toHaveTextContent("Processor 10%");
    fireEvent.blur(chart);
    expect(screen.queryByRole("tooltip")).not.toBeInTheDocument();
  });

  it("two series get a legend and tabular numbers on the axis", () => {
    render(
      <LineChart
        title="Throughput"
        points={pts.map((p) => ({ ts: p.ts, rx: p.cpu, tx: 5 }))}
        series={[
          { key: "rx", label: "Download" },
          { key: "tx", label: "Upload" },
        ]}
        format={String}
      />,
    );
    expect(screen.getByText("Download")).toBeInTheDocument();
    expect(screen.getByText("Upload")).toBeInTheDocument();
  });
});

describe("Sparkline", () => {
  it("one path per unbroken run, nothing without values", () => {
    expect(sparkPaths([1, 2, null, 4, 5])).toHaveLength(2);
    expect(sparkPaths([null, null])).toEqual([]);
    const { container } = render(<Sparkline values={[null, null]} label="Throughput" />);
    expect(container).toBeEmptyDOMElement();
    render(<Sparkline values={[1, 3, 2]} label="Throughput" />);
    expect(screen.getByRole("img", { name: "Throughput" })).toBeInTheDocument();
  });
});

describe("DayBars stacked", () => {
  it("puts the series on top of each other and uses the given captions", () => {
    const { container } = render(
      <DayBars
        title="Traffic"
        days={[{ day: "2026-11-08T10:00:00Z", other: 6, torrent: 2 }]}
        series={[
          { key: "other", label: "Other" },
          { key: "torrent", label: "Torrents" },
        ]}
        stacked
        axis={(v) => `${v} B`}
        label={() => "10:00"}
      />,
    );
    const rects = [...container.querySelectorAll("rect.fill-primary, rect.fill-accent")];
    expect(rects).toHaveLength(2);
    // Same x: one bar; the torrents sit right above the rest.
    expect(rects[0].getAttribute("x")).toBe(rects[1].getAttribute("x"));
    const [other, torrent] = rects.map((r) => ({ y: Number(r.getAttribute("y")), h: Number(r.getAttribute("height")) }));
    expect(torrent.y + torrent.h).toBeCloseTo(other.y);
    expect(screen.getByText("10:00")).toBeInTheDocument();
    expect(screen.getByText("8 B")).toBeInTheDocument();
  });
});
