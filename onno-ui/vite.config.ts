/// <reference types="vitest/config" />
import { defineConfig } from "vite";
import path from "path";
import { onno } from "./vite-preset.js";

// The shell's own build (the default UI an app without UI code gets) uses the same preset apps do.
export default defineConfig({
  plugins: [onno({ backend: `http://localhost:${process.env.BACKEND_PORT || 8080}` })],
  base: "/",
  resolve: {
    alias: [
      { find: /^@onno\/ui$/, replacement: path.resolve(__dirname, "./src/index.ts") },
      { find: /^@onno\/ui\/icons$/, replacement: path.resolve(__dirname, "./src/icons.ts") },
      { find: /^@\//, replacement: path.resolve(__dirname, "./src") + "/" },
    ],
  },
  test: {
    alias: [{ find: /^lucide-react$/, replacement: path.resolve(__dirname, "../node_modules/lucide-react/dist/esm/lucide-react.js") }],
    environment: "jsdom",
    globals: true,
    setupFiles: ["./tests/setup.ts"],
  },
});
