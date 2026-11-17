import type { PluginOption } from "vite";

/** The Vite preset for an onno app: `plugins: [onno()]`. */
export function onno(options?: {
  /** Where the dev server forwards API calls. Default `http://localhost:8080` or `$ONNO_BACKEND`. */
  backend?: string;
}): PluginOption[];
