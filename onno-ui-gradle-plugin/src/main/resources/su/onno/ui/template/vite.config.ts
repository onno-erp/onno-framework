import { defineConfig } from "vite";
import { onno } from "@onno/ui/vite";

// `npm run dev`: hot reload against the backend on :8080 (`onno({ backend })` to change it).
export default defineConfig({ plugins: [onno()] });
