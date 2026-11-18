import type { ESLint, Linter, Rule } from "eslint";

/** The onno rules: `no-private-imports`, `icon-component`, `no-raw-api-fetch`, `notify-caught-error`. */
export declare const rules: Record<"no-private-imports" | "icon-component" | "no-raw-api-fetch" | "notify-caught-error", Rule.RuleModule>;

/** `app`: the onno rules for an app's own UI (`src/main/ui`). Pair it with a TypeScript parser. */
export declare const configs: { app: Linter.Config };

declare const plugin: ESLint.Plugin & { rules: typeof rules; configs: typeof configs };
export default plugin;
