import { useCallback, useEffect, useId, useMemo, useRef, useState } from "react";
import { Lock, LogIn, SearchX, TriangleAlert } from "lucide-react";
import { Button } from "@/components/ui/button";
import { useMessages } from "@/providers/messages-provider";
import {
  DivKitContent,
  type ContentAction,
  type ContentCard,
  type ContentHandle,
  type Delta,
} from "@/views/divkit-content";
// Routes served as native React pages rather than server-driven DivKit surfaces. Settings is no
// longer here — it's an ordinary DivKit Page now (see DivKitController.settings / PageBuilder.constants).
const REACT_PAGES: Record<string, () => JSX.Element> = {};

// Catalog/document LIST surfaces (2 path segments) have a targeted delta endpoint
// (rows div-patch + count variable). Home, detail, and registers don't — they patch
// their whole content body instead.
function isListSurface(pathname: string): boolean {
  const seg = pathname.split("/").filter(Boolean);
  return seg.length === 2 && (seg[0] === "catalogs" || seg[0] === "documents");
}

// A mounted pane's live hook: the surface it's showing plus the in-place refetch to
// run when a server event touches that surface. DivKitView keeps one SSE stream and
// fans events out across every registered pane.
export type LiveEntry = { path: string; run: () => void };
export type LiveRegistry = Map<string, LiveEntry>;

// The initial load's failure, kept as status + the server's reason (Spring's error JSON
// `message`, present when the app sets server.error.include-message=always) so the error
// surface can shape its copy by status instead of dumping "HTTP 403".
type PaneError = { status: number | null; detail: string | null };

/**
 * The pane-level error surface — what renders when a route's content fetch fails outright.
 * Copy is shaped by status: 401 → the session is gone (offer sign-in), 403 → RBAC denied this
 * route (this is what a user without read access to an entity sees), 404 → nothing served here
 * (no EntityView / stale link), anything else → generic with retry. All strings localize via
 * the chrome message bundle (error.* keys); the server's reason shows as a fine-print line.
 */
function PaneErrorState({
  error,
  onRetry,
  onAction,
}: {
  error: PaneError;
  onRetry: () => void;
  onAction: (action: ContentAction) => void;
}) {
  const t = useMessages();
  const kind =
    error.status === 401
      ? "unauthorized"
      : error.status === 403
        ? "forbidden"
        : error.status === 404
          ? "notFound"
          : "generic";
  const Icon =
    kind === "unauthorized" ? LogIn : kind === "forbidden" ? Lock : kind === "notFound" ? SearchX : TriangleAlert;
  return (
    <div className="flex flex-col items-center justify-center px-6 py-16 text-center sm:py-24">
      <div className="mb-4 flex h-12 w-12 items-center justify-center rounded-full bg-muted text-muted-foreground">
        <Icon className="h-6 w-6" aria-hidden />
      </div>
      <div className="text-base font-semibold text-foreground">{t(`error.${kind}.title`)}</div>
      <p className="mt-1 max-w-sm text-sm text-muted-foreground">{t(`error.${kind}.body`)}</p>
      {error.detail ? (
        <p className="mt-2 max-w-sm text-xs text-muted-foreground/70">{error.detail}</p>
      ) : null}
      <div className="mt-5 flex items-center gap-2">
        {kind === "unauthorized" ? (
          // onno://logout clears the shell cache and lands on /login — the same path the
          // account menu's Sign out takes, so a dead session re-authenticates cleanly.
          <Button size="sm" onClick={() => onAction({ url: "onno://logout" })}>
            {t("error.signIn")}
          </Button>
        ) : (
          <>
            {kind === "generic" ? (
              <Button size="sm" onClick={onRetry}>
                {t("error.retry")}
              </Button>
            ) : null}
            <Button
              size="sm"
              variant={kind === "generic" ? "outline" : "secondary"}
              onClick={() => onAction({ url: "onno://" })}
            >
              {t("error.home")}
            </Button>
          </>
        )}
      </div>
    </div>
  );
}

/**
 * One content surface — the body of an island (or the whole content area in the
 * non-desktop layouts). Owns its own fetch, skeleton/error, and live-update wiring
 * for {@code path}, so several can render side by side without sharing state. It
 * registers its refetch in the shared {@link LiveRegistry} keyed by a stable id, and
 * the parent's single SSE handler decides which panes a given event affects.
 */
export function ContentPane({
  path,
  viewport,
  theme,
  profile,
  onAction,
  registry,
  skeletonBg,
}: {
  path: string;
  viewport: string;
  theme: "light" | "dark";
  profile: string | null;
  onAction: (action: ContentAction) => void;
  registry: LiveRegistry;
  skeletonBg: string;
}) {
  const id = useId();
  const [content, setContent] = useState<{ key: string; json: unknown } | null>(null);
  const [error, setError] = useState<PaneError | null>(null);

  const endpoint = useMemo(() => {
    const isHome = path === "/" || path === "";
    const base = isHome ? "/api/divkit/home" : `/api/divkit${path}`;
    const qs = new URLSearchParams();
    qs.set("viewport", viewport);
    qs.set("theme", theme);
    if (profile) qs.set("profile", profile);
    return `${base}?${qs.toString()}`;
  }, [path, viewport, theme, profile]);

  const contentRef = useRef<ContentHandle>(null);
  const endpointRef = useRef(endpoint);
  endpointRef.current = endpoint;
  const pathRef = useRef(path);
  pathRef.current = path;

  // Apply a server-pushed change in place. List surfaces fetch a targeted delta (rows
  // div-patch + the count variable); other surfaces refetch and patch their whole
  // content body. Either way it's applyDelta on the live instance — no remount.
  // Refetch the full card and remount it — the same clean render as a page reload.
  // Used as the fallback when an in-place delta can't be applied, so a surface never
  // gets stuck wiped until the user manually reloads.
  const fullReload = useCallback((ep: string) => {
    fetch(ep, { credentials: "include" })
      .then((r) => (r.ok ? r.json() : Promise.reject(new Error(`HTTP ${r.status}`))))
      .then((json) => {
        if (endpointRef.current === ep) setContent({ key: ep, json });
      })
      .catch(() => {});
  }, []);

  const liveUpdate = useCallback(() => {
    const ep = endpointRef.current;
    const handle = contentRef.current;
    if (!handle) return;
    if (isListSurface(pathRef.current)) {
      fetch(`${ep}&delta=1`, { credentials: "include" })
        .then((r) => (r.ok ? r.json() : Promise.reject(new Error(`HTTP ${r.status}`))))
        .then((d: Delta) => {
          if (endpointRef.current !== ep) return;
          // If the targeted row patch didn't take (missing/broken target), fall back to
          // a full remount so the list is refreshed instead of wiped.
          if (!handle.applyDelta(d)) fullReload(ep);
        })
        .catch(() => {});
    } else {
      fetch(ep, { credentials: "include" })
        .then((r) => (r.ok ? r.json() : Promise.reject(new Error(`HTTP ${r.status}`))))
        .then((resp: { card?: { states?: { div?: { items?: unknown[] } }[] } }) => {
          if (endpointRef.current !== ep) return;
          const items = resp?.card?.states?.[0]?.div?.items;
          if (!items) return;
          if (!handle.applyDelta({ changes: [{ id: "onno-content", items }] })) fullReload(ep);
        })
        .catch(() => {});
    }
  }, [fullReload]);

  // Register this pane's surface + refetch so the parent SSE handler can reach it.
  useEffect(() => {
    registry.set(id, { path, run: liveUpdate });
    return () => {
      registry.delete(id);
    };
  }, [id, path, liveUpdate, registry]);

  // Initial content load per surface — show a skeleton until it lands, the status-shaped
  // error surface if it fails (403 access denied, 404 no view, …). Also the Retry handler.
  const load = useCallback(() => {
    const ep = endpointRef.current;
    if (REACT_PAGES[pathRef.current]) return; // React pages don't fetch a DivKit surface
    setContent(null);
    setError(null);
    fetch(ep, { credentials: "include" })
      .then(async (r) => {
        if (r.ok) return r.json() as Promise<unknown>;
        // Pull the server's reason out of Spring's error JSON when it's exposed
        // (server.error.include-message=always); tolerate a non-JSON error body.
        let detail: string | null = null;
        try {
          const body = (await r.json()) as { message?: unknown; error?: unknown };
          if (typeof body.message === "string" && body.message.trim()) detail = body.message;
          else if (typeof body.error === "string" && body.error.trim()) detail = body.error;
        } catch {
          // ignore — keep the status-based copy only
        }
        return Promise.reject<PaneError>({ status: r.status, detail });
      })
      .then((json) => {
        if (endpointRef.current === ep) setContent({ key: ep, json });
      })
      .catch((e: unknown) => {
        if (endpointRef.current !== ep) return;
        const pe = e as Partial<PaneError>;
        setError({ status: typeof pe?.status === "number" ? pe.status : null, detail: pe?.detail ?? null });
      });
  }, []);

  useEffect(() => {
    load();
  }, [endpoint, load]);

  const reactPage = REACT_PAGES[path];
  if (reactPage) {
    return reactPage();
  }

  const contentJson = content && content.key === endpoint ? content.json : null;
  // The outer padding now lives in the DivKit content document (Div.contentPadding), so
  // the shell owns no content insets — only the skeleton/error placeholders, which DivKit
  // hasn't rendered yet, carry matching padding so the loading state doesn't sit flush.
  return (
    <>
      {error ? (
        <PaneErrorState error={error} onRetry={load} onAction={onAction} />
      ) : !contentJson ? (
        <div className="flex flex-col gap-3 px-4 py-4 sm:px-6 sm:py-5">
          <div className="h-8 w-48 rounded-control" style={{ background: skeletonBg }} />
          <div className="h-64 w-full rounded-card" style={{ background: skeletonBg }} />
        </div>
      ) : null}
      <DivKitContent
        ref={contentRef}
        surfaceKey={endpoint}
        card={contentJson as ContentCard}
        theme={theme}
        onAction={onAction}
      />
    </>
  );
}
