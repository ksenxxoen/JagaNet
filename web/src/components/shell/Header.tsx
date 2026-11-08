import { Menu as MenuIcon } from "lucide-react";
import { useState } from "react";
import { useRouterState } from "@tanstack/react-router";
import { buttonVariants } from "@/components/ui";
import { useI18n } from "@/i18n";
import { cn } from "@/lib/cn";
import { useSession } from "@/store/session";
import { LangSwitch } from "./LangSwitch";
import { navItems, type NavItem } from "./nav";

/** Top bar: page links, the language switch and, on phones, a menu button with the same links. */
export function Header() {
  const { t } = useI18n();
  const token = useSession((s) => s.token);
  const role = useSession((s) => s.role);
  const site = useSession((s) => s.site);
  const here = useRouterState({ select: (s) => s.location.pathname });
  const [open, setOpen] = useState(false);
  const items = navItems(!!token, role, site);

  const link = (x: NavItem, inMenu: boolean) => {
    const on = x.path === here;
    return (
      <a
        key={x.href}
        href={x.href}
        aria-current={on ? "page" : undefined}
        onClick={inMenu ? () => setOpen(false) : undefined}
        className={cn(
          "rounded-lg px-3 py-2 text-sm font-semibold no-underline hover:bg-subtle hover:no-underline",
          on ? "text-ink-heading" : "text-muted hover:text-ink-heading",
          inMenu && "block py-3 text-[15px]",
        )}
      >
        {x.label}
      </a>
    );
  };

  return (
    <header className="sticky top-0 z-50 border-b border-line bg-paper/90 backdrop-blur">
      <div className="mx-auto flex h-16 max-w-page items-center gap-4 px-4 sm:px-6">
        <a
          href="#/"
          aria-label="Boop VPN"
          className="inline-flex items-center gap-2.5 text-lg font-extrabold tracking-tight text-ink-heading no-underline hover:no-underline"
        >
          <img src="/favicon.svg" alt="" width={28} height={28} className="size-7 rounded-lg" />
          Boop
        </a>
        <nav aria-label={t("Menu")} className="ml-auto flex items-center gap-1">
          <span className="hidden items-center gap-1 lg:flex">{items.map((x) => link(x, false))}</span>
          <LangSwitch className="ml-2 hidden sm:inline-flex" />
          {!token && (
            <>
              <a href="#/signin" className={cn(buttonVariants({ variant: "ghost" }), "hidden sm:inline-flex")}>
                {t("Sign in")}
              </a>
              <a href="#pricing" className={cn(buttonVariants(), "hidden sm:inline-flex")}>
                {t("Choose your plan")}
              </a>
            </>
          )}
          <button
            type="button"
            aria-label={t("Menu")}
            aria-expanded={open}
            aria-controls="menu"
            onClick={() => setOpen((v) => !v)}
            className={cn(buttonVariants({ variant: "secondary" }), "w-10 px-0 lg:hidden")}
          >
            <MenuIcon aria-hidden="true" />
          </button>
        </nav>
      </div>
      <nav id="menu" aria-label={t("Menu")} hidden={!open} className="border-t border-line bg-surface px-4 pb-4 pt-2 lg:hidden">
        {items.map((x) => link(x, true))}
        {!token && link({ href: "#/signin", label: t("Sign in") }, true)}
        <div className="pt-3">
          <LangSwitch />
        </div>
      </nav>
    </header>
  );
}
