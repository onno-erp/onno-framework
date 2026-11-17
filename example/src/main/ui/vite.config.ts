import { defineConfig } from "vite";
import { onno } from "@onno/ui/vite";

// The repository's `npm run dev` serves this UI on :5173 against the example backend on :8091.
// Override ONNO_BACKEND for another backend. Production packaging is still `vite build`.
export default defineConfig({
  plugins: [onno({ backend: process.env.ONNO_BACKEND ?? "http://localhost:8091" })],
});
