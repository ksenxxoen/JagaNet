/**
 * Devices and keys: how many places the plan has, the devices counted in
 * it with rename and delete, and the subscription key outside the limit (it opens the connection
 * screen). A device key (from "New key" on the website) is deleted as a key.
 */
import { useQueries } from "@tanstack/react-query";
import { KeyRound, Laptop, Pencil, Smartphone, Trash2 } from "lucide-react";
import { useState } from "react";
import { deleteDevice, deviceChanges, devicesQuery, keysQuery, renameDevice } from "@/api/account";
import { meQuery } from "@/api/queries";
import type { Schema } from "@/api/typed";
import { Button, Input } from "@/components/ui";
import { useApiMutation } from "@/hooks/useApiMutation";
import { useI18n } from "@/i18n";
import { useSession } from "@/store/session";
import { ErrorCard, Group, Head, List, Meter, Panel, Row, RowIcon, Skeleton } from "../parts";
import { useMini } from "../state";
import { useTelegram, useTgConfirm } from "../TelegramProvider";

type Device = Schema<"Device">;

// "Ключ" is masculine, "устройство" neuter: the never-connected line agrees with whichever it is.
function Seen({ d, isKey }: { d: Device; isKey: boolean }) {
  const { t, date } = useI18n();
  if (d.online)
    return (
      <>
        <span aria-hidden="true" className="mr-1 inline-block size-2 rounded-full bg-green align-[1px]" />
        {t("online")}
      </>
    );
  if (d.lastSeenAt) return <>{t("last seen {date}", { date: date(d.lastSeenAt) })}</>;
  return <>{isKey ? t("key not connected yet") : t("not connected yet")}</>;
}

function DeviceRow({ d, isKey }: { d: Device; isKey: boolean }) {
  const { t } = useI18n();
  const { haptic } = useTelegram();
  const token = useSession((s) => s.token);
  const { confirm, dialog } = useTgConfirm();
  const [editing, setEditing] = useState(false);
  const [name, setName] = useState(d.name);
  const changed = deviceChanges(token);
  const rename = useApiMutation({
    mutationFn: (value: string) => renameDevice(d.id, value),
    invalidate: changed,
    success: t("Saved"),
    onSuccess: () => {
      haptic.ok();
      setEditing(false);
    },
    onError: () => haptic.err(),
  });
  const remove = useApiMutation({
    mutationFn: () => deleteDevice(d.id, isKey),
    invalidate: changed,
    success: t("Device deleted."),
    onSuccess: () => haptic.ok(),
    onError: () => haptic.err(),
  });
  const close = () => {
    setEditing(false);
    setName(d.name);
  };
  const ask = async () => {
    const text = isKey
      ? t("Delete this key? Apps using it will stop connecting.")
      : t("Delete {name}? It stops connecting.", { name: d.name });
    if (await confirm(text, t("Delete"))) remove.mutate();
  };
  const Icon = isKey ? KeyRound : d.platform === "desktop" ? Laptop : Smartphone;
  return (
    <div className="flex min-h-[56px] items-center gap-3 px-3.5 py-2.5">
      <RowIcon icon={Icon} tone={d.online ? "green" : "gray"} />
      <div className="min-w-0 flex-1">
        {editing ? (
          <form
            className="flex flex-wrap gap-2"
            onSubmit={(e) => {
              e.preventDefault();
              rename.mutate(name.trim());
            }}
          >
            <Input
              // The field opens on "Rename" to be typed in at once, as the old form did.
              // eslint-disable-next-line jsx-a11y/no-autofocus
              autoFocus
              onFocus={(e) => e.currentTarget.select()}
              maxLength={60}
              required
              value={name}
              aria-label={t("Name")}
              className="min-w-0 flex-1 basis-40"
              onChange={(e) => setName(e.target.value)}
              onKeyDown={(e) => {
                if (e.key === "Escape") close();
              }}
            />
            <Button type="submit" size="sm" disabled={rename.isPending}>
              {t("Save")}
            </Button>
            <Button size="sm" variant="ghost" onClick={close}>
              {t("Cancel")}
            </Button>
          </form>
        ) : (
          <span className="block truncate font-semibold text-ink-heading">{d.name}</span>
        )}
        <span className="block text-[13px] text-muted">
          {isKey ? `${t("Key for other apps")}, ` : null}
          <Seen d={d} isKey={isKey} />
        </span>
      </div>
      {editing ? null : (
        <span className="flex flex-none gap-1">
          <Button variant="ghost" size="sm" className="size-9 px-0" aria-label={t("Rename")} onClick={() => setEditing(true)}>
            <Pencil aria-hidden="true" />
          </Button>
          <Button
            variant="ghost"
            size="sm"
            className="size-9 px-0 text-danger-text hover:bg-danger-tint"
            aria-label={t("Delete")}
            disabled={remove.isPending}
            onClick={() => void ask()}
          >
            <Trash2 aria-hidden="true" />
          </Button>
        </span>
      )}
      {dialog}
    </div>
  );
}

export function Devices() {
  const { t } = useI18n();
  const go = useMini((s) => s.go);
  const token = useSession((s) => s.token);
  const all = useQueries({ queries: [meQuery(token), devicesQuery, keysQuery] });
  const [me, list, keyList] = all;
  const failed = all.find((q) => q.isError);
  if (failed) return <ErrorCard error={failed.error} onRetry={() => all.forEach((q) => void q.refetch())} />;
  if (!me.data || !list.data || !keyList.data) return <Skeleton />;

  const keyIds = new Set(keyList.data.keys.map((k) => k.id));
  const mine = list.data.devices.filter((d) => !d.outsideLimit && d.protocol);
  const used = me.data.usage.devicesUsed;
  const limit = me.data.entitlement.deviceLimit;
  const v = keyList.data.vless;
  const vSub = (
    <>
      <span className="mr-1 inline-flex h-5 items-center rounded-full bg-neutral-tint px-2 text-[11px] font-semibold text-neutral-text">
        {t("outside the limit")}
      </span>
      {v && !v.active ? t("This key is off until access is renewed") : null}
    </>
  );

  return (
    <>
      <Head title={t("Devices and keys")} />
      <Panel>
        <Meter label={t("Devices in the plan")} used={used} limit={limit} />
        <p className="m-0 mt-2 text-[13px] text-muted">{t("Delete a device to free a place.")}</p>
      </Panel>
      <Group title={t("Connected devices")}>
        {mine.length ? (
          <List>
            {mine.map((d) => (
              <DeviceRow key={d.id} d={d} isKey={keyIds.has(d.id)} />
            ))}
          </List>
        ) : (
          <Panel>
            <p className="m-0 text-muted">{t("No devices connected yet.")}</p>
          </Panel>
        )}
      </Group>
      {v ? (
        <Group title={t("Subscription key")}>
          <List>
            <Row icon={KeyRound} tone="pink" title={v.name} sub={vSub} onClick={v.active ? () => go("connect") : undefined} />
          </List>
          <p className="m-0 mt-2 px-1 text-[13px] text-muted">
            {t("For Happ, v2RayTun, Hiddify and v2rayNG")}. {t("It comes with your access and can't be deleted.")}
          </p>
        </Group>
      ) : null}
    </>
  );
}
