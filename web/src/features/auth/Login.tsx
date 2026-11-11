/**
 * #/login?token=…: the one-time sign-in link from an e-mail or the Telegram
 * bot. The link is used once; afterwards the account opens in place of it, so the used token does
 * not stay in the browser history.
 */
import { useNavigate, useSearch } from "@tanstack/react-router";
import { useEffect, useRef, useState } from "react";
import { post } from "@/api/typed";
import { buttonVariants, Notice } from "@/components/ui";
import { useI18n } from "@/i18n";
import { cn } from "@/lib/cn";
import { useSession } from "@/store/session";
import { AuthCard } from "./AuthCard";

export function Login() {
  const { t } = useI18n();
  const navigate = useNavigate();
  const { token } = useSearch({ strict: false }) as { token?: string };
  const [error, setError] = useState<string | null>(null);
  // The link works once: StrictMode or a re-render must not redeem it twice.
  const started = useRef(false);

  useEffect(() => {
    if (started.current) return;
    started.current = true;
    void (async () => {
      try {
        if (!token) throw new Error(t("This sign-in link is incomplete. Ask for a new one."));
        const r = await post("/auth/link/redeem", { body: { token, device: { name: t("Website"), platform: "other" } } });
        useSession.getState().setToken(r.token);
        await navigate({ to: "/account", replace: true });
      } catch (e) {
        setError(e instanceof Error ? e.message : String(e));
      }
    })();
  }, [token, navigate, t]);

  if (!error)
    return (
      <AuthCard mark={false}>
        <p role="status" className="m-0 text-muted">
          {t("Signing in")}
        </p>
      </AuthCard>
    );
  return (
    <AuthCard mark={false} title={t("Sign in")}>
      <Notice tone="bad" className="mt-4">
        {error}
      </Notice>
      <a className={cn(buttonVariants(), "mt-1 h-12 w-full")} href="#/signin">
        {t("Back to sign in")}
      </a>
    </AuthCard>
  );
}
