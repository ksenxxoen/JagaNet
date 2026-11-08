/**
 * #/signin (signin() in app.js): e-mail → 6-digit code, with the terms and the privacy policy
 * accepted first; the test mode shows the code on screen; the referral code goes with the
 * sign-up; Telegram (the bot's one-time link and, when on, the Login Widget).
 */
import { useQuery } from "@tanstack/react-query";
import { useNavigate, useSearch } from "@tanstack/react-router";
import { useCallback, useEffect, useRef, useState, type ReactNode } from "react";
import { siteQuery } from "@/api/queries";
import { post } from "@/api/typed";
import { buttonVariants, Button, CodeInput, Field, Form, FormError, Input, Notice, Spinner } from "@/components/ui";
import { useSaveForm } from "@/hooks/useSaveForm";
import { useI18n } from "@/i18n";
import { cn } from "@/lib/cn";
import { rich } from "@/lib/rich";
import { finishSignIn, referralCode } from "./finish";
import { TelegramWidget, type TelegramUser } from "./TelegramWidget";
import { AuthCard } from "./AuthCard";

const external = (href: string) => (s: string) => (
  <a href={href} target="_blank" rel="noopener">
    {s}
  </a>
);

export function SignIn() {
  const { t } = useI18n();
  const navigate = useNavigate();
  const search = useSearch({ strict: false }) as { ref?: string };
  const site = useQuery(siteQuery);
  const [step, setStep] = useState<"email" | "code">("email");
  const [email, setEmail] = useState("");
  const [code, setCode] = useState("");
  const [terms, setTerms] = useState(false);
  const [privacy, setPrivacy] = useState(false);
  const [tgError, setTgError] = useState<string | null>(null);
  const codeRef = useRef<HTMLInputElement>(null);

  const go = useCallback((href: string) => navigate({ href }), [navigate]);
  const device = { name: t("Website"), platform: "other" as const };

  const start = useSaveForm({
    mutationFn: (body: { email: string; referralCode: string | null }) => post("/auth/email/start", { body }),
    onSuccess: (r) => {
      setCode(r.devCode ?? "");
      setStep("code");
    },
  });
  const verify = useSaveForm({
    mutationFn: (body: { email: string; code: string }) => post("/auth/email/verify", { body: { ...body, device } }),
    onSuccess: (r) => finishSignIn(r.token, go),
  });

  useEffect(() => {
    if (step === "code") codeRef.current?.focus();
  }, [step]);

  const onTelegram = useCallback(
    async (auth: TelegramUser) => {
      setTgError(null);
      try {
        const ref = referralCode(search.ref);
        const r = await post("/auth/telegram", { body: { auth, ...(ref ? { referralCode: ref } : {}) } });
        await finishSignIn(r.token, go);
      } catch (e) {
        setTgError(e instanceof Error ? e.message : String(e));
      }
    },
    [go, search.ref],
  );

  if (site.isPending) return <Spinner />;
  const info = site.data;
  const bot = info?.telegramBotUrl;
  const accepted = terms && privacy;

  const viaTelegram: ReactNode = bot ? (
    <>
      <div>
        <a
          className={cn(buttonVariants({ variant: "secondary" }), "h-12 w-full")}
          href={`${bot}?start=login`}
          target="_blank"
          rel="noreferrer"
        >
          {t("Sign in with Telegram")}
        </a>
      </div>
      <TelegramWidget onAuth={(u) => void onTelegram(u)} />
      <FormError>{tgError}</FormError>
      <p className="mb-0 mt-4 text-center text-[13px] leading-[18px] text-muted">
        {rich(t("By continuing you accept the {terms}terms of use{end} and the {privacy}privacy policy{end}"), {
          terms: external("/terms"),
          privacy: external("/privacy"),
        })}
      </p>
    </>
  ) : null;

  if (info?.emailSignIn === false) {
    return (
      <AuthCard title={t("Sign in")}>
        <Notice tone="bad">{t("Sign-in by e-mail is temporarily unavailable")}</Notice>
        {viaTelegram}
      </AuthCard>
    );
  }

  return (
    <AuthCard title={t("Sign in")}>
      <p className="mb-4 text-muted">{t("Enter your email. We'll send a 6-digit code, no password needed.")}</p>
      {info?.testCodes ? (
        <p>
          <span className="inline-flex min-h-6 items-center rounded-full bg-warn-tint px-2.5 py-1 text-xs font-semibold leading-snug text-warn-text">
            {t("Test mode, sign-in codes are shown on screen")}
          </span>
        </p>
      ) : null}

      {step === "email" ? (
        <Form
          pending={start.pending}
          error={start.error}
          onSubmit={() =>
            void start.save(() => {
              if (!accepted) throw new Error(t("Accept the terms of use and the privacy policy to continue"));
              return { email: email.trim(), referralCode: referralCode(search.ref) };
            })
          }
        >
          <Field label={t("Email")} className="mt-5">
            {(id) => (
              <Input
                id={id}
                type="email"
                autoComplete="email"
                required
                placeholder="you@example.com"
                value={email}
                onChange={(e) => setEmail(e.target.value)}
              />
            )}
          </Field>
          <Check checked={terms} onChange={setTerms}>
            {rich(t("I accept the {a}terms of use{end}"), { a: external("/terms") })}
          </Check>
          <Check checked={privacy} onChange={setPrivacy}>
            {rich(t("I agree to the {a}privacy policy{end}"), { a: external("/privacy") })}
          </Check>
          <Button type="submit" className="mt-5 h-12 w-full" disabled={!accepted}>
            {t("Email me a code")}
          </Button>
        </Form>
      ) : (
        <Form
          pending={verify.pending}
          error={verify.error}
          onSubmit={() => void verify.save(() => ({ email: email.trim(), code: code.trim() }))}
        >
          <p className="text-muted">{t("Code sent to {email}", { email: email.trim() })}</p>
          <Field label={t("Code")} className="mt-5">
            {(id) => <CodeInput id={id} ref={codeRef} required value={code} onChange={setCode} />}
          </Field>
          <Button type="submit" className="mt-1 h-12 w-full">
            {t("Sign in")}
          </Button>
          <p className="mb-0 mt-3 text-center">
            <button
              type="button"
              className="text-link hover:underline"
              onClick={() => {
                verify.setError(null);
                setStep("email");
              }}
            >
              {t("Use another email")}
            </button>
          </p>
        </Form>
      )}

      {viaTelegram ? (
        <>
          <div className="my-6 flex items-center gap-3 text-[13px] text-faint before:flex-1 before:border-t before:border-line after:flex-1 after:border-t after:border-line">
            {t("or")}
          </div>
          {viaTelegram}
        </>
      ) : null}
    </AuthCard>
  );
}

function Check({ checked, onChange, children }: { checked: boolean; onChange: (v: boolean) => void; children: ReactNode }) {
  return (
    <label className="mt-3 flex cursor-pointer items-start gap-2.5 text-sm leading-5 text-ink">
      <input
        type="checkbox"
        required
        checked={checked}
        onChange={(e) => onChange(e.target.checked)}
        className="mt-0.5 size-4 flex-none accent-[var(--primary)]"
      />
      <span>{children}</span>
    </label>
  );
}
