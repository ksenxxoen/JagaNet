import type { ReactNode } from "react";
import { useI18n } from "@/i18n";
import { useSession } from "@/store/session";
import { anchors } from "./nav";

const Col = ({ title, links }: { title: string; links: ReactNode[] }) => (
  <div>
    <h4 className="mb-3 mt-0 text-sm">{title}</h4>
    <ul className="m-0 list-none space-y-2 p-0 text-sm">
      {links.filter(Boolean).map((l, i) => (
        <li key={i}>{l}</li>
      ))}
    </ul>
  </div>
);

const A = ({ href, children, external }: { href: string; children: ReactNode; external?: boolean }) => (
  <a href={href} className="text-muted hover:text-ink-heading" {...(external ? { target: "_blank", rel: "noopener" } : {})}>
    {children}
  </a>
);

/** Footer: the brand, the product links, the account and help with the legal pages (renderFooter() in app.js). */
export function Footer() {
  const { t } = useI18n();
  const signedIn = useSession((s) => !!s.token);
  const site = useSession((s) => s.site);
  const tg = site?.telegramBotUrl;
  return (
    <footer className="mt-auto border-t border-line bg-surface-alt">
      <div className="mx-auto grid max-w-page gap-8 px-4 py-10 sm:grid-cols-2 sm:px-6 lg:grid-cols-[2fr_1fr_1fr_1fr]">
        <div>
          <a
            href="#/"
            className="inline-flex items-center gap-2.5 text-lg font-extrabold tracking-tight text-ink-heading no-underline hover:no-underline"
          >
            <img src="/favicon.svg" alt="" width={28} height={28} className="size-7 rounded-lg" />
            Boop VPN
          </a>
          <p className="mt-3 max-w-xs text-sm text-muted">{t("A fast VPN with low ping that keeps your data safe.")}</p>
        </div>
        <Col
          title={t("Product")}
          links={[
            ...anchors().map((x) => (
              <A key={x.href} href={x.href}>
                {x.label}
              </A>
            )),
            <A key="download" href="#download">
              {t("Apps")}
            </A>,
          ]}
        />
        <Col
          title={t("Account")}
          links={[
            signedIn ? <A href="#/account">{t("My account")}</A> : <A href="#/signin">{t("Sign in")}</A>,
            signedIn && (site?.inviteRewardDays ?? 0) > 0 ? <A href="#/invite">{t("Invite a friend")}</A> : null,
            tg ? (
              <a href={tg} rel="noreferrer" className="text-muted hover:text-ink-heading">
                {t("Telegram bot")}
              </a>
            ) : null,
          ]}
        />
        <Col
          title={t("Help")}
          links={[
            <A href="#/support">{t("Contact support")}</A>,
            site?.communityUrl ? (
              <A href={site.communityUrl} external>
                {t("Our Telegram group")}
              </A>
            ) : null,
            <A href="/privacy" external>
              {t("Privacy policy")}
            </A>,
            <A href="/terms" external>
              {t("Terms of use")}
            </A>,
          ]}
        />
      </div>
      <div className="mx-auto flex max-w-page flex-wrap justify-between gap-2 border-t border-line px-4 py-4 text-xs text-faint sm:px-6">
        <span>© {new Date().getFullYear()} Boop VPN</span>
        <span>{t("We don't keep browsing history")}</span>
      </div>
    </footer>
  );
}
