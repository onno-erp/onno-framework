import { useCallback, useEffect, useId, useMemo, useRef, useState } from "react";
import {
  DivKitContent,
  type ContentAction,
  type ContentCard,
  type ContentHandle,
  type Delta,
} from "@/views/divkit-content";
import { SettingsPage } from "@/components/settings-page";

// Routes served as native React pages rather than server-driven DivKit surfaces.
const REACT_PAGES: Record<string, () => JSX.Element> = {
  "/settings": () => <SettingsPage />,
};

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
  const [error, setError] = useState<string | null>(null);

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
          if (!handle.applyDelta({ changes: [{ id: "onec-content", items }] })) fullReload(ep);
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

  // Initial content load per surface — show a skeleton until it lands.
  useEffect(() => {
    if (REACT_PAGES[path]) return; // React pages don't fetch a DivKit surface
    let cancelled = false;
    setContent(null);
    setError(null);
    fetch(endpoint, { credentials: "include" })
      .then((r) => (r.ok ? r.json() : Promise.reject(new Error(`HTTP ${r.status}`))))
      .then((json) => {
        if (!cancelled) setContent({ key: endpoint, json });
      })
      .catch((e: unknown) => {
        if (!cancelled) setError(e instanceof Error ? e.message : String(e));
      });
    return () => {
      cancelled = true;
    };
  }, [endpoint]);

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
        <div className="px-4 py-4 text-sm text-destructive sm:px-6 sm:py-5">
          Failed to load: {error}
        </div>
      ) : !contentJson ? (
        <div className="flex flex-col gap-3 px-4 py-4 sm:px-6 sm:py-5">
          <div className="h-8 w-48 rounded-md" style={{ background: skeletonBg }} />
          <div className="h-64 w-full rounded-xl" style={{ background: skeletonBg }} />
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
