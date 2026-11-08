/** "Payments": the account's payments with their period and where they were made. */
import type { Schema } from "@/api/typed";
import { Section } from "@/components/layout";
import { Table, Td, Th } from "@/components/ui";
import { t as tr, useI18n } from "@/i18n";

type Payment = Schema<"Payment">;

export const sourceName = (source: string): string =>
  ({
    web: tr("Website"),
    telegram: "Telegram",
    app: tr("Boop app"),
    apple: "App Store",
    google: "Google Play",
    referral: tr("Invite reward"),
    dev: tr("Simulation"),
  })[source] ?? source;

export function PaymentsSection({ payments }: { payments: Payment[] }) {
  const { t, date } = useI18n();
  if (!payments.length) return null;
  return (
    <Section title={t("Payments")} className="mt-10">
      <Table>
        <thead>
          <tr>
            <Th>{t("Plan")}</Th>
            <Th>{t("Period")}</Th>
            <Th>{t("Channel")}</Th>
          </tr>
        </thead>
        <tbody>
          {payments.map((x) => (
            <tr key={x.id}>
              <Td>{x.productId === "referral" ? t("Invite reward") : x.tariffName || "Pro"}</Td>
              <Td>{t("{from} to {to}", { from: date(x.startedAt), to: date(x.expiresAt) })}</Td>
              <Td>{sourceName(x.source)}</Td>
            </tr>
          ))}
        </tbody>
      </Table>
    </Section>
  );
}
