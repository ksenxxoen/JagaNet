/** The prebuilt Swagger UI bundle (swagger-ui-dist): only what ApiDocs uses. */
declare module "swagger-ui-dist/swagger-ui-es-bundle.js" {
  export interface SwaggerRequest {
    url: string;
    method?: string;
    headers: Record<string, string>;
    [key: string]: unknown;
  }
  export interface SwaggerOptions {
    domNode: HTMLElement;
    spec: object;
    deepLinking?: boolean;
    docExpansion?: "list" | "full" | "none";
    defaultModelsExpandDepth?: number;
    filter?: boolean;
    persistAuthorization?: boolean;
    displayRequestDuration?: boolean;
    tryItOutEnabled?: boolean;
    requestInterceptor?: (req: SwaggerRequest) => SwaggerRequest;
  }
  const SwaggerUI: (options: SwaggerOptions) => unknown;
  export default SwaggerUI;
}
