import { BASE_PATH } from "@/lib/base-path";

type TelemetryName = "route.viewed" | "api.request" | "ui.error";

interface BrowserEvent {
  id: string;
  name: TelemetryName;
  occurredAt: string;
  outcome?: string;
  durationMs?: number;
  route?: string;
  dimensions?: Record<string, string>;
}

const API_PATH = "/api/telemetry/events";
const SESSION_KEY = "onno:telemetry-session";
const SAMPLE_KEY = "onno:telemetry-sampled";
const MAX_BATCH = 20;
const FLUSH_MS = 5_000;

let enabled = false;
let installed = false;
let queue: BrowserEvent[] = [];
let flushTimer: number | null = null;
let nativeFetch: typeof window.fetch | null = null;

function randomId(): string {
  return globalThis.crypto?.randomUUID?.()
    ?? `${Date.now().toString(36)}-${Math.random().toString(36).slice(2)}`;
}

function sessionId(): string {
  try {
    const existing = sessionStorage.getItem(SESSION_KEY);
    if (existing) return existing;
    const created = randomId();
    sessionStorage.setItem(SESSION_KEY, created);
    return created;
  } catch {
    return randomId();
  }
}

function sampled(rate: number): boolean {
  try {
    const existing = sessionStorage.getItem(SAMPLE_KEY);
    if (existing != null) return existing === "1";
    const selected = Math.random() < Math.max(0, Math.min(1, rate));
    sessionStorage.setItem(SAMPLE_KEY, selected ? "1" : "0");
    return selected;
  } catch {
    return Math.random() < rate;
  }
}

function csrfToken(): string | null {
  const part = document.cookie.split(";")
    .map((value) => value.trim())
    .find((value) => value.startsWith("XSRF-TOKEN="));
  return part ? decodeURIComponent(part.slice("XSRF-TOKEN=".length)) : null;
}

/** Remove query strings and identifiers before a route can leave the browser. */
export function normalizeTelemetryRoute(value: string): string {
  let pathname = value;
  try {
    pathname = new URL(value, window.location.origin).pathname;
  } catch {
    pathname = value.split(/[?#]/, 1)[0];
  }
  if (BASE_PATH !== "/" && pathname.startsWith(BASE_PATH)) {
    pathname = pathname.slice(BASE_PATH.length) || "/";
  }
  return pathname
    .replace(/[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}/gi, ":id")
    .replace(/\/\d+(?=\/|$)/g, "/:id")
    .slice(0, 160);
}

function deviceClass(): string {
  if (window.innerWidth < 640) return "phone";
  if (window.innerWidth < 1024) return "tablet";
  return "desktop";
}

function enqueue(event: Omit<BrowserEvent, "id" | "occurredAt">) {
  if (!enabled) return;
  queue.push({
    ...event,
    id: randomId(),
    occurredAt: new Date().toISOString(),
    dimensions: { device: deviceClass(), ...(event.dimensions ?? {}) },
  });
  if (queue.length >= MAX_BATCH) void flush();
}

async function flush() {
  if (!enabled || queue.length === 0 || !nativeFetch) return;
  const events = queue.splice(0, MAX_BATCH);
  const headers: Record<string, string> = { "Content-Type": "application/json" };
  const csrf = csrfToken();
  if (csrf) headers["X-XSRF-TOKEN"] = csrf;
  try {
    const response = await nativeFetch(API_PATH, {
      method: "POST",
      credentials: "same-origin",
      headers,
      keepalive: true,
      body: JSON.stringify({ sessionId: sessionId(), events }),
    });
    if (!response.ok) queue = events.concat(queue).slice(0, 200);
  } catch {
    queue = events.concat(queue).slice(0, 200);
  }
}

function installFetchTiming() {
  if (nativeFetch) return;
  nativeFetch = window.fetch.bind(window);
  window.fetch = async (input: RequestInfo | URL, init?: RequestInit) => {
    const url = typeof input === "string" ? input : input instanceof URL ? input.href : input.url;
    if (url.includes(API_PATH)) return nativeFetch!(input, init);
    const started = performance.now();
    const method = (init?.method ?? (input instanceof Request ? input.method : "GET")).toUpperCase();
    try {
      const response = await nativeFetch!(input, init);
      if (new URL(url, window.location.origin).pathname.startsWith("/api/")) {
        enqueue({
          name: "api.request",
          outcome: response.ok ? "success" : "error",
          durationMs: Math.round(performance.now() - started),
          route: normalizeTelemetryRoute(url),
          dimensions: { method },
        });
      }
      return response;
    } catch (error) {
      enqueue({
        name: "api.request",
        outcome: "error",
        durationMs: Math.round(performance.now() - started),
        route: normalizeTelemetryRoute(url),
        dimensions: { method, errorType: error instanceof TypeError ? "network" : "unknown" },
      });
      throw error;
    }
  };
}

/** Called once after authenticated `/api/config` advertises telemetry. */
export function configureTelemetry(config?: { enabled: boolean; sampleRate: number }) {
  if (!config?.enabled || installed) return;
  installed = true;
  enabled = sampled(config.sampleRate);
  if (!enabled) return;
  installFetchTiming();
  // The first route effect runs while /api/config is still loading, so capture the current route
  // when telemetry becomes active; later client-side navigations are recorded by App.
  recordRouteView(window.location.pathname);
  flushTimer = window.setInterval(() => void flush(), FLUSH_MS);
  window.addEventListener("pagehide", () => void flush());
  window.addEventListener("error", (event) => {
    recordUiError(event.error instanceof Error ? event.error.name : "window");
  });
  window.addEventListener("unhandledrejection", (event) => {
    recordUiError(event.reason instanceof Error ? event.reason.name : "promise");
  });
}

export function recordRouteView(pathname: string) {
  enqueue({
    name: "route.viewed",
    outcome: "success",
    route: normalizeTelemetryRoute(pathname),
  });
}

export function recordUiError(errorType: string, component?: string) {
  enqueue({
    name: "ui.error",
    outcome: "error",
    route: normalizeTelemetryRoute(window.location.pathname),
    dimensions: {
      errorType: (errorType || "unknown").slice(0, 80),
      ...(component ? { component: component.slice(0, 80) } : {}),
    },
  });
}

/** Test-only cleanup for the patched global. */
export function resetTelemetryForTests() {
  if (nativeFetch) window.fetch = nativeFetch;
  if (flushTimer != null) window.clearInterval(flushTimer);
  nativeFetch = null;
  flushTimer = null;
  enabled = false;
  installed = false;
  queue = [];
}
