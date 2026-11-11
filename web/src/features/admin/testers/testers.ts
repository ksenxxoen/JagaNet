/** Test accounts: rows pasted from a spreadsheet and the table for Excel. */
import type { Schema } from "@/api/typed";
import { date, localDay, t } from "@/i18n";

/**
 * Rows pasted from a spreadsheet: "name, devices" or "number, name, devices, …" per line, cells
 * split by tabs (Excel, Google Sheets) or semicolons. Lines without a device count are skipped.
 */
export function parseTesters(text: string): Schema<"TestAccountReq">[] {
  const out: Schema<"TestAccountReq">[] = [];
  for (const line of text.split(/\r?\n/)) {
    const cells = line.split(line.includes("\t") ? "\t" : ";").map((c) => c.trim());
    const at = cells.length >= 3 && /^\d+$/.test(cells[0] ?? "") ? 1 : 0;
    const name = cells[at];
    const devices = Number(cells[at + 1]);
    if (name && Number.isInteger(devices) && devices > 0) out.push({ name, devices });
  }
  return out;
}

/** CSV for Excel: semicolons, UTF-8 with BOM, one key link per column. */
export function testersCsv(list: Schema<"TestAccount">[]): string {
  const most = Math.max(1, ...list.map((a) => a.keys.length));
  const q = (v: unknown) => `"${String(v).replace(/"/g, '""')}"`;
  const pad = (arr: string[]) => Array.from({ length: most }, (_, i) => arr[i] ?? "");
  const head = [
    t("Client"),
    t("Email"),
    t("Devices"),
    t("Valid until"),
    ...Array.from({ length: most }, (_, i) => t("Key {n}", { n: i + 1 })),
    ...Array.from({ length: most }, (_, i) => t("AmneziaVPN key {n}", { n: i + 1 })),
  ];
  const rows = list.map((a) => [a.name, a.email, a.devices, date(localDay(a.expiresAt)), ...pad(a.keys), ...pad(a.vpnKeys ?? [])]);
  return "﻿" + [head, ...rows].map((r) => r.map(q).join(";")).join("\r\n");
}
