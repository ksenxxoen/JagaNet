/** The legend of a chart with two series: a colored square and the name; the first primary, the second accent. */
import { cn } from "@/lib/cn";

export const SERIES_DOT = ["bg-primary", "bg-accent"];

export function ChartLegend({ series }: { series: { key: string; label: string }[] }) {
  return (
    <div className="flex flex-wrap gap-3 text-xs text-muted">
      {series.map((s, i) => (
        <span key={s.key} className="inline-flex items-center gap-1.5">
          <i aria-hidden="true" className={cn("size-2.5 rounded-sm", SERIES_DOT[i])} />
          {s.label}
        </span>
      ))}
    </div>
  );
}
