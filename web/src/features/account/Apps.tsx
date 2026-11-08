/** "Apps": the app cards (shared with the landing page), a code to sign in to the Boop app, the Telegram group. */
import { Plane, Shield, Smartphone, TabletSmartphone } from "lucide-react";
import { useState } from "react";
import { post } from "@/api/typed";
import { Section } from "@/components/layout";
import { Button, buttonVariants, Card, CardHeader } from "@/components/ui";
import { useApiMutation } from "@/hooks/useApiMutation";
import { useI18n } from "@/i18n";
import { appCards, type AppCard } from "@/lib/apps";
import type { SiteInfo } from "@/store/session";

const ICONS: Record<AppCard["icon"], typeof Plane> = { android: TabletSmartphone, phone: Smartphone, plane: Plane, shield: Shield };

function PairCode() {
  const { t } = useI18n();
  const [code, setCode] = useState<string | null>(null);
  const make = useApiMutation({ mutationFn: () => post("/devices/pairing-code"), onSuccess: (r) => setCode(r.code) });
  return code ? (
    <div className="font-mono text-[30px] font-bold leading-[38px] tracking-[0.2em] text-ink-heading" title={t("Works for 10 minutes")}>
      {code}
    </div>
  ) : (
    <Button variant="secondary" disabled={make.isPending} onClick={() => make.mutate()}>
      {t("Show a code")}
    </Button>
  );
}

export function AppsSection({ site }: { site: Partial<SiteInfo> }) {
  const { t } = useI18n();
  return (
    <Section title={t("Apps")} className="mt-10">
      <div className="grid gap-4 sm:grid-cols-3">
        {appCards(site).map((c) => {
          const Icon = ICONS[c.icon];
          return (
            <Card key={c.title} className="flex flex-col items-start gap-2">
              <span aria-hidden="true" className="grid size-10 place-items-center rounded-xl bg-primary-tint text-link">
                <Icon className="size-5" />
              </span>
              <h3 className="m-0 text-[17px]">{c.title}</h3>
              <p className="m-0 flex-1 text-muted">{c.text}</p>
              {c.href ? (
                <a
                  className={buttonVariants({ variant: "secondary", className: "w-full" })}
                  href={c.href}
                  {...(c.external ? { rel: "noreferrer" } : {})}
                >
                  {c.label}
                </a>
              ) : (
                <Button variant="secondary" className="w-full" disabled>
                  {c.label}
                </Button>
              )}
            </Card>
          );
        })}
      </div>
      <Card className="mt-4">
        <CardHeader title={t("Sign in to the Boop app")} actions={<PairCode />} className="mb-0 flex-wrap" />
      </Card>
    </Section>
  );
}

export function CommunityCard({ url }: { url: string }) {
  const { t } = useI18n();
  return (
    <Card className="mt-4">
      <CardHeader
        title={t("Our Telegram group")}
        actions={
          <a className={buttonVariants({ variant: "secondary" })} href={url} target="_blank" rel="noopener">
            {t("Open")}
          </a>
        }
      />
      <p className="m-0 text-muted">{t("Chat with other Boop users and follow the news in our Telegram group.")}</p>
    </Card>
  );
}
