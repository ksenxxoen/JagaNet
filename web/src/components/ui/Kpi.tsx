import type { ReactNode } from "react";
import { cn } from "@/lib/cn";
import { Card } from "./Card";

export interface KpiItem {
  label: string;
  value: ReactNode;
  /** A line under the number; kept even when empty, so the tiles of a row line up (kpi() in app.js). */
  note?: ReactNode;
}

/** .kpi: a number tile with its label and a note line. */
export function KpiTile({ label, value, note }: KpiItem) {
  return (
    <Card className="flex flex-col p-4">
      <div className="text-[13px] text-muted">{label}</div>
      <div className="mt-1 text-2xl font-extrabold leading-tight tracking-tight text-ink-heading tnum">{value}</div>
      <div className="mt-0.5 min-h-[1.25rem] text-xs text-muted">{note}</div>
    </Card>
  );
}

/** .kpis: a full row of tiles (two a row on phones). */
export function KpiGrid({ items, className }: { items: KpiItem[]; className?: string }) {
  return (
    <div className={cn("mb-4 grid grid-cols-2 gap-3 sm:grid-cols-[repeat(auto-fit,minmax(150px,1fr))]", className)}>
      {items.map((x) => (
        <KpiTile key={x.label} {...x} />
      ))}
    </div>
  );
}
