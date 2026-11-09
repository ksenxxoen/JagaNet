/**
 * Connection (connect() in tg/app.js): the VLESS subscription with its QR code (drawn on canvas,
 * as on the website), the link to copy, one-tap import into Happ / v2RayTun through /tg/open, the
 * Boop apps, the key page. Without a VLESS key: the device keys with their QR codes; without any
 * key: "No key yet" and the way to buy.
 */
import { useQueries, useQuery } from "@tanstack/react-query";
import { Download, ExternalLink, KeyRound, Lock } from "lucide-react";
import { desktopQuery, keysQuery } from "@/api/account";
import { siteQuery } from "@/api/queries";
import type { Schema } from "@/api/typed";
import { AppLinks } from "@/components/AppLinks";
import { QrCanvas } from "@/components/qr/QrCanvas";
import { Button, CopyButton, Input } from "@/components/ui";
import { useI18n } from "@/i18n";
import { withLang } from "@/lib/links";
import { ErrorCard, Group, Head, List, Panel, Row, Skeleton, StateCard } from "../parts";
import { useMini } from "../state";
import { useTelegram } from "../TelegramProvider";

/** The path of a server URL, so pictures load from this page's origin (img-src 'self'). */
export const pathOf = (url: string) => {
  try {
    const u = new URL(url, window.location.href);
    return u.pathname + u.search;
  } catch {
    return url;
  }
};

function BoopApps() {
  const { t } = useI18n();
  const { openLink } = useTelegram();
  const site = useQuery(siteQuery).data;
  const windows = useQuery(desktopQuery).data;
  const apps = [
    site?.androidAppUrl ? ["Android", site.androidAppUrl] : null,
    windows ? ["Windows", "/download/windows"] : null,
    site?.iosAppUrl ? ["iPhone", site.iosAppUrl] : null,
  ].filter((x): x is [string, string] => !!x);
  if (!apps.length) return null;
  return (
    <Group title={t("The Boop app")}>
      <Panel>
        <p className="m-0 text-muted">{t("Our own app, connects in one tap")}</p>
        <div className="mt-3 flex flex-wrap gap-2">
          {apps.map(([name, url]) => (
            <Button key={name} variant="secondary" onClick={() => openLink(url)}>
              <Download aria-hidden="true" />
              {name}
            </Button>
          ))}
        </div>
      </Panel>
    </Group>
  );
}

function Subscription({ v }: { v: Schema<"VlessKey"> }) {
  const { t, lang } = useI18n();
  const { openApp, openLink } = useTelegram();
  const sub = v.subscriptionUrl;
  return (
    <>
      <Panel className="flex flex-col items-center gap-3">
        <QrCanvas text={sub} label={t("QR code of your VPN key")} size={220} />
        <p className="m-0 text-center text-muted">{t("Scan it in the app from another phone")}</p>
        <div className="w-full">
          <label className="mb-1.5 block text-[13px] font-semibold text-ink-heading" htmlFor="sublink">
            {t("Subscription link")}
          </label>
          <Input id="sublink" readOnly value={sub} className="font-mono text-[13px]" onFocus={(e) => e.currentTarget.select()} />
        </div>
        <CopyButton text={sub} label={t("Copy subscription link")} className="w-full" />
      </Panel>
      <Group title={t("Add in one tap")}>
        <AppLinks subscriptionUrl={sub} open={openApp} openSite={openLink} />
      </Group>
      <BoopApps />
      <Group>
        <List>
          <Row
            icon={ExternalLink}
            title={t("Open key page")}
            sub={t("Links, QR codes and steps")}
            onClick={() => openLink(withLang(v.pageUrl, lang))}
          />
        </List>
        <p className="m-0 mt-2 flex items-start gap-1.5 px-1 text-[13px] text-warn-text">
          <Lock aria-hidden="true" className="mt-0.5 size-4 flex-none" />
          {t("Keep the link private. Anyone who has it uses your subscription.")}
        </p>
      </Group>
    </>
  );
}

function DeviceKey({ k }: { k: Schema<"AccessKey"> }) {
  const { t, lang } = useI18n();
  const { openLink } = useTelegram();
  return (
    <Panel className="flex flex-col items-center gap-2 text-center">
      <h3 className="m-0 text-lg">{k.name}</h3>
      <p className="m-0 text-muted">{k.location}</p>
      <img
        src={pathOf(k.qrUrl)}
        alt={t("QR code of your VPN key")}
        width={220}
        height={220}
        className="mt-1 block size-[220px] rounded-[14px] border border-line bg-white p-2 [image-rendering:pixelated]"
      />
      <Button className="mt-2" onClick={() => openLink(withLang(k.pageUrl, lang))}>
        <ExternalLink aria-hidden="true" />
        {t("Open key page")}
      </Button>
    </Panel>
  );
}

export function Connect() {
  const { t } = useI18n();
  const go = useMini((s) => s.go);
  const [keyList] = useQueries({ queries: [keysQuery] });
  if (keyList.isError) return <ErrorCard error={keyList.error} onRetry={() => void keyList.refetch()} />;
  if (!keyList.data) return <Skeleton />;
  const v = keyList.data.vless;
  let body: React.ReactNode;
  if (v && v.active && v.links.length) body = <Subscription v={v} />;
  else if (keyList.data.keys.length)
    body = (
      <>
        <div className="grid gap-3">
          {keyList.data.keys.map((k) => (
            <DeviceKey key={k.id} k={k} />
          ))}
        </div>
        <BoopApps />
      </>
    );
  else
    body = (
      <StateCard icon={KeyRound} title={t("No key yet")} actions={<Button onClick={() => go("pay")}>{t("Buy a subscription")}</Button>}>
        <p className="m-0 text-muted">{t("Keys come with a subscription")}</p>
      </StateCard>
    );
  return (
    <>
      <Head title={t("Connection")} />
      {body}
    </>
  );
}
