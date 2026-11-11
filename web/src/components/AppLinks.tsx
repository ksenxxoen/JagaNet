/**
 * "Add to Happ", "Add to v2RayTun": one tap hands the subscription to the app, with links to get the apps. The website opens the import link itself; the mini app
 * passes `open` that goes through /tg/open (Telegram opens only web links outside).
 */
import { ChevronRight, Zap } from "lucide-react";
import { useI18n } from "@/i18n";
import { cn } from "@/lib/cn";
import { IMPORT_APPS, isImportLink } from "@/lib/links";

const openHere = (link: string) => window.location.assign(link);

export function AppLinks({
  subscriptionUrl,
  open = openHere,
  openSite,
  className,
}: {
  subscriptionUrl: string;
  /** Opens an import link (only links that pass isImportLink() get here). */
  open?: (link: string) => void;
  /** Opens an app's website; plain links when not given. */
  openSite?: (url: string) => void;
  className?: string;
}) {
  const { t } = useI18n();
  const apps = IMPORT_APPS.map((a) => ({ ...a, href: a.link(subscriptionUrl) })).filter((a) => isImportLink(a.href));
  if (!apps.length) return null;
  return (
    <div className={cn("w-full", className)}>
      <ul className="m-0 grid list-none gap-2 p-0">
        {apps.map((a) => (
          <li key={a.name}>
            <button
              type="button"
              onClick={() => open(a.href)}
              className="flex w-full items-center gap-3 rounded-ctl border border-line bg-surface px-3 py-2.5 text-left hover:bg-hover"
            >
              <span aria-hidden="true" className="grid size-8 flex-none place-items-center rounded-lg bg-primary-tint text-link">
                <Zap className="size-4" />
              </span>
              <span className="min-w-0 flex-1">
                <span className="block font-semibold text-ink-heading">{t("Add to {app}", { app: a.name })}</span>
                <span className="block text-[13px] text-muted">{t("Opens the app with your subscription")}</span>
              </span>
              <ChevronRight aria-hidden="true" className="size-4 flex-none text-faint" />
            </button>
          </li>
        ))}
      </ul>
      <p className="mb-0 mt-2 text-[13px] text-muted">
        {t("No app yet?")}{" "}
        {apps.map((a, i) => (
          <span key={a.name}>
            {i > 0 ? ", " : null}
            {openSite ? (
              <button type="button" className="text-link underline-offset-2 hover:underline" onClick={() => openSite(a.site)}>
                {a.name}
              </button>
            ) : (
              <a href={a.site} target="_blank" rel="noreferrer">
                {a.name}
              </a>
            )}
          </span>
        ))}
      </p>
    </div>
  );
}
