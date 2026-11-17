import { StrictMode } from "react";
import { createRoot } from "react-dom/client";
import App from "./App";
import type { AppDefinition } from "./lib/app-config";
import "./index.css";

let mounted = false;

/**
 * The app's UI: renders the shell with the app's declaration into `#root`. Call once, from the
 * app's entry. Every part is optional — `defineApp({})` is the framework's defaults.
 */
export function defineApp(app: AppDefinition, container: HTMLElement | null = document.getElementById("root")): void {
  if (mounted || !container) return;
  mounted = true;
  createRoot(container).render(
    <StrictMode>
      <App app={app} />
    </StrictMode>
  );
}
