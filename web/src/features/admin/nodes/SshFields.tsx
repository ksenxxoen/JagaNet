/**
 * SSH access to a fresh server. The values live only in the form's state:
 * never in the address, storage or the query cache, and the form empties the password and the key
 * after sending.
 */
import { Field, Input, Textarea } from "@/components/ui";
import { useI18n } from "@/i18n";
import type { SshValues } from "./nodeForm";

export function SshFields({
  v,
  onChange,
  firstRef,
}: {
  v: SshValues;
  onChange: (v: SshValues) => void;
  firstRef?: React.Ref<HTMLInputElement>;
}) {
  const { t } = useI18n();
  const set = (k: keyof SshValues) => (e: { target: { value: string } }) => onChange({ ...v, [k]: e.target.value });
  return (
    <>
      <div className="grid grid-cols-1 gap-x-4 sm:grid-cols-2 lg:grid-cols-4">
        <Field label={t("Server address")}>
          {(id) => <Input id={id} autoComplete="off" placeholder="203.0.113.10" value={v.host} onChange={set("host")} />}
        </Field>
        <Field label={t("SSH port")}>
          {(id) => <Input id={id} type="number" min={1} max={65535} step={1} value={v.port} onChange={set("port")} />}
        </Field>
        <Field label={t("User")}>{(id) => <Input id={id} autoComplete="off" value={v.user} onChange={set("user")} />}</Field>
        <Field label={t("Password")}>
          {(id) => (
            <Input id={id} ref={firstRef} type="password" autoComplete="new-password" value={v.password} onChange={set("password")} />
          )}
        </Field>
      </div>
      <Field label={t("Private key, instead of the password")} hint={t("The password is not saved")}>
        {(id) => (
          <Textarea
            id={id}
            rows={3}
            className="font-mono"
            autoComplete="off"
            spellCheck={false}
            placeholder="-----BEGIN OPENSSH PRIVATE KEY-----"
            value={v.privateKey}
            onChange={set("privateKey")}
          />
        )}
      </Field>
    </>
  );
}
