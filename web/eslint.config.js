import { readdirSync } from "node:fs";
import js from "@eslint/js";
import jsxA11y from "eslint-plugin-jsx-a11y";
import reactHooks from "eslint-plugin-react-hooks";
import globals from "globals";
import tseslint from "typescript-eslint";

/**
 * A screen (src/features/<name>/) may import the shared modules and itself, never another screen:
 * what two screens need moves to components/, hooks/ or lib/ (docs/frontend-plan.md, 2.1).
 * The shared modules import no screen at all; only the router (src/routes/) puts screens together.
 */
const features = readdirSync(new URL("./src/features", import.meta.url), { withFileTypes: true })
  .filter((d) => d.isDirectory())
  .map((d) => d.name);

const otherScreens = (own) => ({
  "no-restricted-imports": [
    "error",
    {
      patterns: [
        {
          group: ["@/features/*", `!@/features/${own}`, `!@/features/${own}/**`],
          message: "A screen does not import another screen: move the shared part to components/, hooks/ or lib/.",
        },
        { group: ["../../*/**", "../../*"], message: "Import shared modules with @/… and never another screen." },
      ],
    },
  ],
});

export default tseslint.config(
  { ignores: ["dist", "node_modules", "src/api/schema.d.ts"] },
  {
    extends: [js.configs.recommended, ...tseslint.configs.recommended, jsxA11y.flatConfigs.recommended],
    files: ["**/*.{ts,tsx}"],
    languageOptions: { ecmaVersion: 2022, globals: globals.browser },
    plugins: { "react-hooks": reactHooks },
    rules: {
      ...reactHooks.configs.recommended.rules,
      "@typescript-eslint/no-unused-vars": ["error", { argsIgnorePattern: "^_" }],
    },
  },
  ...features.map((own) => ({ files: [`src/features/${own}/**/*.{ts,tsx}`], rules: otherScreens(own) })),
  {
    files: ["src/{api,components,hooks,i18n,lib,store,test}/**/*.{ts,tsx}"],
    rules: {
      "no-restricted-imports": [
        "error",
        { patterns: [{ group: ["@/features/*", "**/features/*"], message: "Shared modules do not import screens; the router does." }] },
      ],
    },
  },
);
