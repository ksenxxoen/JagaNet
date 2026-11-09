import { describe, expect, it } from "vitest";
import { overviewServer, serversOverview } from "@/test/app";
import { cascadeLink, eventText, filterRows, mainLevel, serverRow, statusRows, summary, whyLines } from "../model";

const NOW = Date.parse("2026-11-09T12:00:00Z");

const d = serversOverview({
  servers: [
    overviewServer({ id: "a-ok", displayName: "Alpha" }),
    overviewServer({ id: "b-warn", displayName: "Bravo", health: "warn", issues: [{ code: "cpu", level: "warning", args: { p: "95" } }] }),
    overviewServer({
      id: "c-down",
      displayName: "Charlie",
      health: "down",
      issues: [{ code: "silent", level: "critical", args: { since: "2026-11-09T11:50:00Z" } }],
    }),
    overviewServer({
      id: "d-maint",
      displayName: "Delta",
      health: "off",
      maintenance: true,
      issues: [{ code: "maintenance", level: "info", args: {} }],
    }),
  ],
});

describe("the status list", () => {
  it("puts problems first: not responding, then problems, then working (main server first), then off", () => {
    expect(statusRows(d, NOW).map((r) => r.id)).toEqual(["c-down", "b-warn", "main", "a-ok", "d-maint"]);
  });

  it("writes the state out with a short reason", () => {
    const rows = statusRows(d, NOW);
    const by = (id: string) => rows.find((r) => r.id === id)!;
    expect(by("c-down")).toMatchObject({ status: "Not responding", reason: "No report for 10 min" });
    expect(by("b-warn")).toMatchObject({ status: "Problem", reason: "CPU 95%" });
    expect(by("a-ok")).toMatchObject({ status: "Works", reason: "" });
    expect(by("d-maint")).toMatchObject({ status: "Switched off (maintenance)", live: false });
  });

  it("filters by state and searches by name, id or host", () => {
    const rows = statusRows(d, NOW);
    expect(filterRows(rows, "problem", "").map((r) => r.id)).toEqual(["c-down", "b-warn"]);
    expect(filterRows(rows, "down", "").map((r) => r.id)).toEqual(["c-down"]);
    expect(filterRows(rows, "off", "").map((r) => r.id)).toEqual(["d-maint"]);
    expect(filterRows(rows, "all", "brav").map((r) => r.id)).toEqual(["b-warn"]);
    expect(filterRows(rows, "all", "PANEL.example").map((r) => r.id)).toEqual(["main"]);
    expect(filterRows(rows, "all", "zzz")).toEqual([]);
  });

  it("sums the states up in one line", () => {
    expect(summary(statusRows(d, NOW))).toBe("Total 5, working 2, problems 1, not responding 1, off 1");
    expect(summary(statusRows(serversOverview(), NOW))).toBe("Total 2, working 2, problems 0, not responding 0");
  });

  it("the main server is down when the database or the website fails, a problem for other checks", () => {
    const failing = (key: string, level: "warning" | "critical") =>
      serversOverview({ monitor: { ...serversOverview().monitor, checks: [{ key, level, message: "Fails", args: {} }] } });
    expect(mainLevel(failing("db", "critical"))).toBe("down");
    expect(mainLevel(failing("disk", "critical"))).toBe("warn");
    // The nodes' own check is on the nodes' rows.
    expect(mainLevel(failing("nodes", "critical"))).toBe("ok");
  });

  it("explains the status by the rule that fired", () => {
    const rows = statusRows(d, NOW);
    const why = (id: string) =>
      whyLines(
        rows.find((r) => r.id === id)!,
        d,
        NOW,
      );
    expect(why("b-warn")).toEqual(["CPU 95%: from 90% a node counts as having a problem."]);
    expect(why("c-down")[0]).toBe("No agent report for 10 min. A node without a report for 3 minutes counts as not responding.");
    expect(why("d-maint")[0]).toMatch(/^Marked as maintenance/);
    expect(why("a-ok")).toEqual(["All rules passed: reports arrive, CPU, memory and places are below 90%."]);
    expect(why("main")).toEqual(["All checks of the main server passed."]);
  });

  it("shows how entries and exits of a cascade see each other", () => {
    const exit = overviewServer({ id: "x1", displayName: "Exit 1", cascadeRole: "exit" });
    const entry = overviewServer({ id: "e1", displayName: "Entry 1", cascadeRole: "entry", balancer: { selected: ["x1"] } });
    const c = serversOverview({ balancer: true, servers: [entry, exit] });
    expect(cascadeLink(exit, c)).toEqual({ label: "Connection to entries", value: "Selected by: Entry 1" });
    expect(cascadeLink(entry, c)).toEqual({ label: "Connection to exits", value: "Uses: Exit 1" });
    expect(cascadeLink(exit, { ...c, balancer: false })).toBeNull();
    expect(serverRow(exit, NOW).role).toBe("Exit");
  });

  it("writes the events of the log in words", () => {
    expect(eventText({ ts: "", kind: "online", args: { downMinutes: "75" } })).toBe("Back online after 1 h 15 min");
    expect(eventText({ ts: "", kind: "maintenance_on", args: {} })).toBe("Maintenance on");
  });
});
