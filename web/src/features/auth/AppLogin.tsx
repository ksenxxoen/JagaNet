/**
 * #/app-login?id=…: the Boop app on a computer asks to be signed in to this
 * account. The screen shows the app's device name and the code the app shows; "Allow" signs it in.
 * Needs a session (the route guard opens sign-in and comes back here). The app itself polls the
 * server, this page does not need to.
 */
import { useQuery } from "@tanstack/react-query";
import { useSearch } from "@tanstack/react-router";
import { keys } from "@/api/keys";
import { get, post } from "@/api/typed";
import { Button, ErrorState, Notice, Spinner } from "@/components/ui";
import { useApiMutation } from "@/hooks/useApiMutation";
import { t as tr, useI18n } from "@/i18n";
import { AuthCard } from "./AuthCard";

export function AppLogin() {
  const { t } = useI18n();
  const { id = "" } = useSearch({ strict: false }) as { id?: string };
  const info = useQuery({
    queryKey: keys.auth.appLogin(id),
    queryFn: () => (id ? get("/auth/app/{id}", { params: { id } }) : Promise.reject(new Error(tr("Something went wrong")))),
    retry: false,
    staleTime: Infinity,
  });
  const allow = useApiMutation({ mutationFn: () => post("/auth/app/{id}/allow", { params: { id } }) });

  if (info.isPending) return <Spinner />;
  if (info.isError) return <ErrorState error={info.error} />;
  return (
    <AuthCard mark={false} center title={t("Sign in to the Boop app")}>
      <p className="text-muted">{info.data.deviceName}</p>
      <div className="mb-5 mt-4 font-mono text-[30px] font-bold leading-[38px] tracking-[0.2em] text-ink-heading">{info.data.code}</div>
      {allow.isSuccess ? (
        <Notice tone="ok" className="text-left">
          {t("Signed in. Go back to the app.")}
        </Notice>
      ) : (
        <>
          <Button className="h-12 w-full" disabled={allow.isPending} onClick={() => allow.mutate()}>
            {t("Allow")}
          </Button>
          <p className="mb-0 mt-3">
            <a href="#/account">{t("Cancel")}</a>
          </p>
        </>
      )}
    </AuthCard>
  );
}
