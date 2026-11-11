/** The fields of a node, for the new and the edit form. */
import { Field, Input, Select } from "@/components/ui";
import { useI18n } from "@/i18n";
import { XHTTP_MODES, type NodeValues, type VlessValues } from "./nodeForm";

export function NodeFields({
  v,
  onChange,
  local,
  protocolLocked,
  firstRef,
}: {
  v: NodeValues;
  onChange: (v: NodeValues) => void;
  /** The main server: no protocol choice and no VLESS settings. */
  local?: boolean;
  /** An existing node keeps its protocol. */
  protocolLocked?: boolean;
  firstRef?: React.Ref<HTMLInputElement>;
}) {
  const { t } = useI18n();
  const set = (k: keyof NodeValues) => (e: { target: { value: string } }) => onChange({ ...v, [k]: e.target.value });
  const yesNo = (k: "active" | "inSubscription", yes: string, no: string) => (id: string) => (
    <Select id={id} value={v[k] ? "1" : "0"} onChange={(e) => onChange({ ...v, [k]: e.target.value === "1" })}>
      <option value="1">{yes}</option>
      <option value="0">{no}</option>
    </Select>
  );
  return (
    <>
      <div className="grid grid-cols-1 gap-x-4 sm:grid-cols-2 lg:grid-cols-3">
        <Field label={t("Name")}>
          {(id) => <Input id={id} ref={firstRef} required maxLength={60} placeholder="Germany 1" value={v.name} onChange={set("name")} />}
        </Field>
        <Field label={t("City")}>
          {(id) => <Input id={id} required maxLength={60} placeholder="Nuremberg" value={v.city} onChange={set("city")} />}
        </Field>
        <Field label={t("Country code")}>
          {(id) => <Input id={id} required maxLength={2} placeholder="DE" value={v.countryCode} onChange={set("countryCode")} />}
        </Field>
        <Field label={t("Devices at most")}>
          {(id) => <Input id={id} type="number" min={1} max={65000} step={1} required value={v.maxPeers} onChange={set("maxPeers")} />}
        </Field>
        <Field label={t("Host name")}>
          {(id) => <Input id={id} maxLength={200} placeholder="de1.vpn.example.com" value={v.hostname} onChange={set("hostname")} />}
        </Field>
        <Field label={t("Status")}>{yesNo("active", t("On"), t("Off"))}</Field>
        <Field label={t("Country name in the apps")}>
          {(id) => <Input id={id} maxLength={60} placeholder="Финляндия" value={v.countryName} onChange={set("countryName")} />}
        </Field>
        <Field label={t("Location name")}>
          {(id) => <Input id={id} maxLength={60} placeholder="Финляндия, Хельсинки" value={v.displayName} onChange={set("displayName")} />}
        </Field>
        <Field label={t("Order in the list")}>
          {(id) => <Input id={id} type="number" min={-100000} max={100000} step={1} value={v.sortOrder} onChange={set("sortOrder")} />}
        </Field>
        <Field label={t("In subscriptions")}>{yesNo("inSubscription", t("Yes"), t("No"))}</Field>
        <Field label={t("Cascade")}>
          {(id) => (
            <Select id={id} value={v.cascadeRole} onChange={set("cascadeRole")}>
              <option value="">{t("None")}</option>
              <option value="entry">{t("Entry")}</option>
              <option value="exit">{t("Exit")}</option>
            </Select>
          )}
        </Field>
        {local ? null : (
          <Field label={t("Protocol")}>
            {(id) => (
              <Select id={id} value={v.protocol} disabled={protocolLocked} onChange={set("protocol")}>
                <option value="amneziawg">AmneziaWG</option>
                <option value="vless">VLESS+Reality (Xray)</option>
              </Select>
            )}
          </Field>
        )}
      </div>
      {!local && v.protocol === "vless" ? <VlessFields v={v.vless} onChange={(vless) => onChange({ ...v, vless })} /> : null}
    </>
  );
}

function VlessFields({ v, onChange }: { v: VlessValues; onChange: (v: VlessValues) => void }) {
  const { t } = useI18n();
  const set = (k: keyof VlessValues) => (e: { target: { value: string } }) => onChange({ ...v, [k]: e.target.value });
  const port = { type: "number", min: 1, max: 65535, step: 1 } as const;
  return (
    <fieldset className="m-0 mb-2 min-w-0 border-0 p-0">
      <legend className="mb-1 p-0 text-[15px] font-bold text-ink-heading">VLESS+Reality</legend>
      <p className="mb-4 mt-0 text-muted">
        {t("Xray is set up on the machine by hand. Its Reality private key stays there, enter only the public key.")}
      </p>
      <div className="grid grid-cols-1 gap-x-4 sm:grid-cols-2 lg:grid-cols-3">
        <Field label={t("Address for the apps")}>
          {(id) => <Input id={id} maxLength={253} placeholder="ru1.vpn.example.com" value={v.host} onChange={set("host")} />}
        </Field>
        <Field label={t("Reality server name (SNI)")}>
          {(id) => <Input id={id} maxLength={253} placeholder="ru1.vpn.example.com" value={v.sni} onChange={set("sni")} />}
        </Field>
        <Field label={t("Reality public key")}>
          {(id) => (
            <Input id={id} maxLength={43} autoComplete="off" className="font-mono" value={v.publicKey} onChange={set("publicKey")} />
          )}
        </Field>
        <Field label={t("Short IDs, comma separated")}>
          {(id) => <Input id={id} className="font-mono" placeholder="a1b2c3d4" value={v.shortIds} onChange={set("shortIds")} />}
        </Field>
        <Field label={t("Fingerprint")}>
          {(id) => <Input id={id} maxLength={20} value={v.fingerprint} onChange={set("fingerprint")} />}
        </Field>
        <Field label={t("Vision port (TCP), empty for none")}>
          {(id) => <Input id={id} {...port} value={v.vision} onChange={set("vision")} />}
        </Field>
        <Field label={t("XHTTP port, empty for none")}>{(id) => <Input id={id} {...port} value={v.xhttp} onChange={set("xhttp")} />}</Field>
        <Field label={t("XHTTP path")}>
          {(id) => <Input id={id} className="font-mono" placeholder="/secret-path" value={v.xhttpPath} onChange={set("xhttpPath")} />}
        </Field>
        <Field label={t("XHTTP mode")}>
          {(id) => (
            <Select id={id} value={v.xhttpMode} onChange={set("xhttpMode")}>
              {XHTTP_MODES.map((m) => (
                <option key={m}>{m}</option>
              ))}
            </Select>
          )}
        </Field>
      </div>
    </fieldset>
  );
}
