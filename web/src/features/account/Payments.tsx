/** "Payments": the account's payments with their period and where they were made. */
import type { Schema } from "@/api/typed";
import { Section } from "@/components/layout";
import { Table, Td, Th } from "@/components/ui";
import { useI18n } from "@/i18n";
import { paymentSource, paymentTitle } from "@/lib/payments";

type Payment = Schema<"Payment">;

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
              <Td>{paymentTitle(x)}</Td>
              <Td>{t("{from} to {to}", { from: date(x.startedAt), to: date(x.expiresAt) })}</Td>
              <Td>{paymentSource(x.source)}</Td>
            </tr>
          ))}
        </tbody>
      </Table>
    </Section>
  );
}
