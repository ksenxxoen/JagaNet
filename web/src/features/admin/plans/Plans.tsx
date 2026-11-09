/**
 * The plans tab (plansPane() in app.js): the plans for new purchases (length, prices in rubles and
 * euros, devices, data, sold, in use, status), the form for a new or changed plan, and the Free
 * plan settings. Taking a plan off sale (hidden or archived) asks first; people who have it keep it.
 */
import { useQuery } from "@tanstack/react-query";
import { useState } from "react";
import { useOpenFocus } from "@/hooks/useOpenFocus";
import { keys } from "@/api/keys";
import { post, put, type Schema } from "@/api/typed";
import { DataTable } from "@/components/admin/DataTable";
import { SettingsForm } from "@/components/admin/SettingsForm";
import { Toolbar } from "@/components/layout";
import { Button, Field, Input, Select, StatusBadge } from "@/components/ui";
import { useConfirm } from "@/hooks/useConfirm";
import { t, useI18n } from "@/i18n";
import { period } from "@/lib/pricing";
import { tariffsQuery } from "../queries";
import { WithSettings, type AdminSettings } from "../settings/WithSettings";
import { fromMinor, toMinor } from "./money";

type Tariff = Schema<"AdminTariff">;
type TariffReq = Schema<"TariffReq">;
type Status = Schema<"TariffStatus">;

const STATUS: Record<Status, string> = { active: "On sale", hidden: "Hidden", archived: "Archived" };

export interface PlanValues {
  name: string;
  badge: string;
  durationValue: string;
  durationUnit: "months" | "days";
  rub: string;
  eur: string;
  devices: string;
  trafficGb: string;
  sort: string;
  status: Status;
}

export const planValues = (x: Tariff | null, count: number): PlanValues => ({
  name: x?.name ?? "",
  badge: x?.badge ?? "",
  durationValue: String(x?.durationValue ?? 1),
  durationUnit: x?.durationUnit ?? "months",
  rub: x ? fromMinor(x.priceRubMinor) : "",
  eur: x ? fromMinor(x.priceEurMinor) : "",
  devices: String(x?.deviceLimit ?? 5),
  trafficGb: x?.trafficGb == null ? "" : String(x.trafficGb),
  sort: String(x?.sort ?? count + 1),
  status: x?.status ?? "active",
});

/** The request from the form (the body of plansPane()); prices must be numbers above zero. */
export function planBody(v: PlanValues): TariffReq {
  const body: TariffReq = {
    name: v.name.trim(),
    badge: v.badge.trim() || null,
    durationValue: Number(v.durationValue),
    durationUnit: v.durationUnit,
    priceRubMinor: toMinor(v.rub),
    priceEurMinor: toMinor(v.eur),
    deviceLimit: Number(v.devices),
    trafficGb: v.trafficGb.trim() ? Number(v.trafficGb) : null,
    sort: Number(v.sort),
    status: v.status,
  };
  if (!(body.priceRubMinor > 0) || !(body.priceEurMinor > 0))
    throw new Error(t("Enter prices as numbers above zero, for example 299 or 4,99"));
  return body;
}

export default function Plans() {
  return <WithSettings>{(s) => <PlansPane s={s} />}</WithSettings>;
}

function PlansPane({ s }: { s: AdminSettings }) {
  const { t, money } = useI18n();
  const q = useQuery(tariffsQuery);
  // null: no form; "new"; or the id of the plan being changed.
  const [editing, setEditing] = useState<string | null>(null);
  const list = q.data?.tariffs;
  const current = editing && editing !== "new" ? (list?.find((x) => x.id === editing) ?? null) : null;

  return (
    <>
      <Toolbar className="justify-between">
        <span className="text-muted">{t("For new purchases")}</span>
        <Button onClick={() => setEditing("new")}>{t("New plan")}</Button>
      </Toolbar>
      <DataTable
        label={t("Plans")}
        rows={list}
        loading={q.isPending}
        error={q.error}
        onRetry={() => void q.refetch()}
        rowKey={(x) => x.id}
        rowClassName={(x) => (x.status === "archived" ? "text-muted" : undefined)}
        empty={t("No plans")}
        pageSize={0}
        columns={[
          {
            key: "name",
            header: t("Name"),
            sort: (x) => x.name,
            cell: (x) => (
              <>
                <button
                  type="button"
                  className="text-left font-semibold text-link underline-offset-2 hover:underline"
                  onClick={() => setEditing(x.id)}
                >
                  {x.name}
                </button>
                {x.badge ? (
                  <StatusBadge tone="primary" className="ml-2">
                    {x.badge}
                  </StatusBadge>
                ) : null}
              </>
            ),
          },
          { key: "length", header: t("Length"), cell: (x) => period(x) },
          { key: "rub", header: t("Price, rubles"), sort: (x) => x.priceRubMinor, cell: (x) => money(x.priceRubMinor, "RUB") },
          {
            key: "eur",
            header: t("Price, euros"),
            sort: (x) => x.priceEurMinor,
            optional: true,
            cell: (x) => money(x.priceEurMinor, "EUR"),
          },
          { key: "devices", header: t("Devices"), optional: true, sort: (x) => x.deviceLimit, cell: (x) => x.deviceLimit },
          {
            key: "data",
            header: t("Data"),
            optional: true,
            cell: (x) => (x.trafficGb == null ? t("Unlimited") : t("{n} GB a month", { n: x.trafficGb })),
          },
          { key: "sold", header: t("Sold"), sort: (x) => x.sold, cell: (x) => x.sold },
          { key: "now", header: t("Using now"), sort: (x) => x.activeNow, cell: (x) => x.activeNow },
          {
            key: "status",
            header: t("Status"),
            sort: (x) => x.status,
            cell: (x) => <StatusBadge tone={x.status === "active" ? "ok" : "neutral"}>{t(STATUS[x.status])}</StatusBadge>,
          },
          {
            key: "edit",
            header: <span className="sr-only">{t("Edit")}</span>,
            cell: (x) => (
              <Button size="sm" variant="secondary" onClick={() => setEditing(x.id)}>
                {t("Edit")}
              </Button>
            ),
          },
        ]}
      />
      {editing && list ? <PlanForm key={editing} plan={current} count={list.length} onClose={() => setEditing(null)} /> : null}
      <FreePlan s={s} />
    </>
  );
}

function PlanForm({ plan, count, onClose }: { plan: Tariff | null; count: number; onClose: () => void }) {
  const { t } = useI18n();
  const [v, setV] = useState(() => planValues(plan, count));
  const { confirm, dialog } = useConfirm();
  const first = useOpenFocus();
  const set = (k: keyof PlanValues) => (e: { target: { value: string } }) => setV({ ...v, [k]: e.target.value });

  return (
    <>
      <SettingsForm
        title={plan ? t("Edit plan") : t("New plan")}
        mutationFn={(body: TariffReq) =>
          plan ? put("/admin/tariffs/{id}", { params: { id: plan.id }, body }) : post("/admin/tariffs", { body })
        }
        build={() => planBody(v)}
        ask={(body) =>
          plan?.status === "active" && body.status !== "active"
            ? confirm({ title: t("Take this plan off sale? People who bought it keep their access."), confirm: t("Save") })
            : true
        }
        invalidate={[keys.admin.tariffs(), keys.plans()]}
        onSaved={onClose}
        actions={
          <Button variant="secondary" onClick={onClose}>
            {t("Cancel")}
          </Button>
        }
      >
        <div className="grid grid-cols-1 gap-x-4 sm:grid-cols-2 lg:grid-cols-3">
          <Field label={t("Name")}>
            {(id) => <Input id={id} required maxLength={60} placeholder="Pro на месяц" ref={first} value={v.name} onChange={set("name")} />}
          </Field>
          <Field label={t("Badge")}>
            {(id) => <Input id={id} maxLength={30} placeholder="Выгодно" value={v.badge} onChange={set("badge")} />}
          </Field>
          <div className="grid grid-cols-2 gap-x-3">
            <Field label={t("Length")}>
              {(id) => <Input id={id} type="number" min={1} step={1} required value={v.durationValue} onChange={set("durationValue")} />}
            </Field>
            <Field label={<span className="sr-only">{t("Months")}</span>}>
              {(id) => (
                <Select id={id} value={v.durationUnit} onChange={set("durationUnit")} className="mt-7">
                  <option value="months">{t("Months")}</option>
                  <option value="days">{t("Days")}</option>
                </Select>
              )}
            </Field>
          </div>
          <Field label={t("Price, rubles")}>
            {(id) => <Input id={id} inputMode="decimal" required placeholder="299" value={v.rub} onChange={set("rub")} />}
          </Field>
          <Field label={t("Price, euros")}>
            {(id) => <Input id={id} inputMode="decimal" required placeholder="4,99" value={v.eur} onChange={set("eur")} />}
          </Field>
          <Field label={t("Devices")}>
            {(id) => <Input id={id} type="number" min={1} max={100} step={1} required value={v.devices} onChange={set("devices")} />}
          </Field>
          <Field label={t("Data per month, GB")}>
            {(id) => (
              <Input id={id} type="number" min={1} step={1} placeholder={t("Unlimited")} value={v.trafficGb} onChange={set("trafficGb")} />
            )}
          </Field>
          <Field label={t("Order on the page")}>
            {(id) => <Input id={id} type="number" step={1} required value={v.sort} onChange={set("sort")} />}
          </Field>
          <Field label={t("Status")}>
            {(id) => (
              <Select id={id} value={v.status} onChange={set("status")}>
                {(Object.keys(STATUS) as Status[]).map((k) => (
                  <option key={k} value={k}>
                    {t(STATUS[k])}
                  </option>
                ))}
              </Select>
            )}
          </Field>
        </div>
      </SettingsForm>
      {dialog}
    </>
  );
}

function FreePlan({ s }: { s: AdminSettings }) {
  const { t } = useI18n();
  const p = s.plans;
  const [v, setV] = useState({
    speed: String(p.freeSpeedMbps ?? 0),
    days: String(p.freeDays ?? 0),
    gb: String(p.freeMonthlyGb),
    devices: String(p.freeDeviceLimit),
  });
  const set = (k: keyof typeof v) => (e: { target: { value: string } }) => setV({ ...v, [k]: e.target.value });
  const whole = { type: "number", min: 0, step: 1, inputMode: "numeric", required: true } as const;
  const note = (text: string) => <span className="font-normal text-muted">{text}</span>;
  return (
    <SettingsForm
      title={t("Free plan")}
      className="mt-8"
      mutationFn={(body: Schema<"PlanSettings">) => put("/admin/settings/plans", { body })}
      build={() => ({
        freeMonthlyGb: Number(v.gb),
        freeDeviceLimit: Number(v.devices),
        freeSpeedMbps: Number(v.speed),
        freeDays: Number(v.days),
      })}
      invalidate={[keys.admin.settings(), keys.plans()]}
    >
      <div className="grid grid-cols-1 gap-x-4 sm:grid-cols-2 lg:grid-cols-4">
        <Field
          label={
            <>
              {t("Speed, Mbit/s")} {note(t("0 no limit"))}
            </>
          }
        >
          {(id) => <Input id={id} {...whole} value={v.speed} onChange={set("speed")} />}
        </Field>
        <Field
          label={
            <>
              {t("Free period, days")} {note(t("0 for ever"))}
            </>
          }
        >
          {(id) => <Input id={id} {...whole} value={v.days} onChange={set("days")} />}
        </Field>
        <Field
          label={
            <>
              {t("Data per month, GB")} {note(t("0 no limit"))}
            </>
          }
        >
          {(id) => <Input id={id} {...whole} value={v.gb} onChange={set("gb")} />}
        </Field>
        <Field label={t("Devices on Free")}>{(id) => <Input id={id} {...whole} value={v.devices} onChange={set("devices")} />}</Field>
      </div>
    </SettingsForm>
  );
}
