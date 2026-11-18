// Lint for @onno/ui itself (`npm run lint`, Gradle `:onno-web-starter:lintFrontend`, part of `check`).
// A deliberately conservative start: rules the current code passes, so every reported problem is a
// regression. Turn more on one at a time, fixing the code in the same change.
import js from "@eslint/js";
import tseslint from "typescript-eslint";
import reactHooks from "eslint-plugin-react-hooks";
import globals from "globals";
import onno from "./eslint-plugin/index.js";

export default tseslint.config(
  { ignores: ["dist", "node_modules"] },
  { linterOptions: { reportUnusedDisableDirectives: "error" } },
  {
    files: ["**/*.{js,ts,tsx}"],
    extends: [js.configs.recommended, ...tseslint.configs.recommended],
    languageOptions: { globals: { ...globals.browser, ...globals.node } },
    plugins: { "react-hooks": reactHooks, onno },
    rules: {
      "react-hooks/rules-of-hooks": "error",
      // Advisory: some effects deliberately run on a narrower dependency set. The React Compiler
      // rules of eslint-plugin-react-hooks 7 (refs, set-state-in-effect, …) are not on yet: the
      // existing code has ~130 findings, to be cleared before they can be errors.
      "react-hooks/exhaustive-deps": "warn",
      "@typescript-eslint/no-unused-vars": ["error", {
        argsIgnorePattern: "^_", varsIgnorePattern: "^_", caughtErrorsIgnorePattern: "^_", ignoreRestSiblings: true,
      }],
      // A variable assigned once after a closure that reads it (a timer cleared in its own callback).
      "prefer-const": ["error", { ignoreReadBeforeAssign: true }],
      // One failed request, one toast (#505).
      "onno/notify-caught-error": "error",
    },
  },
  {
    // Tests fake browser APIs and data with loose shapes.
    files: ["tests/**", "**/*.test.{ts,tsx}"],
    rules: { "@typescript-eslint/no-explicit-any": "off" },
  },
  {
    // Type-level tests: each binding exists to be type-checked (often with @ts-expect-error), not read.
    files: ["tests/types/**"],
    rules: { "@typescript-eslint/no-unused-vars": "off" },
  },
);
