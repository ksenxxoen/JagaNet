/**
 * Daily bars in SVG (dayChart() in app.js): one series as plain bars, two as grouped bars with a
 * legend. One axis with a round top, a quiet grid, a tooltip with the day's values on hover or
 * focus. Drawn at the real width, so the labels keep their size on phones. W-8 adds line charts
 * next to it.
 */
import { useEffect, useRef, useState } from "react";
import { Card, CardHeader } from "@/components/ui";
import { useI18n } from "@/i18n";
import { cn } from "@/lib/cn";

export interface BarSeries<K extends string> {
  key: K;
  label: string;
}

/** Series colors from the theme: the first primary, the second accent. */
const FILL = ["fill-primary", "fill-accent"];
const DOT = ["bg-primary", "bg-accent"];

/** The axis top: a round number ≥ max whose half is a whole number too. */
export function niceTop(max: number): number {
  const m = Math.max(1, max);
  const mag = Math.pow(10, Math.floor(Math.log10(m)));
  return Math.max(
    2,
    [1, 1.2, 1.5, 2, 2.5, 3, 4, 5, 6, 8, 10].map((k) => k * mag).find((v) => v >= m && Number.isInteger(v / 2)) ?? 10 * mag,
  );
}

function useWidth(fallback = 960) {
  const ref = useRef<HTMLDivElement>(null);
  const [width, setWidth] = useState(fallback);
  useEffect(() => {
    const el = ref.current;
    if (!el) return;
    const measure = () => el.clientWidth && setWidth(el.clientWidth);
    measure();
    if (typeof ResizeObserver === "undefined") return;
    const ro = new ResizeObserver(measure);
    ro.observe(el);
    return () => ro.disconnect();
  }, []);
  return [ref, width] as const;
}

export function DayBars<K extends string, D extends { day: string } & Record<K, number>>({
  title,
  days,
  series,
  format = String,
  className,
}: {
  title: string;
  days: D[];
  series: BarSeries<K>[];
  /** A value in the tooltip (money on the finance tab); the number itself by default. */
  format?: (v: number) => string;
  className?: string;
}) {
  const { date, lang } = useI18n();
  const [box, measured] = useWidth();
  const [hover, setHover] = useState<number | null>(null);
  const W = Math.max(300, Math.min(1000, measured));
  const H = W < 600 ? 180 : 220;
  const L = 34,
    R = 8,
    T = 10,
    B = 26;
  const top = niceTop(Math.max(0, ...days.flatMap((d) => series.map((s) => d[s.key]))));
  const n = Math.max(1, days.length);
  const cw = (W - L - R) / n;
  const bw = Math.max(2, Math.min(18, (cw - 2) / series.length - 2));
  const y = (v: number) => T + (H - T - B) * (1 - v / top);
  const every = Math.ceil(n / Math.max(3, Math.floor(W / 110)));
  const short = (iso: string) => {
    const [, m, d] = iso.split("-").map(Number);
    return lang === "de" ? `${d}.${m}.` : `${d}.${String(m).padStart(2, "0")}`;
  };
  const tip = hover != null ? days[hover] : undefined;

  return (
    <Card className={cn("relative mt-4", className)}>
      <CardHeader
        title={title}
        actions={
          series.length > 1 ? (
            <div className="flex flex-wrap gap-3 text-xs text-muted">
              {series.map((s, i) => (
                <span key={s.key} className="inline-flex items-center gap-1.5">
                  <i aria-hidden="true" className={cn("size-2.5 rounded-sm", DOT[i])} />
                  {s.label}
                </span>
              ))}
            </div>
          ) : undefined
        }
      />
      <div ref={box} className="relative">
        <svg viewBox={`0 0 ${W} ${H}`} role="img" aria-label={title} className="block h-auto w-full" preserveAspectRatio="none">
          {[0, top / 2, top].map((v) => (
            <g key={v}>
              <line x1={L} x2={W - R} y1={y(v)} y2={y(v)} className="stroke-line-soft" />
              <text x={L - 6} y={y(v) + 4} textAnchor="end" className="fill-muted text-[11px]">
                {Math.round(v)}
              </text>
            </g>
          ))}
          <line x1={L} x2={W - R} y1={y(0)} y2={y(0)} className="stroke-line-strong" />
          {days.map((d, i) => {
            const x0 = L + i * cw + (cw - series.length * (bw + 2)) / 2;
            return (
              <g key={d.day} onMouseEnter={() => setHover(i)} onMouseLeave={() => setHover(null)}>
                <rect x={L + i * cw} y={T} width={cw} height={H - T - B} className="fill-transparent" />
                {series.map((s, j) => {
                  const v = d[s.key];
                  if (!v) return null;
                  const h = Math.max(2, y(0) - y(v));
                  return <rect key={s.key} x={x0 + j * (bw + 2)} y={y(0) - h} width={bw} height={h} rx={1} className={FILL[j]} />;
                })}
                {i % every === 0 ? (
                  <text x={L + i * cw + cw / 2} y={H - 8} textAnchor="middle" className="fill-muted text-[11px]">
                    {short(d.day)}
                  </text>
                ) : null}
              </g>
            );
          })}
        </svg>
        {tip ? (
          <div
            role="tooltip"
            className="pointer-events-none absolute top-0 z-10 rounded-lg border border-line bg-surface px-3 py-2 text-xs shadow"
            style={{ left: `${Math.min(80, Math.max(0, (((hover ?? 0) + 0.5) / n) * 100 - 10))}%` }}
          >
            <b className="text-ink-heading">{date(tip.day)}</b>
            {series.map((s) => (
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
