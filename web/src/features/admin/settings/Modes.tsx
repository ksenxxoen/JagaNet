/**
 * The test modes tab (modesPane() in app.js): sign-in codes on screen and test payments, each with
 * its warning, and a notice while one is on. Test payments can't be turned on while a real payment
 * service is connected: the box is off and locked, and saving always sends false then.
 */
import { useState } from "react";
import { keys } from "@/api/keys";
import { put, type Schema } from "@/api/typed";
import { SettingsForm } from "@/components/admin/SettingsForm";
import { Checkbox, Notice } from "@/components/ui";
import { useI18n } from "@/i18n";
import { WithSettings, type AdminSettings } from "./WithSettings";

/** The request: test payments only while no payment service is connected. */
export const modesBody = (codes: boolean, payments: boolean, paymentsConnected: boolean): Schema<"ModeSettings"> => ({
  showSignInCodes: codes,
  testPayments: payments && !paymentsConnected,
});

export default function Modes() {
  return <WithSettings>{(s) => <ModesPane s={s} />}</WithSettings>;
}

function ModesPane({ s }: { s: AdminSettings }) {
  const { t } = useI18n();
  const m = s.modes;
  const locked = s.paymentsConnected;
  const [codes, setCodes] = useState(m.showSignInCodes);
  const [payments, setPayments] = useState(m.testPayments && !locked);
  return (
    <>
      {m.showSignInCodes || m.testPayments ? <Notice tone="danger">{t("A test mode is on")}</Notice> : null}
      <SettingsForm
        title={t("Test modes")}
        className="mt-0"
        mutationFn={(body: Schema<"ModeSettings">) => put("/admin/settings/modes", { body })}
        build={() => modesBody(codes, payments, locked)}
        invalidate={[keys.admin.settings()]}
      >
        <Checkbox checked={codes} onChange={setCodes} hint={t("Anyone can sign in to any account")} hintTone="danger">
          {t("Show sign-in codes on screen")}
        </Checkbox>
        <Checkbox
          checked={payments && !locked}
          onChange={setPayments}
          disabled={locked}
          hint={locked ? t("A real payment service is connected, so test payments can't be turned on.") : t("Pro without paying")}
          hintTone={locked ? "muted" : "danger"}
        >
          {t("Test payments")}
        </Checkbox>
      </SettingsForm>
    </>
  );
}
