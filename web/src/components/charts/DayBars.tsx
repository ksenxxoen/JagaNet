/**
 * Bars in SVG: one series as plain bars, two as grouped
 * bars with a legend, or stacked (`stacked`: BitTorrent on top of the rest in the analytics). One
 * axis with a round top, a quiet grid, a tooltip with the bar's values on hover or focus. Drawn at
 * the real width, so the labels keep their size on phones. Bars are by day by default; `label` and
 * `heading` give other buckets (hours) their own captions. Lines over time: LineChart.
 */
import { useState } from "react";
import { Card, CardHeader } from "@/components/ui";
import { useI18n } from "@/i18n";
import { formatShortDate } from "@/i18n/format";
import { cn } from "@/lib/cn";
import { ChartLegend } from "./legend";
import { useWidth } from "./useWidth";

export interface BarSeries<K extends string> {
  key: K;
  label: string;
}

/** Series colors from the theme: the first primary, the second accent. */
const FILL = ["fill-primary", "fill-accent"];

/** The axis top: a round number ≥ max whose half is a whole number too. */
export function niceTop(max: number): number {
  const m = Math.max(1, max);
  const mag = Math.pow(10, Math.floor(Math.log10(m)));
  return Math.max(
    2,
    [1, 1.2, 1.5, 2, 2.5, 3, 4, 5, 6, 8, 10].map((k) => k * mag).find((v) => v >= m && Number.isInteger(v / 2)) ?? 10 * mag,
  );
}

export function DayBars<K extends string, D extends { day: string } & Record<K, number>>({
  title,
  days,
  series,
  format = String,
  axis = (v: number) => String(Math.round(v)),
  stacked = false,
  label,
  heading,
  className,
}: {
  title: string;
  days: D[];
  series: BarSeries<K>[];
  /** A value in the tooltip (money on the finance tab); the number itself by default. */
  format?: (v: number) => string;
  /** An axis tick (bytes in the analytics); the rounded number by default. */
  axis?: (v: number) => string;
  /** One bar a row with the series on top of each other, the first at the bottom. */
  stacked?: boolean;
  /** The caption under a bar; the short date of `day` by default. */
  label?: (d: D) => string;
  /** The tooltip's first line; the date of `day` by default. */
  heading?: (d: D) => string;
  className?: string;
}) {
  const { date, lang } = useI18n();
  const [box, measured] = useWidth();
  const [hover, setHover] = useState<number | null>(null);
  const W = Math.max(300, Math.min(1000, measured));
  const H = W < 600 ? 180 : 220;
  const R = 8,
    T = 10,
    B = 26;
  const height = (d: D) => (stacked ? series.reduce((a, s) => a + d[s.key], 0) : Math.max(0, ...series.map((s) => d[s.key])));
  const top = niceTop(Math.max(0, ...days.map(height)));
  const L = Math.max(34, 10 + axis(top).length * 6.5);
  const n = Math.max(1, days.length);
  const cw = (W - L - R) / n;
  const groups = stacked ? 1 : series.length;
  const bw = stacked ? Math.max(2, Math.min(40, cw * 0.6)) : Math.max(2, Math.min(18, (cw - 2) / groups - 2));
  const y = (v: number) => T + (H - T - B) * (1 - v / top);
  const every = Math.ceil(n / Math.max(3, Math.floor(W / 110)));
  const caption = label ?? ((d: D) => formatShortDate(lang, d.day));
  const tip = hover != null ? days[hover] : undefined;

  const bars = (d: D, i: number) => {
    const x0 = L + i * cw + (cw - groups * (bw + 2)) / 2;
    if (!stacked)
      return series.map((s, j) => {
        const v = d[s.key];
        if (!v) return null;
        const h = Math.max(2, y(0) - y(v));
        return <rect key={s.key} x={x0 + j * (bw + 2)} y={y(0) - h} width={bw} height={h} rx={1} className={FILL[j]} />;
      });
    let base = y(0);
    return series.map((s, j) => {
      const v = d[s.key];
      if (!v) return null;
      const h = Math.max(1, y(0) - y(v));
      base -= h;
      return <rect key={s.key} x={L + i * cw + (cw - bw) / 2} y={base} width={bw} height={h} className={FILL[j]} />;
    });
  };

  return (
    <Card className={cn("relative mt-4", className)}>
      <CardHeader title={title} actions={series.length > 1 ? <ChartLegend series={series} /> : undefined} />
      <div ref={box} className="relative">
        <svg viewBox={`0 0 ${W} ${H}`} role="img" aria-label={title} className="block h-auto w-full" preserveAspectRatio="none">
          {[0, top / 2, top].map((v) => (
            <g key={v}>
              <line x1={L} x2={W - R} y1={y(v)} y2={y(v)} className="stroke-line-soft" />
              <text x={L - 6} y={y(v) + 4} textAnchor="end" className="fill-muted text-[11px] tnum">
                {v ? axis(v) : "0"}
              </text>
            </g>
          ))}
          <line x1={L} x2={W - R} y1={y(0)} y2={y(0)} className="stroke-line-strong" />
          {days.map((d, i) => (
            <g key={d.day} onMouseEnter={() => setHover(i)} onMouseLeave={() => setHover(null)}>
              <rect x={L + i * cw} y={T} width={cw} height={H - T - B} className="fill-transparent" />
              {bars(d, i)}
              {i % every === 0 ? (
                <text x={L + i * cw + cw / 2} y={H - 8} textAnchor="middle" className="fill-muted text-[11px] tnum">
                  {caption(d)}
                </text>
              ) : null}
            </g>
          ))}
        </svg>
        {tip ? (
          <div
            role="tooltip"
            className="pointer-events-none absolute top-0 z-10 rounded-lg border border-line bg-surface px-3 py-2 text-xs shadow tnum"
            style={{ left: `${Math.min(80, Math.max(0, (((hover ?? 0) + 0.5) / n) * 100 - 10))}%` }}
          >
            <b className="text-ink-heading">{heading ? heading(tip) : date(tip.day)}</b>
            {(stacked ? [...series].reverse() : series).map((s) => (
              <div key={s.key}>
                {s.label} {format(tip[s.key])}
              </div>
            ))}
          </div>
        ) : null}
      </div>
    </Card>
  );
}
