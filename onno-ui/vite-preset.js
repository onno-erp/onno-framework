// The Vite preset for an onno app: `plugins: [onno()]` in the app's vite.config.ts. It compiles the
// app together with @onno/ui (React, Tailwind with the shell's theme), proxies the backend in dev
// and writes the production build where the Gradle plugin packages it.
import fs from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";
import react from "@vitejs/plugin-react";
import tailwindcss from "tailwindcss";
import autoprefixer from "autoprefixer";
import theme from "./tailwind.config.js";

const packageDir = path.dirname(fileURLToPath(import.meta.url));

/** Backend paths the dev server forwards (everything the app calls outside its own pages). */
const BACKEND_PATHS = ["/api", "/branding", "/ui/branding", "/oauth2", "/login/oauth2"];

/** The real directory of an installed package, walking up from `from` like Node does. */
function packageRoot(name, from) {
  for (let dir = from; ; dir = path.dirname(dir)) {
    const candidate = path.join(dir, "node_modules", name);
    if (fs.existsSync(candidate)) return fs.realpathSync(candidate);
    if (path.dirname(dir) === dir) return null;
  }
}

/** Source globs of the app's other @onno packages (e.g. @onno/crm), so Tailwind sees their classes. */
function onnoPackageSources(root) {
  let pkg;
  try {
    pkg = JSON.parse(fs.readFileSync(path.join(root, "package.json"), "utf8"));
  } catch {
    return [];
  }
  return Object.keys({ ...pkg.dependencies, ...pkg.devDependencies })
    .filter((name) => name.startsWith("@onno/") && name !== "@onno/ui")
    .map((name) => packageRoot(name, root))
    .filter(Boolean)
    .map((dir) => path.join(dir, "**/*.{ts,tsx}"));
}

/**
 * @param {{ backend?: string }} [options] `backend`: where the dev server forwards API calls
 *   (default `http://localhost:8080`, or `$ONNO_BACKEND`).
 */
export function onno(options = {}) {
  const backend = options.backend ?? process.env.ONNO_BACKEND ?? "http://localhost:8080";
  return [
    react(),
    {
      name: "onno",
      config(config) {
        const root = path.resolve(config.root ?? process.cwd());
        const content = [
          path.join(packageDir, "src/**/*.{ts,tsx}"),
          path.join(root, "*.{ts,tsx,html}"),
          path.join(root, "!(node_modules|dist)/**/*.{ts,tsx}"),
          ...onnoPackageSources(root),
        ];
        return {
          publicDir: config.publicDir ?? path.join(packageDir, "public"),
          resolve: { dedupe: ["react", "react-dom", "@tanstack/react-query"] },
          css: { postcss: { plugins: [tailwindcss({ ...theme, content }), autoprefixer()] } },
          build: { outDir: process.env.ONNO_UI_OUT_DIR ?? "dist", emptyOutDir: true },
          server: { proxy: Object.fromEntries(BACKEND_PATHS.map((p) => [p, backend])) },
        };
      },
    },
  ];
}
