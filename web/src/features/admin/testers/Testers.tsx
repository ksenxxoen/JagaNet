/**
 * The test accounts tab: new accounts from rows pasted from a table, the
 * list with each key's last connection, the table for Excel, giving an account's keys to a
 * registered user (with suggestions as you type) and deleting an account. Both ask first.
 */
import { useQuery } from "@tanstack/react-query";
import { useEffect, useId, useState } from "react";
import { keys } from "@/api/keys";
import { del, get, post, type Schema } from "@/api/typed";
import { DataTable } from "@/components/admin/DataTable";
import { SettingsForm } from "@/components/admin/SettingsForm";
import { Section } from "@/components/layout";
import { Button, Field, Input, Textarea } from "@/components/ui";
import { useApiMutation } from "@/hooks/useApiMutation";
import { useConfirm } from "@/hooks/useConfirm";
import { useCopy } from "@/hooks/useCopy";
import { localDay, useI18n } from "@/i18n";
import { download } from "@/lib/download";
import { testersQuery } from "../queries";
import { parseTesters, testersCsv } from "./testers";

type Account = Schema<"TestAccount">;

export default function Testers() {
  const { t, date } = useI18n();
  const q = useQuery(testersQuery);
  const [giving, setGiving] = useState<string | null>(null);
  const { confirm, dialog } = useConfirm();
  const remove = useApiMutation({
    mutationFn: (id: string) => del("/admin/test-accounts/{id}", { params: { id } }),
    invalidate: [keys.admin.testers()],
  });
  const list = q.data?.accounts;

  const onDelete = async (a: Account) => {
    const yes = await confirm({
      title: t("Delete the account of {name} and its keys? The keys stop working.", { name: a.name }),
      confirm: t("Delete"),
      danger: true,
    });
    if (yes) remove.mutate(a.id);
  };

  return (
    <>
      <NewAccounts />
      <Section
        title={t("Test accounts")}
        actions={
          list?.length ? (
            <Button variant="secondary" onClick={() => download.save(testersCsv(list), "jaganet-test-accounts.csv")}>
              {t("Download table")}
            </Button>
          ) : null
        }
      >
        <DataTable
          label={t("Test accounts")}
          rows={list}
          loading={q.isPending}
          error={q.error}
          onRetry={() => void q.refetch()}
          rowKey={(a) => a.id}
          empty={t("No test accounts")}
          pageSize={0}
          columns={[
            { key: "name", header: t("Client"), sort: (a) => a.name, cell: (a) => <b>{a.name}</b> },
            { key: "email", header: t("Email"), optional: true, className: "font-mono text-[13px]", cell: (a) => a.email },
            { key: "devices", header: t("Devices"), sort: (a) => a.devices, cell: (a) => a.devices },
            { key: "until", header: t("Valid until"), sort: (a) => a.expiresAt, cell: (a) => date(localDay(a.expiresAt)) },
            {
              key: "used",
              header: t("Connected"),
              sort: (a) => used(a),
              cell: (a) => {
                const text = t("{a} of {b}", { a: used(a), b: a.keys.length });
                return used(a) === 0 ? <span className="text-danger-text">{text}</span> : text;
              },
            },
            { key: "keys", header: t("Keys"), cell: (a) => <KeyLinks a={a} /> },
            {
              key: "actions",
              header: <span className="sr-only">{t("Actions")}</span>,
              cell: (a) =>
                giving === a.id ? (
                  <GiveForm a={a} onClose={() => setGiving(null)} />
                ) : (
                  <div className="flex flex-col gap-2">
                    <Button size="sm" onClick={() => setGiving(a.id)}>
                      {t("Give")}
                    </Button>
                    <Button size="sm" variant="secondary" disabled={remove.isPending} onClick={() => void onDelete(a)}>
                      {t("Delete")}
                    </Button>
                  </div>
                ),
            },
          ]}
        />
      </Section>
      {dialog}
    </>
  );
}

const used = (a: Account) => a.activity.filter((x) => x.lastSeenAt).length;

function KeyLinks({ a }: { a: Account }) {
  const { t, when, bytes } = useI18n();
  return (
    <ul className="m-0 list-none space-y-1 p-0">
      {a.keys.map((k, i) => {
        const x = a.activity[i];
        const vpn = a.vpnKeys[i];
        return (
          <li key={k} className="whitespace-nowrap">
            <a href={k} target="_blank" rel="noopener noreferrer" className="text-link underline-offset-2 hover:underline">
              {t("Key {n}", { n: i + 1 })}
            </a>
            {vpn ? <CopyVpn text={vpn} /> : null}{" "}
            {!x ? null : !x.lastSeenAt ? (
              <span className="text-danger-text">{t("never connected")}</span>
            ) : (
              <span className="text-muted">
                {when(x.lastSeenAt)}, {bytes(x.rxBytes + x.txBytes)}
              </span>
            )}
          </li>
        );
      })}
    </ul>
  );
}

/** The vpn:// key for AmneziaVPN, copied with the site's usual toast. */
function CopyVpn({ text }: { text: string }) {
  const { t } = useI18n();
  const { copy } = useCopy();
  return (
    <button
      type="button"
      title={t("Copy key for AmneziaVPN")}
      aria-label={t("Copy key for AmneziaVPN")}
      onClick={() => void copy(text)}
      className="ml-1.5 font-mono text-[13px] text-link underline-offset-2 hover:underline"
    >
      vpn://
    </button>
  );
}

function NewAccounts() {
  const { t } = useI18n();
  const [rows, setRows] = useState("");
  const [days, setDays] = useState("30");
  const parsed = parseTesters(rows);
  return (
    <SettingsForm
      title={t("New test accounts")}
      className="mt-0"
      mutationFn={(body: Schema<"TestAccountsReq">) => post("/admin/test-accounts", { body })}
      build={() => {
        const accounts = parseTesters(rows);
        if (!accounts.length) throw new Error(t("No rows with a name and a number of devices"));
        return { accounts, days: Number(days) };
      }}
      submitLabel={t("Create accounts")}
      invalidate={[keys.admin.testers()]}
      onSaved={() => setRows("")}
    >
      <Field label={t("Rows from the table")}>
        {(id) => (
          <Textarea
            id={id}
            rows={8}
            required
            className="font-mono"
            placeholder={"Игорь + Таня\t5\nЛеня + Лера\t3"}
            value={rows}
            onChange={(e) => setRows(e.target.value)}
          />
        )}
      </Field>
      <div className="flex flex-col gap-x-4 sm:flex-row sm:items-end">
        <Field label={t("Test length, days")} className="sm:w-56">
          {(id) => (
            <Input id={id} type="number" min={1} max={365} step={1} required value={days} onChange={(e) => setDays(e.target.value)} />
          )}
        </Field>
        <p aria-live="polite" className="mb-4 mt-0 min-h-5 text-muted sm:pb-2.5">
          {parsed.length ? t("{a} accounts, {d} devices", { a: parsed.length, d: parsed.reduce((n, r) => n + r.devices, 0) }) : ""}
        </p>
      </div>
    </SettingsForm>
  );
}

/** Registered users whose e-mail matches, from the analytics search (suggestions only). */
function useSuggestions(text: string) {
  const [q, setQ] = useState("");
  useEffect(() => {
    const v = text.trim();
    const timer = setTimeout(() => setQ(v.length >= 2 ? v : ""), 250);
    return () => clearTimeout(timer);
  }, [text]);
  const r = useQuery({
    queryKey: ["admin", "testers", "suggest", q],
    queryFn: () => get("/admin/analytics/users", { query: { period: "7d", q } }),
    enabled: q.length >= 2,
    staleTime: 30_000,
    retry: false,
  });
  return (r.data?.users ?? []).filter((u) => !u.email.endsWith(".invalid")).slice(0, 8);
}

function GiveForm({ a, onClose }: { a: Account; onClose: () => void }) {
  const { t } = useI18n();
  const [email, setEmail] = useState("");
  const list = useId();
  const suggestions = useSuggestions(email);
  const { confirm, dialog } = useConfirm();
  return (
    <>
      <SettingsForm
        className="mt-0 min-w-[260px] p-3 shadow-none"
        mutationFn={(body: Schema<"AssignTestAccountReq">) => post("/admin/test-accounts/{id}/assign", { params: { id: a.id }, body })}
        build={() => ({ email: email.trim() })}
        ask={(body) =>
          confirm({
            title: t("Give the keys of {name} to {email}? The test account will be removed.", { name: a.name, email: body.email }),
            confirm: t("Give"),
          })
        }
        submitLabel={t("Give")}
        success={false}
        invalidate={[keys.admin.testers(), keys.admin.usersAll]}
        onSaved={onClose}
        actions={
          <Button variant="secondary" onClick={onClose}>
            {t("Cancel")}
          </Button>
        }
      >
        <Input
          type="email"
          required
          list={list}
          autoComplete="off"
          aria-label={t("User's e-mail")}
          placeholder={t("User's e-mail")}
          value={email}
          onChange={(e) => setEmail(e.target.value)}
          className="mb-3"
        />
        <datalist id={list}>
          {suggestions.map((u) => (
            <option key={u.id} value={u.email} />
          ))}
        </datalist>
      </SettingsForm>
      {dialog}
    </>
  );
}
