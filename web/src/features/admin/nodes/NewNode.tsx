/**
 * A new node (openNew() in app.js): its details and the server to install it on. "Create and
 * install" creates it and starts the install over SSH; if the install can't start, the node
 * exists already and its edit form opens with the address and the message. "Create, I'll install
 * by hand" creates it and shows the install command.
 */
import { useQueryClient } from "@tanstack/react-query";
import { useRef, useState } from "react";
import { keys } from "@/api/keys";
import { post, type Schema } from "@/api/typed";
import { Button, Card, CardHeader, Form } from "@/components/ui";
import { useOpenFocus } from "@/hooks/useOpenFocus";
import { useI18n } from "@/i18n";
import { NodeFields } from "./NodeFields";
import { nodeBody, nodeValues, sshBody, sshValues } from "./nodeForm";
import { SshFields } from "./SshFields";

const message = (e: unknown) => (e instanceof Error ? e.message : String(e));

export function NewNode({
  onClose,
  onCommand,
  onInstallFailed,
}: {
  onClose: () => void;
  /** Created to be installed by hand: its install command. */
  onCommand: (command: string) => void;
  /** Created, but the install did not start: continue in its edit form. */
  onInstallFailed: (node: Schema<"AdminNode">, host: string, error: string) => void;
}) {
  const { t } = useI18n();
  const qc = useQueryClient();
  const [v, setV] = useState(nodeValues);
  const [ssh, setSsh] = useState(() => sshValues());
  const [pending, setPending] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const form = useRef<HTMLFormElement>(null);
  const first = useOpenFocus();
  const refresh = () => qc.invalidateQueries({ queryKey: keys.admin.nodes() });
  const forget = () => setSsh((x) => ({ ...x, password: "", privateKey: "" }));

  const createAndInstall = async () => {
    setError(null);
    let access: Schema<"NodeSshReq">;
    try {
      if (!ssh.host.trim()) throw new Error(t("Enter the server's address"));
      access = sshBody(ssh);
    } catch (e) {
      setError(message(e));
      return;
    }
    setPending(true);
    let created: Schema<"NodeInstallRes"> | null = null;
    try {
      created = await post("/admin/nodes", { body: nodeBody(v) });
      await post("/admin/nodes/{id}/install", { params: { id: created.node.id }, body: access });
      await refresh();
      onClose();
    } catch (e) {
      if (!created) setError(message(e));
      else {
        await refresh();
        onInstallFailed(created.node, access.host, message(e));
      }
    } finally {
      forget();
      setPending(false);
    }
  };

  const createByHand = async () => {
    if (form.current && !form.current.reportValidity()) return;
    setError(null);
    setPending(true);
    try {
      const r = await post("/admin/nodes", { body: nodeBody(v) });
      forget();
      await refresh();
      onCommand(r.command);
      onClose();
    } catch (e) {
      setError(message(e));
    } finally {
      setPending(false);
    }
  };

  return (
    <Card className="mt-4">
      <Form ref={form} onSubmit={() => void createAndInstall()} error={error} pending={pending} autoComplete="off">
        <CardHeader title={t("New node")} />
        <NodeFields v={v} onChange={setV} firstRef={first} />
        <h4 className="mb-3 mt-2 text-[15px]">{t("Server")}</h4>
        <SshFields v={ssh} onChange={setSsh} />
        <div className="flex flex-wrap gap-2">
          <Button type="submit">{t("Create and install")}</Button>
          <Button variant="secondary" onClick={onClose}>
            {t("Cancel")}
          </Button>
          <Button variant="secondary" onClick={() => void createByHand()}>
            {t("Create, I'll install by hand")}
          </Button>
        </div>
      </Form>
    </Card>
  );
}
