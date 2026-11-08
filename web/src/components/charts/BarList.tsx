/** Horizontal bars for small breakdowns: where clicks come from, sign-up channels (breakdown() in app.js). */
import { Card, CardHeader } from "@/components/ui";
import { useI18n } from "@/i18n";

export function BarList({ title, items, name }: { title: string; items: { key: string; count: number }[]; name: (key: string) => string }) {
  const { t } = useI18n();
  const max = Math.max(1, ...items.map((i) => i.count));
  return (
    <Card>
      <CardHeader title={title} className="mb-0" />
      {items.length ? (
        <ul className="m-0 list-none p-0">
          {items.map((i) => (
            <li key={i.key} className="mt-3.5 grid grid-cols-[1fr_auto] gap-x-3 gap-y-1">
              <span>{name(i.key)}</span>
              <b className="text-ink-heading tnum">{i.count}</b>
              <div className="col-span-2 h-1.5 overflow-hidden rounded bg-line-soft">
                <div className="h-full rounded bg-primary" style={{ width: `${Math.max(2, (i.count / max) * 100)}%` }} />
              </div>
            </li>
          ))}
        </ul>
      ) : (
        <p className="mb-0 mt-3 text-muted">{t("No data yet.")}</p>
      )}
    </Card>
  );
}
