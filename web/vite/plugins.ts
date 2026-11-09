import { gzipSync } from "node:zlib";
import type { Plugin } from "vite";

/**
 * Build plugins of the new website (vite.config.ts). They live in vite/, not build/: the root
 * .gitignore ignores every build/ directory.
 */

const AXIOS_COOKIES = /[\\/]axios[\\/]lib[\\/]helpers[\\/]cookies\.js$/;

/**
 * Replaces Axios's XSRF cookie helper with a no-op. `/v1` uses Bearer tokens and never sets an XSRF
 * cookie, so nothing changes for the website, and the mini app's bundle gets no `document.cookie`.
 */
export function noAxiosCookies(): Plugin {
  return {
    name: "jaganet:no-axios-cookies",
    enforce: "pre",
    load(id) {
      if (!AXIOS_COOKIES.test(id.split("?")[0])) return null;
      return [
        "// Replaced by vite/plugins.ts (noAxiosCookies): /v1 uses Bearer tokens, no XSRF cookie.",
        "export default {",
        "  write() {},",
        "  read() { return null; },",
        "  async readAsync() { return null; },",
        "  remove() {},",
        "};",
      ].join("\n");
    },
  };
}

/** A page whose files are checked: the chunk name (rolldown input) and the HTML file name. */
export interface CheckedPage {
  name: string;
  html: string;
}

/** What the mini app and /tg/open never load: web storage and cookies. */
const FORBIDDEN_CODE: [RegExp, string][] = [
  [/\blocalStorage\b/, "localStorage"],
  [/\bsessionStorage\b/, "sessionStorage"],
  [/document\.cookie/, "document.cookie"],
];

/** The website's modules: other screens (admin, analytics…), the router, the shell, charts, Swagger UI. */
const FORBIDDEN_MODULES: [RegExp, string][] = [
  [/[\\/]src[\\/]features[\\/](?!miniapp[\\/])/, "another screen (src/features/)"],
  [/[\\/]src[\\/]routes[\\/]/, "the router (src/routes/)"],
  [/[\\/]src[\\/]components[\\/](shell|charts|admin)[\\/]/, "a website component (shell, charts, admin)"],
  [/[\\/]src[\\/]components[\\/]LinkTabs\./, "LinkTabs (the router)"],
  [/[\\/]src[\\/]lib[\\/](storage|checkout)\./, "web storage or website checkout (src/lib/)"],
  [/[\\/]src[\\/]i18n[\\/]site\./, "the website's language handling (src/i18n/site.ts)"],
  [/[\\/]node_modules[\\/]@tanstack[\\/](react-)?router/, "TanStack Router"],
  [/[\\/]node_modules[\\/]i18next-browser-languagedetector[\\/]/, "the language detector"],
  [/[\\/]node_modules[\\/]swagger-ui-dist[\\/]/, "Swagger UI"],
  [/[\\/]node_modules[\\/]@hello-pangea[\\/]/, "drag and drop (admin)"],
];

/** Inline code in the HTML that MINIAPP_CSP (script-src 'self' https://telegram.org; style-src 'self') blocks. */
const INLINE_HTML: [RegExp, string][] = [
  [/<script(?![^>]*\bsrc=)[^>]*>/i, "an inline <script>"],
  [/<style\b/i, "a <style> element"],
  [/\sstyle\s*=/i, "a style attribute"],
  [/\son[a-z]+\s*=/i, "an inline event handler"],
  [/(src|href)\s*=\s*["']?\s*(data|javascript):/i, "a data: or javascript: URL"],
];

const kb = (n: number) => `${(n / 1024).toFixed(1)} KB`;
const gz = (s: string | Uint8Array) => gzipSync(s).length;
const text = (s: string | Uint8Array) => (typeof s === "string" ? s : Buffer.from(s).toString("utf8"));

/**
 * Checks the mini app's pages (and /tg/open) after the build: prints every file they load with its
 * size (eager and lazy, plain and gzip) and fails the build on web storage or cookies in their code,
 * a website module among their modules, a `data:` URL in their CSS, or inline code in their HTML.
 * MiniAppTest and `npm run test:browser` check the same pages again (docs/WEB-FRONTEND.md).
 */
export function miniappBundle(pages: CheckedPage[]): Plugin {
  return {
    name: "jaganet:miniapp-bundle",
    apply: "build",
    enforce: "post",
    generateBundle(_options, bundle) {
      const errors: string[] = [];
      const chunks = Object.values(bundle).flatMap((f) => (f.type === "chunk" ? [f] : []));
      const byFile = new Map(chunks.map((c) => [c.fileName, c]));

      for (const page of pages) {
        const html = bundle[page.html];
        if (!html || html.type !== "asset") {
          errors.push(`${page.html}: not in the build`);
          continue;
        }
        const source = text(html.source);
        for (const [re, what] of INLINE_HTML) {
          if (re.test(source)) errors.push(`${page.html}: ${what} (MINIAPP_CSP blocks it)`);
        }

        const entry = chunks.find((c) => c.isEntry && (c.name === page.name || c.facadeModuleId?.endsWith(page.html)));
        if (!entry) {
          errors.push(`${page.name}: no entry chunk`);
          continue;
        }

        // Eager files: the entry and its static imports; lazy: whatever dynamic imports reach.
        const eager = new Set<string>();
        const lazy = new Set<string>();
        const walk = (file: string, isLazy: boolean) => {
          const seen = isLazy ? lazy : eager;
          if (seen.has(file) || (isLazy && eager.has(file))) return;
          seen.add(file);
          const chunk = byFile.get(file);
          if (!chunk) return;
          for (const f of chunk.imports) walk(f, isLazy);
          for (const f of chunk.dynamicImports) walk(f, true);
        };
        walk(entry.fileName, false);
        for (const f of eager) lazy.delete(f);

        const css = new Set<string>();
        for (const f of [...eager, ...lazy]) {
          for (const c of byFile.get(f)?.viteMetadata?.importedCss ?? []) css.add(c);
        }
        for (const m of source.matchAll(/href="\/ui\/([^"]+\.css)"/g)) css.add(m[1]);

        const rows: [string, number, number, boolean][] = [];
        for (const f of [...eager, ...lazy]) {
          const chunk = byFile.get(f)!;
          rows.push([f, chunk.code.length, gz(chunk.code), lazy.has(f)]);
          for (const [re, what] of FORBIDDEN_CODE) {
            if (re.test(chunk.code)) errors.push(`${page.name}: ${what} in ${f}`);
          }
          for (const id of chunk.moduleIds) {
            for (const [re, what] of FORBIDDEN_MODULES) {
              if (re.test(id)) errors.push(`${page.name}: ${what} in ${f} (${id})`);
            }
          }
        }
        for (const f of css) {
          const asset = bundle[f];
          if (!asset || asset.type !== "asset") continue;
          const code = text(asset.source);
          rows.push([f, code.length, gz(code), false]);
          if (/url\(\s*["']?\s*data:/i.test(code)) errors.push(`${page.name}: a data: URL in ${f} (img-src 'self')`);
        }

        const total = rows.filter((r) => !r[3]);
        const sum = (i: 1 | 2) => total.reduce((n, r) => n + r[i], 0);
        const lines = rows.map(
          ([f, raw, zip, isLazy]) => `  ${isLazy ? "lazy " : "     "}${f.padEnd(44)} ${kb(raw).padStart(10)}  gzip ${kb(zip).padStart(9)}`,
        );
        this.info(`${page.html} loads:\n${lines.join("\n")}\n  eager total ${kb(sum(1))}, gzip ${kb(sum(2))}`);
      }

      if (errors.length) this.error(`The mini app's bundle breaks its rules (docs/WEB-FRONTEND.md):\n  ${errors.join("\n  ")}`);
    },
  };
}
