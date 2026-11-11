/**
 * A node's forms: its details, the Xray balancer of a cascade entry, what
 * the apps are told about it, the maintenance flag (#115), moving all its devices to another node,
 * and, for a remote node, installing it on a server (or moving it to another one) over SSH or with
 * a new install command. The dangerous ones ask first, with the old questions.
 */
import { useQuery } from "@tanstack/react-query";
import { useState } from "react";
import { keys } from "@/api/keys";
import { get, post, put, type Schema } from "@/api/typed";
import { SettingsForm } from "@/components/admin/SettingsForm";
import { Button, Card, CardHeader, CopyButton, ErrorState, Field, FormError, Input, Select, Spinner } from "@/components/ui";
import { useApiMutation } from "@/hooks/useApiMutation";
import { useConfirm } from "@/hooks/useConfirm";
import { useOpenFocus } from "@/hooks/useOpenFocus";
import { useI18n } from "@/i18n";
import { NODE_STATUSES } from "../common";
import { NodeFields } from "./NodeFields";
import { moveTargets, nodeBody, nodeValues, sshBody, sshValues, type NodeReq } from "./nodeForm";
import { SshFields } from "./SshFields";

type Node = Schema<"AdminNode">;

export function EditNode({
  node,
  nodes,
  balancer,
  host,
  installError,
  onClose,
  onCommand,
}: {
  node: Node;
  nodes: readonly Node[];
  /** VLESS_BALANCER=1: cascade entries have a balancer fragment. */
  balancer: boolean;
  /** The address of the server to install on (after a failed install). */
  host?: string;
  installError?: string;
  onClose: () => void;
  onCommand: (command: string) => void;
}) {
  const { t } = useI18n();
  const [v, setV] = useState(() => nodeValues(node));
  const first = useOpenFocus();
  const local = !node.remote;
  const invalidate = [keys.admin.nodes(), keys.admin.dashboard()];

  return (
    <>
      <SettingsForm
        title={t("Edit node")}
        autoComplete="off"
        mutationFn={(body: NodeReq) => put("/admin/nodes/{id}", { params: { id: node.id }, body })}
        build={() => nodeBody(v, { local, was: node })}
        invalidate={invalidate}
        onSaved={onClose}
        actions={
          <Button variant="secondary" onClick={onClose}>
            {t("Cancel")}
          </Button>
        }
      >
        <NodeFields v={v} onChange={setV} local={local} protocolLocked firstRef={first} />
      </SettingsForm>
      {balancer && node.cascadeRole === "entry" ? <Balancer id={node.id} /> : null}
      <InTheApps node={node} />
      <Maintenance node={node} />
      <MoveDevices node={node} nodes={nodes} />
      {node.remote ? <Install node={node} host={host} error={installError} onDone={onClose} onCommand={onCommand} /> : null}
    </>
  );
}

/** A cascade entry's Xray balancer: the fragment to add to the entry's config by hand (docs/SERVER.md, "Cascade"). */
function Balancer({ id }: { id: string }) {
  const { t } = useI18n();
  const [open, setOpen] = useState(false);
  const q = useQuery({
    queryKey: [...keys.admin.nodes(), "balancer", id],
    queryFn: () => get("/admin/nodes/{id}/balancer", { params: { id } }),
    enabled: open,
    staleTime: 0,
  });
  const text = q.data ? JSON.stringify(q.data, null, 2) : "";
  return (
    <Card className="mt-4">
      <CardHeader
        title={t("Balancer")}
        actions={text ? <CopyButton size="sm" variant="secondary" text={text} label={t("Copy")} /> : null}
      />
      <p className="mb-4 mt-0 text-muted">
        {t(
          "Xray settings for this entry: the exits, a leastPing balancer and the observatory. Add them to the entry's Xray config by hand.",
        )}
      </p>
      {!open ? (
        <Button variant="secondary" onClick={() => setOpen(true)}>
          {t("Show the Xray settings")}
        </Button>
      ) : q.isError ? (
        <ErrorState error={q.error} onRetry={() => void q.refetch()} />
      ) : !q.data ? (
        <Spinner />
      ) : (
        <pre className="m-0 max-h-96 overflow-auto rounded-ctl bg-subtle p-3 font-mono text-[13px] leading-5">{text}</pre>
      )}
    </Card>
  );
}

/** What the apps are told: "Fastest" skips blocked and maintenance nodes and picks unstable ones last. */
function InTheApps({ node }: { node: Node }) {
  const { t } = useI18n();
  const [status, setStatus] = useState(node.status || "ok");
  const [notice, setNotice] = useState(node.notice ?? "");
  return (
    <SettingsForm
      title={t("In the apps")}
      intro={t("Fastest skips blocked nodes and nodes under maintenance, and picks unstable ones last. The node keeps working.")}
      mutationFn={(body: Schema<"NodeStatusReq">) => put("/admin/nodes/{id}/status", { params: { id: node.id }, body })}
      build={() => ({ status, notice: notice.trim() || null })}
      invalidate={[keys.admin.nodes()]}
    >
      <div className="grid grid-cols-1 gap-x-4 sm:grid-cols-2">
        <Field label={t("Status")}>
          {(id) => (
            <Select id={id} value={status} onChange={(e) => setStatus(e.target.value)}>
              {Object.entries(NODE_STATUSES).map(([k, x]) => (
                <option key={k} value={k}>
                  {t(x[1])}
                </option>
              ))}
            </Select>
          )}
        </Field>
        <Field label={t("Note for this node's users")}>
          {(id) => <Input id={id} maxLength={200} value={notice} onChange={(e) => setNotice(e.target.value)} />}
        </Field>
      </div>
    </SettingsForm>
  );
}

/** Issue #115: the node is expected to be silent, so there is no "no contact" alert. */
function Maintenance({ node }: { node: Node }) {
  const { t } = useI18n();
  return (
    <SettingsForm
      title={t("Maintenance")}
      intro={node.maintenance ? t('Marked as maintenance: the node is expected to be silent, so there is no "no contact" alert.') : null}
      mutationFn={(body: Schema<"NodeMaintenanceReq">) => put("/admin/nodes/{id}/maintenance", { params: { id: node.id }, body })}
      build={() => ({ maintenance: !node.maintenance })}
      invalidate={[keys.admin.nodes(), keys.admin.dashboard()]}
      submitLabel={node.maintenance ? t("Turn maintenance off") : t("Turn maintenance on")}
      submitVariant="secondary"
    >
      {null}
    </SettingsForm>
  );
}

/** All devices of this node to another one (its address is blocked, it is retired…). */
function MoveDevices({ node, nodes }: { node: Node; nodes: readonly Node[] }) {
  const { t, tp } = useI18n();
  const targets = moveTargets(nodes, node);
  const [to, setTo] = useState(targets[0]?.id ?? "");
  const { confirm, dialog } = useConfirm();
  const target = targets.find((n) => n.id === to);
  return (
    <>
      <SettingsForm
        title={t("Move devices")}
        intro={tp(node.peers, "{n} device|{n} devices")}
        inline={targets.length > 0}
        mutationFn={(body: Schema<"MoveDevicesReq">) => post("/admin/nodes/{id}/move-devices", { params: { id: node.id }, body })}
        build={() => ({ to })}
        ask={() =>
          confirm({
            title: t("Move all {n} devices of {from} to {to}?", { n: node.peers, from: node.name, to: target?.name ?? to }),
            confirm: t("Move all devices"),
            danger: true,
          })
        }
        invalidate={[keys.admin.nodes(), keys.admin.dashboard(), keys.admin.usersAll]}
        submitLabel={t("Move all devices")}
        submitDisabled={!node.peers || !target}
      >
        {targets.length ? (
          <Field label={t("Node")} className="mb-0">
            {(id) => (
              <Select id={id} value={to} onChange={(e) => setTo(e.target.value)}>
                {targets.map((n) => (
                  <option key={n.id} value={n.id}>
                    {n.name}, {n.city} ({n.peers} / {n.maxPeers})
                  </option>
                ))}
              </Select>
            )}
          </Field>
        ) : (
          <p className="mb-4 mt-0 text-muted">{t("No other working node")}</p>
        )}
      </SettingsForm>
      {dialog}
    </>
  );
}

/** Install on a server, or move a used node to another one, over SSH; or a new install command. */
function Install({
  node,
  host,
  error,
  onDone,
  onCommand,
}: {
  node: Node;
  host?: string;
  error?: string;
  onDone: () => void;
  onCommand: (command: string) => void;
}) {
  const { t } = useI18n();
  const used = node.state !== "waiting";
  const [ssh, setSsh] = useState(() => sshValues(host ?? ""));
  const { confirm, dialog } = useConfirm();
  const forget = () => setSsh((x) => ({ ...x, password: "", privateKey: "" }));
  const token = useApiMutation({
    mutationFn: () => post("/admin/nodes/{id}/token", { params: { id: node.id } }),
    success: t("Saved"),
    invalidate: [keys.admin.nodes()],
    onSuccess: (r) => onCommand(r.command),
  });

  return (
    <>
      <SettingsForm
        title={used ? t("Move to another server") : t("Install on a server")}
        autoComplete="off"
        mutationFn={async (body: Schema<"NodeSshReq">) => {
          try {
            return await post("/admin/nodes/{id}/install", { params: { id: node.id }, body });
          } finally {
            forget();
          }
        }}
        build={() => sshBody(ssh)}
        ask={() =>
          used
            ? confirm({
                title: t("Move the node to this server? The current machine stops serving it."),
                confirm: t("Install"),
                danger: true,
              })
            : true
        }
        invalidate={[keys.admin.nodes()]}
        onSaved={onDone}
        submitLabel={t("Install")}
        actions={
          <Button
            variant="secondary"
            disabled={token.isPending}
            onClick={async () => {
              if (
                await confirm({
                  title: t("Make a new install command? The old one stops working."),
                  confirm: t("Show the command instead"),
                })
              )
                token.mutate();
            }}
          >
            {t("Show the command instead")}
          </Button>
        }
      >
        {error ? <FormError className="mb-4 mt-0">{error}</FormError> : null}
        <SshFields v={ssh} onChange={setSsh} />
      </SettingsForm>
      {dialog}
    </>
  );
}
