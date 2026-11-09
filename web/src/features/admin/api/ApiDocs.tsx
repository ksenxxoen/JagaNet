/**
 * #/admin?tab=api: Swagger UI over /v1/openapi.json (owner only on the server, too). Loaded lazily
 * with its styles. No deep links: the website itself lives in the URL hash.
 */
import { useQuery } from "@tanstack/react-query";
import { useEffect, useRef } from "react";
import SwaggerUI from "swagger-ui-dist/swagger-ui-es-bundle.js";
import "swagger-ui-dist/swagger-ui.css";
import "./api-docs.css";
import { get } from "@/api/typed";
import { ErrorState, Spinner } from "@/components/ui";
import { useI18n } from "@/i18n";
import { withSession } from "./session";

export const openApiQuery = { queryKey: ["openapi"], queryFn: () => get("/openapi.json"), staleTime: Infinity } as const;

export default function ApiDocs() {
  const { t } = useI18n();
  const spec = useQuery(openApiQuery);
  const node = useRef<HTMLDivElement>(null);

  useEffect(() => {
    const el = node.current;
    if (!spec.data || !el) return;
    SwaggerUI({
      domNode: el,
      spec: spec.data,
      deepLinking: false,
      docExpansion: "none",
      defaultModelsExpandDepth: 0,
      filter: true,
      persistAuthorization: false,
      displayRequestDuration: true,
      requestInterceptor: withSession,
    });
    return () => {
      el.replaceChildren();
    };
  }, [spec.data]);

  return (
    <div>
      <h2 className="mb-2 mt-0 text-[22px] leading-snug">{t("API")}</h2>
      <p className="mb-6 text-muted">{t("Every /v1 endpoint of the panel. Requests from Try it out are sent with your session.")}</p>
      {spec.isPending && <Spinner className="mx-auto my-16" label={t("Loading")} />}
      {spec.isError && <ErrorState error={spec.error} onRetry={() => void spec.refetch()} />}
      <div ref={node} className="api-docs" data-testid="swagger" />
    </div>
  );
}
