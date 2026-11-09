/**
 * "VPN keys": the VLESS key (QR of the subscription link drawn here, copy, one-tap import into
 * Happ / v2RayTun, its locations) and the device keys (server QR, file, key page, rename, delete).
 * A device key is the device: renaming goes to /devices/{id}, deleting to /keys/{id}.
 */
import { useState } from "react";
import { keys } from "@/api/keys";
import { deleteDevice, deviceChanges, renameDevice } from "@/api/account";
import { post, type Schema } from "@/api/typed";
import { AppLinks } from "@/components/AppLinks";
import { QrCanvas } from "@/components/qr/QrCanvas";
import { Button, buttonVariants, Card, CopyButton, Form, Input, Notice } from "@/components/ui";
import { Section } from "@/components/layout";
import { useApiMutation } from "@/hooks/useApiMutation";
import { useConfirm } from "@/hooks/useConfirm";
import { useSaveForm } from "@/hooks/useSaveForm";
import { useI18n } from "@/i18n";
import { cn } from "@/lib/cn";
import { vlessLocations, withLang } from "@/lib/links";
import { useSession } from "@/store/session";

type KeysRes = Schema<"KeysRes">;
type AccessKey = Schema<"AccessKey">;
type VlessKey = Schema<"VlessKey">;

const keyCard =
  "grid items-center justify-items-center gap-7 text-center sm:grid-cols-[168px_minmax(0,1fr)] sm:justify-items-stretch sm:text-left";
const titleRow = "m-0 mb-1 flex flex-wrap items-center justify-center gap-2 text-lg sm:justify-start";
const actions = "mt-3 flex w-full flex-wrap justify-center gap-2 sm:justify-start [&>*]:flex-[1_1_100%] sm:[&>*]:flex-none";

function Tag({ tone, children }: { tone: "stable" | "fast"; children: string }) {
  return (
    <span
      className={cn(
        "inline-flex h-6 items-center gap-1.5 rounded-full px-2.5 text-xs font-semibold leading-none",
        tone === "stable" ? "bg-green-tint text-green-text" : "bg-accent-tint text-accent",
      )}
    >
      <span aria-hidden="true" className="size-1.5 rounded-full bg-current" />
      {children}
    </span>
  );
}

export function VlessCard({ v }: { v: VlessKey }) {
  const { t } = useI18n();
  const ready = v.active && v.links.length > 0;
  const locations = vlessLocations(v.links);
  return (
    <Card className={keyCard}>
      {v.active ? <QrCanvas text={v.subscriptionUrl} label={t("QR code of your VPN key")} /> : null}
      <div className={cn("w-full min-w-0", !v.active && "sm:col-span-2")}>
        <h3 className={titleRow}>
          {v.name} <Tag tone="stable">{t("Stable connection")}</Tag>
        </h3>
        <p className="m-0 text-muted">{t("Not counted in the device limit")}</p>
        {v.active ? null : (
          <Notice tone="bad" className="mb-0 mt-3">
            {t("This key is off until access is renewed")}
          </Notice>
        )}
        {ready ? (
          <>
            {locations.length ? <p className="mb-0 mt-1 text-[13px] text-muted">{locations.join(", ")}</p> : null}
            <div className={actions}>
              <CopyButton text={v.subscriptionUrl} label={t("Copy subscription link")} />
            </div>
            <AppLinks subscriptionUrl={v.subscriptionUrl} className="mt-4 max-w-md text-left" />
          </>
        ) : null}
      </div>
    </Card>
  );
}

function RenameForm({ k, onDone }: { k: AccessKey; onDone: () => void }) {
  const { t } = useI18n();
  const [name, setName] = useState(k.name);
  const form = useSaveForm({
    mutationFn: (body: { name: string }) => renameDevice(k.id, body.name),
    invalidate: [keys.account.keys()],
    onSuccess: () => onDone(),
  });
  return (
    <Form onSubmit={() => void form.save(() => ({ name: name.trim() }))} error={form.error} pending={form.pending} className="mb-1 w-full">
      <div className="flex gap-2">
        <Input
          // The field opens on "Rename" to be typed in at once, as the old form did.
          // eslint-disable-next-line jsx-a11y/no-autofocus
          autoFocus
          onFocus={(e) => e.currentTarget.select()}
          maxLength={60}
          required
          value={name}
          aria-label={t("Name")}
          className="max-w-[320px] flex-1"
          onChange={(e) => setName(e.target.value)}
          onKeyDown={(e) => {
            if (e.key === "Escape") onDone();
          }}
        />
        <Button type="submit">{t("Save")}</Button>
      </div>
    </Form>
  );
}

export function KeyCard({ k, onDelete }: { k: AccessKey; onDelete: (k: AccessKey) => void }) {
  const { t, date, lang } = useI18n();
  const [renaming, setRenaming] = useState(false);
  return (
    <Card className={keyCard}>
      <img
        className="block size-[168px] rounded-[14px] border border-line bg-white p-2 [image-rendering:pixelated]"
        src={k.qrUrl}
        alt={t("QR code of your VPN key")}
        loading="lazy"
        width={168}
        height={168}
      />
      <div className="w-full min-w-0">
        {renaming ? (
          <RenameForm k={k} onDone={() => setRenaming(false)} />
        ) : (
          <h3 className={titleRow}>
            {k.name} <Tag tone="fast">{t("High speed")}</Tag>
          </h3>
        )}
        <p className="m-0 text-muted">
          {k.location}, {date(k.createdAt)}
        </p>
        <div className={actions}>
          <a className={buttonVariants()} href={k.configUrl} download>
            {t("Download file")}
          </a>
          <a className={buttonVariants({ variant: "secondary" })} href={withLang(k.pageUrl, lang)} target="_blank" rel="noreferrer">
            {t("Open key link")}
          </a>
          <Button variant="secondary" onClick={() => setRenaming(true)}>
            {t("Rename")}
          </Button>
          <Button variant="danger" onClick={() => onDelete(k)}>
            {t("Delete")}
          </Button>
        </div>
      </div>
    </Card>
  );
}

export function KeysSection({ data, isPro }: { data: KeysRes; isPro: boolean }) {
  const { t } = useI18n();
  const token = useSession((s) => s.token);
  const { confirm, dialog } = useConfirm();
  const changed = deviceChanges(token);
  const create = useApiMutation({ mutationFn: () => post("/keys"), invalidate: changed });
  const remove = useApiMutation({ mutationFn: (id: string) => deleteDevice(id, true), invalidate: changed });

  const onDelete = async (k: AccessKey) => {
    const yes = await confirm({ title: t("Delete this key? Apps using it will stop connecting."), confirm: t("Delete"), danger: true });
    if (yes) remove.mutate(k.id);
  };

  return (
    <Section
      title={t("VPN keys")}
      className="mt-10"
      actions={
        isPro || data.keys.length ? (
          <Button variant="secondary" disabled={create.isPending} onClick={() => create.mutate()}>
            {t("New key")}
          </Button>
        ) : null
      }
    >
      <div className="grid gap-4">
        {data.vless ? <VlessCard v={data.vless} /> : null}
        {data.keys.map((k) => (
          <KeyCard key={k.id} k={k} onDelete={(x) => void onDelete(x)} />
        ))}
        {!data.keys.length && !data.vless ? <Card className="text-muted">{isPro ? t("No keys yet") : t("Keys come with Pro")}</Card> : null}
      </div>
      {dialog}
    </Section>
  );
}
