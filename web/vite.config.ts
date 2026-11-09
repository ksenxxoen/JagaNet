/// <reference types="vitest/config" />
import { fileURLToPath, URL } from "node:url";
import react from "@vitejs/plugin-react";
import { defineConfig } from "vite";
import { miniappBundle, noAxiosCookies } from "./build/plugins";

/**
 * Three pages from one build, all with assets under /ui/assets/ (hashed names, cached for a year):
 *
 *   - index.html: the website, served by the panel on /next/ (preview, docs/WEB-FRONTEND.md);
 *   - miniapp.html: the Telegram mini app, a template the panel fills and serves on /tg/app-next;
 *   - tg-open.html: /tg/open-next, hands an import link from the mini app to the app.
 *
 * The HTML is served where it belongs; the assets are the same for every address, so switching
 * addresses later (W-10) needs no rebuild. `npm run dev` proxies /v1 and the server pages to a
 * local server (`./gradlew :server:sim`, port 4000 by default; JAGANET_DEV_SERVER overrides it).
 */
const backend = process.env.JAGANET_DEV_SERVER ?? "http://localhost:4000";
const page = (name: string) => fileURLToPath(new URL(name, import.meta.url));

/** Libraries the mini app shares with the website: one cached file for both. */
const CORE =
  /node_modules[\\/](react|react-dom|scheduler|@tanstack[\\/](query-core|react-query)|zustand|use-sync-external-store|axios|i18next|react-i18next|i18next-http-backend|html-parse-stringify|void-elements|clsx|tailwind-merge|class-variance-authority|react-hot-toast|lucide-react)[\\/]/;

export default defineConfig({
  base: "/ui/",
  plugins: [
    noAxiosCookies(),
    react(),
    miniappBundle([
      { name: "miniapp", html: "miniapp.html" },
      { name: "tg-open", html: "tg-open.html" },
    ]),
  ],
  resolve: { alias: { "@": fileURLToPath(new URL("./src", import.meta.url)) } },
  build: {
    outDir: "dist",
    emptyOutDir: true,
    assetsDir: "assets",
    sourcemap: false,
    // Swagger UI (~1.5 MB, the owner's API tab only) is the one large chunk, and it is lazy.
    chunkSizeWarningLimit: 1600,
    rolldownOptions: {
      input: { site: page("index.html"), miniapp: page("miniapp.html"), "tg-open": page("tg-open.html") },
      output: {
        codeSplitting: {
          groups: [
            // React, React Query, Zustand, Axios, i18next, the toasts and icons: the mini app's libraries, shared with the website.
            { name: "core", test: CORE, priority: 2 },
            // The website's other libraries (the router, the language detector…) change less often than the app:
            // a separate file stays cached between releases. Libraries of lazy screens stay out of it, in their
            // screens' chunks: Swagger UI (owner's API tab), qrcode (the account and the mini app's connection
            // screen) and @hello-pangea/dnd with its own libraries (redux, react-redux, raf-schd, css-box-model,
            // tiny-invariant: the node order). Guests and the mini app never download them.
            {
              name: "vendor",
              test: /node_modules[\\/](?!swagger-ui-dist|@hello-pangea|redux|react-redux|raf-schd|css-box-model|tiny-invariant|qrcode|dijkstrajs|pngjs)/,
              priority: 1,
            },
          ],
        },
      },
    },
  },
  server: {
    port: 5173,
    proxy: Object.fromEntries(
      ["/v1", "/favicon.svg", "/privacy", "/terms", "/k", "/r", "/download"].map((p) => [p, { target: backend, changeOrigin: true }]),
    ),
  },
  test: {
    environment: "jsdom",
    globals: true,
    setupFiles: ["./src/test/setup.ts"],
    css: false,
  },
});
