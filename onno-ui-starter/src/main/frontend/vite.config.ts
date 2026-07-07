import { defineConfig } from "vitest/config";
import react from "@vitejs/plugin-react";
import path from "path";

export default defineConfig({
  plugins: [react()],
  base: "/",
  resolve: {
    alias: {
      "@": path.resolve(__dirname, "./src"),
    },
  },
  server: {
    proxy: {
      "/api": `http://localhost:${process.env.BACKEND_PORT || 8080}`,
      // Backend-served assets that live outside /api: consumer branding (logo/favicon, see
      // ThemeController) and compiled custom-widget plugins (UiAutoConfiguration's
      // {ui.path}/plugins/** handler). Without these the dev shell loses the logo and
      // dev-authored widgets 404.
      "/branding": `http://localhost:${process.env.BACKEND_PORT || 8080}`,
      "/ui/plugins": `http://localhost:${process.env.BACKEND_PORT || 8080}`,
    },
  },
  test: {
    environment: "jsdom",
    globals: true,
    setupFiles: ["./tests/setup.ts"],
  },
});
