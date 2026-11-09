/**
 * A line chart over time in SVG (lineChart() in app.js): one or two series, one y axis with a round
 * top (or a fixed one, 100 for percentages), a quiet grid, time captions (clock times within two
 * days, dates beyond). A slot without a report (a null value) or a pause between two points (more
 * than three average steps) breaks the line instead of drawing across the gap. The values at a moment show on hover,
 * and from the keyboard: focus the chart, then the arrows, Home and End move along the points.
 *
 *   <LineChart title={t("Processor, %")} points={pts} series={[{ key: "cpu", label: t("Processor") }]}
 *              format={(v) => `${Math.round(v)}%`} top={100} />
 */
import { useState, type KeyboardEvent, type MouseEvent } from "react";
import { Card, CardHeader } from "@/components/ui";
import { useI18n } from "@/i18n";
import { clock, formatDateTime, formatShortDate, localDay } from "@/i18n/format";
import { cn } from "@/lib/cn";
import { niceTop } from "./DayBars";
import { ChartLegend } from "./legend";
import { useWidth } from "./useWidth";

export interface LineSeries<K extends string> {
  key: K;
  label: string;
}

export type LinePoint<K extends string> = { ts: string } & { [P in K]?: number | null };

/** Series colors from the theme, as the bars: the first primary, the second accent. */
const STROKE = ["stroke-primary", "stroke-accent"];
const DOT = ["fill-primary", "fill-accent"];

/**
 * The unbroken runs of a series: indexes of the points with a value, split where a value is missing
 * or two points are more than three average steps apart (the agent sent no reports in between).
 */
export function lineRuns(times: number[], values: (number | null | undefined)[]): number[][] {
  const span = times.length > 1 ? times[times.length - 1] - times[0] : 0;
  const step = span / Math.max(1, times.length - 1);
  const runs: number[][] = [];
  let run: number[] = [];
  let prev: number | null = null;
  values.forEach((v, i) => {
    if (v == null) {
      prev = null;
      return;
    }
    if (prev == null || times[i] - times[prev] > step * 3) {
      if (run.length) runs.push(run);
      run = [];
    }
    run.push(i);
    prev = i;
  });
  if (run.length) runs.push(run);
  return runs;
}

/** The index of the point nearest to the time `ms`. */
export function nearest(times: number[], ms: number): number {
  let best = 0;
  times.forEach((t, i) => {
    if (Math.abs(t - ms) < Math.abs(times[best] - ms)) best = i;
  });
  return best;
}

export function LineChart<K extends string>({
  title,
  points,
  series,
  format,
  top: fixedTop,
  small,
  className,
}: {
  title: string;
  /** Oldest first. */
  points: LinePoint<K>[];
  series: LineSeries<K>[];
  /** An axis tick and a value in the tooltip. */
  format: (v: number) => string;
  /** The least axis top (100 for percentages); a round number above the data by default. */
  top?: number;
  /** A low chart, for two side by side. */
  small?: boolean;
  className?: string;
}) {
  const { t, lang } = useI18n();
  const [box, measured] = useWidth();
  const [at, setAt] = useState<number | null>(null);
  // Every point stays on the time axis, so a slot without a report (null) breaks the line.
  const pts = points;

  if (pts.filter((p) => series.some((s) => p[s.key] != null)).length < 2)
    return (
      <Card className={cn("mt-4", className)}>
        <CardHeader title={title} className="mb-0" />
        <p className="mb-0 mt-2 text-muted">{t("No data yet.")}</p>
      </Card>
    );

  const W = Math.max(280, Math.min(1000, measured));
  const H = small ? 120 : W < 600 ? 180 : 220;
  const T = 10,
    B = 26,
    R = 10;
  const max = Math.max(0, ...pts.flatMap((p) => series.map((s) => p[s.key] ?? 0)));
  const top = fixedTop ? Math.max(fixedTop, max) : niceTop(max);
  const ticks = [0, top / 2, top];
  const L = Math.max(30, 10 + Math.max(...ticks.slice(1).map((v) => format(v).length)) * 6.3);
  const times = pts.map((p) => Date.parse(p.ts));
  const t0 = times[0];
  const span = Math.max(1, times[times.length - 1] - t0);
  const x = (ms: number) => L + ((W - L - R) * (ms - t0)) / span;
  const y = (v: number) => T + (H - T - B) * (1 - v / top);
  const byDay = span > 2 * 86_400_000;
  const nLabels = Math.max(2, Math.min(6, Math.floor((W - L) / 90)));
  const labels = Array.from({ length: nLabels }, (_, i) => t0 + (span * i) / (nLabels - 1));
  const when = (ms: number) => {
    const iso = new Date(ms).toISOString();
    return byDay ? formatShortDate(lang, localDay(iso)) : clock(iso);
  };
  const tip = at != null ? pts[at] : undefined;

  const valueText = (i: number) => {
    const p = pts[i]!;
    return `${formatDateTime(lang, p.ts)}, ${series.map((s) => `${s.label} ${p[s.key] == null ? t("no data") : format(p[s.key]!)}`).join(", ")}`;
  };
  const onMove = (e: MouseEvent<HTMLDivElement>) => {
    const r = e.currentTarget.getBoundingClientRect();
    if (!r.width) return;
    const px = ((e.clientX - r.left) / r.width) * W;
    setAt(nearest(times, t0 + ((px - L) / (W - L - R)) * span));
  };
  const onKey = (e: KeyboardEvent<HTMLDivElement>) => {
    const last = pts.length - 1;
    const cur = at ?? last;
    const next = { ArrowLeft: cur - 1, ArrowRight: cur + 1, Home: 0, End: last }[e.key];
    if (next === undefined) return;
    e.preventDefault();
    setAt(Math.min(last, Math.max(0, next)));
  };

  return (
    <Card className={cn("relative mt-4", className)}>
      <CardHeader title={title} actions={series.length > 1 ? <ChartLegend series={series} /> : undefined} />
      {/* A slider over the points: the arrows move along the time, the value text is read out. */}
      <div
        ref={box}
        role="slider"
        aria-label={title}
        aria-valuemin={0}
        aria-valuemax={pts.length - 1}
        aria-valuenow={at ?? pts.length - 1}
        aria-valuetext={valueText(at ?? pts.length - 1)}
        tabIndex={0}
        onKeyDown={onKey}
        onFocus={() => setAt((a) => a ?? pts.length - 1)}
        onBlur={() => setAt(null)}
        onMouseMove={onMove}
        onMouseLeave={() => setAt(null)}
        className="relative rounded-lg"
      >
        <svg viewBox={`0 0 ${W} ${H}`} aria-hidden="true" className="block h-auto w-full" preserveAspectRatio="none">
          {ticks.map((v) => (
            <g key={v}>
              <line x1={L} x2={W - R} y1={y(v)} y2={y(v)} className="stroke-line-soft" />
              <text x={L - 6} y={y(v) + 4} textAnchor="end" className="fill-muted text-[11px] tnum">
                {v ? format(v) : "0"}
              </text>
            </g>
          ))}
          <line x1={L} x2={W - R} y1={y(0)} y2={y(0)} className="stroke-line-strong" />
          {series.map((s, j) =>
            lineRuns(
              times,
              pts.map((p) => p[s.key]),
            ).map((run) =>
              run.length === 1 ? (
                <circle key={`${s.key}-${run[0]}`} cx={x(times[run[0]])} cy={y(pts[run[0]][s.key] ?? 0)} r={2} className={DOT[j]} />
              ) : (
                <path
                  key={`${s.key}-${run[0]}`}
                  d={run.map((i, k) => `${k ? "L" : "M"}${x(times[i]).toFixed(1)} ${y(pts[i][s.key] ?? 0).toFixed(1)}`).join("")}
                  fill="none"
                  strokeWidth={2}
                  strokeLinejoin="round"
                  strokeLinecap="round"
                  className={STROKE[j]}
                  data-run=""
                />
              ),
            ),
          )}
          {labels.map((ms, i) => (
            <text
              key={i}
              x={x(ms)}
              y={H - 8}
              textAnchor={i === 0 ? "start" : i === nLabels - 1 ? "end" : "middle"}
              className="fill-muted text-[11px] tnum"
            >
              {when(ms)}
            </text>
          ))}
          {tip ? (
            <g aria-hidden="true">
              <line x1={x(times[at!])} x2={x(times[at!])} y1={T} y2={H - B} className="stroke-line-strong" strokeDasharray="3 3" />
              {series.map((s, j) =>
                tip[s.key] == null ? null : <circle key={s.key} cx={x(times[at!])} cy={y(tip[s.key]!)} r={4} className={DOT[j]} />,
              )}
            </g>
          ) : null}
        </svg>
        {tip ? (
          <div
            role="tooltip"
            className="pointer-events-none absolute top-0 z-10 rounded-lg border border-line bg-surface px-3 py-2 text-xs shadow tnum"
            style={{ left: `${Math.min(75, Math.max(0, (x(times[at!]) / W) * 100 - 12))}%` }}
          >
            <b className="text-ink-heading">{byDay ? formatDateTime(lang, tip.ts) : clock(tip.ts)}</b>
            {series.map((s) => (
              <div key={s.key}>
                {s.label} {tip[s.key] == null ? t("no data") : format(tip[s.key]!)}
              </div>
            ))}
          </div>
        ) : null}
      </div>
    </Card>
  );
}
