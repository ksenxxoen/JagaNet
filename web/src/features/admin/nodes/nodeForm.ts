/**
 * The node form as data: the fields' values from a node, and the request from the values
 * (nodeFields(), nodeBody(), vlessBody(), sshFields(), sshBody() in app.js), kept apart from the
 * screen so the request is the same as the old site's and can be tested on its own.
 */
import type { Schema } from "@/api/typed";
import { t } from "@/i18n";

type Node = Schema<"AdminNode">;
export type NodeReq = Schema<"NodeReq">;
type Vless = Schema<"VlessNodeSettings">;

export const XHTTP_MODES = ["auto", "packet-up", "stream-up", "stream-one"] as const;
export type CascadeRole = "" | "entry" | "exit";

export interface VlessValues {
  host: string;
  sni: string;
  publicKey: string;
  shortIds: string;
  fingerprint: string;
  /** Vision (TCP) port; empty for none. */
  vision: string;
  /** XHTTP port; empty for none. */
  xhttp: string;
  xhttpPath: string;
  xhttpMode: string;
}

export interface NodeValues {
  name: string;
  city: string;
  countryCode: string;
  maxPeers: string;
  hostname: string;
  active: boolean;
  countryName: string;
  displayName: string;
  sortOrder: string;
  inSubscription: boolean;
  cascadeRole: CascadeRole;
  protocol: "amneziawg" | "vless";
  vless: VlessValues;
}

const transportsOf = (x: Vless | undefined) => {
  const tr = x?.transports ?? [];
  return { vis: tr.find((t) => t.type === "tcp"), xh: tr.find((t) => t.type === "xhttp") };
};

export function vlessValues(x: Vless | undefined): VlessValues {
  const { vis, xh } = transportsOf(x);
  return {
    host: x?.host ?? "",
    sni: x?.sni ?? "",
    publicKey: x?.publicKey ?? "",
    shortIds: (x?.shortIds ?? []).join(","),
    fingerprint: x?.fingerprint || "firefox",
    vision: String(vis?.port ?? 443),
    xhttp: String(xh?.port ?? 8443),
    xhttpPath: xh?.path ?? "",
    xhttpMode: xh?.mode || "auto",
  };
}

/** The form's values: a node's, or a new node's (250 devices, on). */
export function nodeValues(n?: Node): NodeValues {
  return {
    name: n?.name ?? "",
    city: n?.city ?? "",
    countryCode: n?.countryCode ?? "",
    maxPeers: String(n?.maxPeers ?? 250),
    hostname: n?.hostname ?? "",
    active: n?.active ?? true,
    countryName: n?.countryName ?? "",
    displayName: n?.displayName ?? "",
    sortOrder: String(n?.sortOrder ?? 0),
    inSubscription: n?.inSubscription !== false,
    cascadeRole: (n?.cascadeRole as CascadeRole | undefined) ?? "",
    protocol: n?.vless ? "vless" : "amneziawg",
    vless: vlessValues(n?.vless),
  };
}

/**
 * The VLESS part (vlessBody() in app.js): only what the apps need, never the Reality private key.
 * A transport's tag and flow stay as they were; a new one gets the defaults.
 */
export function vlessBody(v: VlessValues, was?: Vless): Vless {
  const { vis, xh } = transportsOf(was);
  const transports: Vless["transports"] = [];
  if (v.vision.trim())
    transports.push({ type: "tcp", port: Number(v.vision), tag: vis?.tag || "vision", flow: vis?.flow ?? "xtls-rprx-vision" });
  if (v.xhttp.trim())
    transports.push({
      type: "xhttp",
      port: Number(v.xhttp),
      tag: xh?.tag || "xhttp",
      flow: xh?.flow ?? "",
      path: v.xhttpPath.trim(),
      mode: v.xhttpMode,
    });
  return {
    host: v.host.trim(),
    sni: v.sni.trim(),
    publicKey: v.publicKey.trim(),
    // As the old form: an empty field is one empty short id (Reality allows it), "a, b" is two.
    shortIds: v.shortIds
      .split(",")
      .map((s) => s.trim())
      .filter((s, _, a) => s || a.length === 1),
    fingerprint: v.fingerprint.trim() || "firefox",
    transports,
  };
}

/**
 * The request (nodeBody() in app.js). The main server has no protocol choice (`local`), so it never
 * sends VLESS settings; a VLESS node always sends them with its old tags and flows.
 */
export function nodeBody(v: NodeValues, opts: { local?: boolean; was?: Node } = {}): NodeReq {
  const vless = !opts.local && v.protocol === "vless";
  return {
    name: v.name.trim(),
    city: v.city.trim(),
    countryCode: v.countryCode.trim().toUpperCase(),
    maxPeers: Number(v.maxPeers),
    active: v.active,
    hostname: v.hostname.trim() || null,
    countryName: v.countryName.trim() || null,
    protocol: vless ? "vless" : null,
    vless: vless ? vlessBody(v.vless, opts.was?.vless) : null,
    displayName: v.displayName.trim(),
    sortOrder: Number(v.sortOrder || 0),
    inSubscription: v.inSubscription,
    cascadeRole: v.cascadeRole,
  };
}

/** SSH access to a fresh server, used once for the install and never kept. */
export interface SshValues {
  host: string;
  port: string;
  user: string;
  password: string;
  privateKey: string;
}

export const sshValues = (host = ""): SshValues => ({ host, port: "22", user: "root", password: "", privateKey: "" });

/** sshBody() in app.js: a password or a private key is needed. */
export function sshBody(v: SshValues): Schema<"NodeSshReq"> {
  const body = {
    host: v.host.trim(),
    port: Number(v.port) || 22,
    user: v.user.trim() || "root",
    password: v.password || null,
    privateKey: v.privateKey.trim() || null,
  };
  if (!body.password && !body.privateKey) throw new Error(t("Enter the password or the private key"));
  return body;
}

/** The order of the locations in the apps and subscriptions: sort_order, then the name and the id. */
export function byOrder(nodes: readonly Node[]): Node[] {
  return [...nodes].sort((a, b) => a.sortOrder - b.sortOrder || a.name.localeCompare(b.name) || a.id.localeCompare(b.id));
}

/** The nodes the devices of `from` can move to: other nodes that are on and have a protocol. */
export const moveTargets = (nodes: readonly Node[], from: Node) => nodes.filter((n) => n.id !== from.id && n.active && n.protocols.length);

/** PUT /admin/nodes/order gives the listed nodes 0, 10, 20… */
export const ORDER_STEP = 10;
