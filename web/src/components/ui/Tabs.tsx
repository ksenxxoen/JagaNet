import { cn } from "@/lib/cn";

export interface TabItem<K extends string> {
  key: K;
  label: string;
}

/** .tabs: a row of links or buttons, the current one underlined. */
export function Tabs<K extends string>({
  items,
  value,
  onChange,
  className,
}: {
  items: TabItem<K>[];
  value: K;
  onChange: (k: K) => void;
  className?: string;
}) {
  return (
    <div role="tablist" className={cn("mb-4 flex gap-1 overflow-x-auto border-b border-line", className)}>
      {items.map((x) => (
        <button
          key={x.key}
          type="button"
          role="tab"
          aria-selected={x.key === value}
          onClick={() => onChange(x.key)}
          className={cn(
            "-mb-px whitespace-nowrap border-b-2 px-3 py-2 text-sm font-semibold",
            x.key === value ? "border-primary text-ink-heading" : "border-transparent text-muted hover:text-ink-heading",
          )}
        >
          {x.label}
        </button>
      ))}
    </div>
  );
}

/** Chips: a compact single choice (periods, filters). */
export function Chips<K extends string>({
  items,
  value,
  onChange,
  label,
}: {
  items: TabItem<K>[];
  value: K;
  onChange: (k: K) => void;
  label?: string;
}) {
  return (
    <div role="group" aria-label={label} className="inline-flex flex-wrap gap-0.5 rounded-ctl border border-line bg-subtle p-[3px]">
      {items.map((x) => (
        <button
          key={x.key}
          type="button"
          aria-pressed={x.key === value}
          onClick={() => onChange(x.key)}
          className={cn(
            "h-7 min-w-[34px] rounded-lg px-2.5 text-xs font-semibold",
            x.key === value ? "bg-surface text-ink-heading shadow-sm ring-1 ring-line" : "text-muted hover:text-ink-heading",
          )}
        >
          {x.label}
        </button>
      ))}
    </div>
  );
}
