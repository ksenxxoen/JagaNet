import type { SwaggerRequest } from "swagger-ui-dist/swagger-ui-es-bundle.js";
import { currentLang } from "@/i18n";
import { useSession } from "@/store/session";

/**
 * Try it out sends requests with the current session, unless the owner typed another token in
 * Authorize (a node token for the node endpoints, for example). Read at request time, so a new
 * sign-in is picked up without reloading the page.
 */
export function withSession(req: SwaggerRequest): SwaggerRequest {
  const token = useSession.getState().token;
  const has = Object.keys(req.headers).some((h) => h.toLowerCase() === "authorization");
  if (token && !has) req.headers.Authorization = `Bearer ${token}`;
  req.headers["Accept-Language"] = currentLang();
  return req;
}
