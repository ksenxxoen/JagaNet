/**
 * A tiny line without axes for a row of a list (the throughput of a server over the chosen range):
 * one color, a missing value breaks the line. Purely a picture of the trend, the numbers are in the
 * row next to it; `label` names it for screen readers.
 */
import { cn } from "@/lib/cn";
import { lineRuns } from "./LineChart";

const W = 100,
  H = 24;

/** The line's paths in a 100 × 24 box, one per unbroken run; empty when there is nothing to draw. */
export function sparkPaths(values: (number | null | undefined)[]): string[] {
  const n = values.length;
  const max = Math.max(0, ...values.map((v) => v ?? 0));
  const x = (i: number) => (n > 1 ? (W * i) / (n - 1) : W / 2);
  const y = (v: number) => (max > 0 ? H - 2 - ((H - 4) * v) / max : H - 2);
  // Evenly spaced slots: only a missing value breaks the line.
  return lineRuns(
    values.map((_, i) => i),
    values,
  ).map((run) =>
    (run.length === 1 ? [run[0], run[0]] : run).map((i, k) => `${k ? "L" : "M"}${x(i).toFixed(1)} ${y(values[i]!).toFixed(1)}`).join(""),
  );
}

export function Sparkline({ values, label, className }: { values: (number | null | undefined)[]; label: string; className?: string }) {
  const paths = sparkPaths(values);
  if (!paths.length) return null;
  return (
    <svg viewBox={`0 0 ${W} ${H}`} role="img" aria-label={label} preserveAspectRatio="none" className={cn("block h-6 w-24", className)}>
      {paths.map((d, i) => (
        <path
          key={i}
          d={d}
          fill="none"
          strokeWidth={1.5}
          strokeLinejoin="round"
          strokeLinecap="round"
          vectorEffect="non-scaling-stroke"
          className="stroke-primary"
        />
      ))}
    </svg>
  );
}
