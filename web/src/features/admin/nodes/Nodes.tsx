/**
 * The nodes tab (nodesPane() in app.js): agent installs in progress, the install command after
 * creating a node by hand, the note for everyone, the nodes with their state, devices, address
 * and load, a node's forms (new or "Edit"), and the order of the locations, by dragging (or the
 * arrows) instead of typing numbers; the number stays in the node form.
 */
import { useQuery, useQueryClient } from "@tanstack/react-query";
import { useState } from "react";
import { keys } from "@/api/keys";
import { put, type Schema } from "@/api/typed";
import { DataTable } from "@/components/admin/DataTable";
import { SettingsForm } from "@/components/admin/SettingsForm";
import { SortableList } from "@/components/admin/SortableList";
import { Section, Toolbar } from "@/components/layout";
import { Button, Card, CardHeader, CopyButton, ErrorState, Field, Input, Level, Spinner } from "@/components/ui";
import { useApiMutation } from "@/hooks/useApiMutation";
import { useI18n } from "@/i18n";
import { INSTALL_STATES, NODE_STATES, NODE_STATUSES, StateBadge } from "../common";
import { nodesQuery, settingsQuery } from "../queries";
import { EditNode } from "./EditNode";
import { InstallCards } from "./InstallCards";
import { NewNode } from "./NewNode";
import { byOrder, ORDER_STEP } from "./nodeForm";

type Node = Schema<"AdminNode">;
type Editing = { kind: "new" } | { kind: "edit"; id: string; host?: string; error?: string } | null;

export default function Nodes() {
  const { t } = useI18n();
  const q = useQuery(nodesQuery);
  const [editing, setEditing] = useState<Editing>(null);
  const [command, setCommand] = useState<string | null>(null);

  if (q.isError) return <ErrorState error={q.error} onRetry={() => void q.refetch()} />;
  if (!q.data) return <Spinner />;
  const nodes = q.data.nodes;
  const current = editing?.kind === "edit" ? nodes.find((n) => n.id === editing.id) : undefined;
  const edit = (n: Node, host?: string, error?: string) => setEditing({ kind: "edit", id: n.id, host, error });

  return (
    <>
      <InstallCards nodes={nodes} onRetry={(n, host) => edit(n, host)} />
      {command ? <CommandCard command={command} onClose={() => setCommand(null)} /> : null}
      <NoteForEveryone />
      <Toolbar className="mt-6 justify-end">
        <Button onClick={() => setEditing({ kind: "new" })}>{t("New node")}</Button>
      </Toolbar>
      <NodeTable nodes={nodes} onEdit={(n) => edit(n)} />
      {editing?.kind === "new" ? (
        <NewNode onClose={() => setEditing(null)} onCommand={setCommand} onInstallFailed={(n, host, error) => edit(n, host, error)} />
      ) : current && editing?.kind === "edit" ? (
        <EditNode
          key={`${current.id}|${editing.host ?? ""}|${editing.error ?? ""}`}
          node={current}
          nodes={nodes}
          balancer={q.data.vlessBalancer}
          host={editing.host}
          installError={editing.error}
          onClose={() => setEditing(null)}
          onCommand={setCommand}
        />
      ) : null}
      <NodeOrder nodes={nodes} />
    </>
  );
}

function CommandCard({ command, onClose }: { command: string; onClose: () => void }) {
  const { t } = useI18n();
  return (
    <Card className="mb-4">
      <CardHeader title={t("Install command")} />
      <div className="flex flex-col gap-2 sm:flex-row">
        <Input
          readOnly
          aria-label={t("Install command")}
          className="font-mono text-[13px]"
          value={command}
          onFocus={(e) => e.target.select()}
        />
        <CopyButton text={command} label={t("Copy")} />
        <Button variant="secondary" onClick={onClose}>
          {t("Close")}
        </Button>
      </div>
    </Card>
  );
}

/** The note the Boop apps show on the main screen until it is closed. */
function NoteForEveryone() {
  const s = useQuery(settingsQuery);
  if (!s.data) return null;
  return <NoteForm text={s.data.notice.text ?? ""} />;
}

function NoteForm({ text }: { text: string }) {
  const { t } = useI18n();
  const [v, setV] = useState(text);
  return (
    <SettingsForm
      title={t("Note for everyone")}
      intro={t("The Boop apps show it on the main screen until it is closed. Empty: no note.")}
      inline
      className="mt-0"
      mutationFn={(body: Schema<"NoticeSettings">) => put("/admin/settings/notice", { body })}
      build={() => ({ text: v.trim() || null })}
      invalidate={[keys.admin.settings()]}
    >
      <Field label={<span className="sr-only">{t("Note for everyone")}</span>} className="mb-0">
        {(id) => <Input id={id} maxLength={200} placeholder={t("Planned work tonight")} value={v} onChange={(e) => setV(e.target.value)} />}
      </Field>
    </SettingsForm>
  );
}

function NodeTable({ nodes, onEdit }: { nodes: readonly Node[]; onEdit: (n: Node) => void }) {
  const { t, when, bits, percent } = useI18n();
  return (
    <DataTable
      label={t("Nodes")}
      rows={nodes}
      rowKey={(n) => n.id}
      rowClassName={(n) => (n.active ? undefined : "text-muted")}
      empty={t("No data yet.")}
      pageSize={0}
      columns={[
        {
          key: "node",
          header: t("Node"),
          sort: (n) => n.name,
          cell: (n) => (
            <>
              <b>{n.name}</b>
              <div className="text-muted">
                {n.city}, {n.countryCode}
                {n.remote ? "" : `, ${t("main server")}`}
              </div>
              {n.status && n.status !== "ok" ? (
                <div>
                  <StateBadge pair={NODE_STATUSES[n.status]} fallback={n.status} />
                </div>
              ) : null}
              {n.notice ? <div className="text-muted">{n.notice}</div> : null}
            </>
          ),
        },
        {
          key: "state",
          header: t("Status"),
          sort: (n) => n.state,
          cell: (n) => (
            <>
              {n.install?.state === "running" ? (
                <StateBadge pair={INSTALL_STATES.running} />
              ) : (
                <StateBadge pair={NODE_STATES[n.state]} fallback={n.state} />
              )}
              {n.maintenance ? <div className="text-[13px] text-muted">{t("Maintenance on")}</div> : null}
              <div className="text-[13px] text-muted">{n.lastReportAt ? when(n.lastReportAt) : " "}</div>
            </>
          ),
        },
        {
          key: "devices",
          header: t("Devices"),
          sort: (n) => n.peers,
          cell: (n) => (
            <>
              {n.peers} / {n.maxPeers}
              <div className="text-[13px] text-muted">{t("{n} online", { n: n.online })}</div>
            </>
          ),
        },
        {
          key: "address",
          header: t("Address"),
          optional: true,
          cell: (n) => (
            <>
              <span className="font-mono text-[13px]">{n.endpoint ?? ""}</span>
              {n.hostname && n.publicIp ? <div className="font-mono text-[13px] text-muted">{n.publicIp}</div> : null}
            </>
          ),
        },
        {
          key: "cpu",
          header: t("Processor"),
          optional: true,
          sort: (n) => n.cpu,
          cell: (n) => (
            <>
              {n.cpu == null ? "" : percent(n.cpu * 100)}
              <div className="text-[13px] text-muted">{n.rxBps == null ? " " : `${bits(n.rxBps)} / ${bits(n.txBps)}`}</div>
            </>
          ),
        },
        {
          key: "edit",
          header: <span className="sr-only">{t("Edit")}</span>,
          cell: (n) => (
            <Button size="sm" variant="secondary" aria-label={`${t("Edit")}: ${n.name}`} onClick={() => onEdit(n)}>
              {t("Edit")}
            </Button>
          ),
        },
      ]}
    />
  );
}

/**
 * The order of the locations in the apps and subscriptions. A move is shown at once and saved as
 * the whole order in one request (PUT /admin/nodes/order), so quick moves never leave half of it
 * saved; on an error the list is fetched again.
 */
function NodeOrder({ nodes }: { nodes: readonly Node[] }) {
  const { t } = useI18n();
  const qc = useQueryClient();
  const reorder = useApiMutation({
    mutationFn: (ids: string[]) => put("/admin/nodes/order", { body: { ids } }),
    success: t("Saved"),
    onMutate: async (ids: string[]) => {
      await qc.cancelQueries({ queryKey: keys.admin.nodes(), exact: true });
      qc.setQueryData<Schema<"AdminNodesRes">>(keys.admin.nodes(), (old) =>
        old ? { ...old, nodes: old.nodes.map((n) => (ids.includes(n.id) ? { ...n, sortOrder: ids.indexOf(n.id) * ORDER_STEP } : n)) } : old,
      );
    },
    onSuccess: (r) => {
      qc.setQueryData(keys.admin.nodes(), r);
    },
    onError: () => void qc.invalidateQueries({ queryKey: keys.admin.nodes(), exact: true }),
  });
  const ordered = byOrder(nodes);
  return (
    <Section title={t("Order in the list")} className="mt-10">
      <p className="-mt-2 mb-3 text-muted">
        {t("The order of the locations in the Boop apps and subscriptions. Drag a node or use the arrows.")}
      </p>
      <SortableList
        label={t("Order in the list")}
        items={ordered}
        itemKey={(n) => n.id}
        itemLabel={(n) => n.name}
        disabled={reorder.isPending}
        onReorder={(list) => reorder.mutate(list.map((n) => n.id))}
        renderItem={(n) => (
          <div className="flex flex-wrap items-baseline gap-x-3">
            <b className="text-ink-heading">{n.displayName || n.name}</b>
            <span className="text-[13px] text-muted">
              {n.displayName ? `${n.name}, ` : ""}
              {n.city}, {n.countryCode}
            </span>
            {n.active ? null : <Level>{t("Turned off")}</Level>}
            {n.inSubscription ? null : <span className="text-[13px] text-muted">{t("Not in subscriptions")}</span>}
          </div>
        )}
      />
    </Section>
  );
}
