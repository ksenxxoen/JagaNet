/**
 * #/admin until the admin panel moves (W-6, W-7): the API tab (Swagger UI) works here, the other
 * tabs show the placeholder with a link to the same tab on the current website.
 */
import { Link, useRouterState } from "@tanstack/react-router";
import { lazy, Suspense } from "react";
import { Spinner } from "@/components/ui";
import { useI18n } from "@/i18n";
import { Placeholder } from "@/routes/Placeholder";

// A separate chunk: guests and users never download Swagger UI.
const ApiDocs = lazy(() => import("./api/ApiDocs"));

export function AdminScreen() {
  const { t } = useI18n();
  const tab = useRouterState({ select: (s) => (s.location.search as { tab?: string }).tab });
  if (tab === "api")
    return (
      <Suspense fallback={<Spinner className="mx-auto my-16" label={t("Loading")} />}>
        <ApiDocs />
      </Suspense>
    );
  return (
    <Placeholder title={t("Admin panel")}>
      <p className="mt-6 text-muted">
        <Link to="/admin" search={{ tab: "api" }} className="text-link underline-offset-2 hover:underline">
          {t("API documentation")}
        </Link>
      </p>
    </Placeholder>
  );
}
