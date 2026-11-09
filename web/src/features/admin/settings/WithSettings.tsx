/** /admin/settings for the settings tabs (e-mail, alerts, test modes, plans): loading, error, then the pane. */
import { useQuery } from "@tanstack/react-query";
import type { ReactNode } from "react";
import type { Schema } from "@/api/typed";
import { ErrorState, Spinner } from "@/components/ui";
import { settingsQuery } from "../queries";

export type AdminSettings = Schema<"AdminSettingsRes">;

export function WithSettings({ children }: { children: (s: AdminSettings) => ReactNode }) {
  const q = useQuery(settingsQuery);
  if (q.isError) return <ErrorState error={q.error} onRetry={() => void q.refetch()} />;
  if (!q.data) return <Spinner />;
  return <>{children(q.data)}</>;
}
