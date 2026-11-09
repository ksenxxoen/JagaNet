/// <reference types="vitest/config" />
import { fileURLToPath, URL } from "node:url";
import react from "@vitejs/plugin-react";
import { defineConfig } from "vite";

/**
 * The new website. It is served by the panel server on /next/ (preview, see docs/WEB-FRONTEND.md),
 * so every asset URL starts with /next/. `npm run dev` proxies /v1 and the server pages to a local
 * server (`./gradlew :server:sim`, port 4000 by default; JAGANET_DEV_SERVER overrides it).
 */
const backend = process.env.JAGANET_DEV_SERVER ?? "http://localhost:4000";

export default defineConfig({
  base: "/next/",
  plugins: [react()],
  resolve: { alias: { "@": fileURLToPath(new URL("./src", import.meta.url)) } },
  build: {
    outDir: "dist",
    emptyOutDir: true,
    assetsDir: "assets",
    sourcemap: false,
    // Swagger UI (~1.5 MB, the owner's API tab only) is the one large chunk, and it is lazy.
    chunkSizeWarningLimit: 1600,
    // Libraries change less often than the app: a separate file stays cached between releases.
    // Libraries of lazy screens stay out of it, in their screens' chunks: Swagger UI (owner's API
    // tab), qrcode (the account, later the mini app) and, from W-7, @hello-pangea/dnd with its own
    // libraries (redux, react-redux, raf-schd, css-box-model, tiny-invariant: the node order).
    // Guests never download them.
    rolldownOptions: {
      output: {
        codeSplitting: {
          groups: [
            {
              name: "vendor",
              test: /node_modules[\\/](?!swagger-ui-dist|@hello-pangea|redux|react-redux|raf-schd|css-box-model|tiny-invariant|qrcode|dijkstrajs|pngjs)/,
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
