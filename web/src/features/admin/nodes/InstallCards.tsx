/**
 * The progress of agent installs (installCard() in app.js): a card for every running install, and
 * for a finished one until it is closed (the start time is remembered under "install:<node id>" as
 * on the old site, so a closed card stays closed there too). A running install is asked every 2
 * seconds (usePolling: stops when it ends, when the tab is hidden or left); when it ends the node
 * list is fetched again.
 */
import { useQueryClient } from "@tanstack/react-query";
import { useEffect, useRef, useState } from "react";
import { keys } from "@/api/keys";
import { get, type Schema } from "@/api/typed";
import { Button, Card, CardHeader } from "@/components/ui";
import { usePolling } from "@/hooks/usePolling";
import { useI18n } from "@/i18n";
import { store } from "@/lib/storage";
import { INSTALL_STATES, StateBadge } from "../common";

type Node = Schema<"AdminNode">;
type Install = Schema<"NodeInstall">;

export const INSTALL_POLL_MS = 2000;
const seenKey = (id: string) => `install:${id}`;

/** The nodes whose install card is shown: running, or ended and not closed yet. */
export const shownInstalls = (nodes: readonly Node[]) =>
  nodes.filter((n) => n.install && (n.install.state === "running" || store.get(seenKey(n.id)) !== n.install.startedAt));

export function InstallCards({ nodes, onRetry }: { nodes: readonly Node[]; onRetry: (n: Node, host: string) => void }) {
  const [, setClosed] = useState(0);
  return (
    <>
      {shownInstalls(nodes).map((n) => (
        <InstallCard
          key={`${n.id}|${n.install!.startedAt}`}
          node={n}
          onRetry={onRetry}
          onClose={(startedAt) => {
            store.set(seenKey(n.id), startedAt);
            setClosed((x) => x + 1);
          }}
        />
      ))}
    </>
  );
}

function InstallCard({
  node,
  onRetry,
  onClose,
}: {
  node: Node;
  onRetry: (n: Node, host: string) => void;
  onClose: (startedAt: string) => void;
}) {
  const { t } = useI18n();
  const qc = useQueryClient();
  const q = usePolling<Install>({
    queryKey: keys.admin.nodeInstall(node.id),
    queryFn: () => get("/admin/nodes/{id}/install", { params: { id: node.id } }),
    interval: INSTALL_POLL_MS,
    until: (i) => i.state !== "running",
  });
  // The list's copy has no log; until the first answer it stands in.
  const i = q.data ?? node.install!;
  const log = useRef<HTMLPreElement>(null);
  const wasRunning = useRef(i.state === "running");

  useEffect(() => {
    if (log.current) log.current.scrollTop = log.current.scrollHeight;
  }, [i.log]);
  useEffect(() => {
    if (wasRunning.current && i.state !== "running") {
      wasRunning.current = false;
      void qc.invalidateQueries({ queryKey: keys.admin.nodes(), exact: true });
    }
  }, [i.state, qc]);

  return (
    <Card className="mb-4" aria-live="polite">
      <CardHeader title={`${node.name}, ${i.host}`} actions={<StateBadge pair={INSTALL_STATES[i.state]} fallback={i.state} />} />
      <p className="mb-3 mt-0">
        {i.state === "running" ? (
          `${t(i.step || "Connecting")}…`
        ) : i.state === "done" ? (
          t("Installed")
        ) : (
          <span className="text-danger-text">{t(i.error || "Something went wrong", i.errorArgs)}</span>
        )}
      </p>
      {i.log ? (
        <pre
          ref={log}
          className="mb-3 mt-0 max-h-64 overflow-auto whitespace-pre-wrap break-all rounded-ctl bg-subtle p-3 font-mono text-[13px] leading-5"
        >
          {i.log}
        </pre>
      ) : null}
      {i.state === "running" ? null : (
        <div className="flex flex-wrap gap-2">
          {i.state === "failed" ? <Button onClick={() => onRetry(node, i.host)}>{t("Try again")}</Button> : null}
          <Button variant="secondary" onClick={() => onClose(i.startedAt)}>
            {t("Close")}
          </Button>
        </div>
      )}
    </Card>
  );
}
