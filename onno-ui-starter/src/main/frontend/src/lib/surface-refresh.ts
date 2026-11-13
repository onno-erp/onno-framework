import { createContext, useContext, useEffect, useRef } from "react";
import type { UiEvent } from "@/lib/types";

/** Portal hosts keep the DOM surface identity even though React portals live in the shell. */
export const SurfaceHost = createContext<HTMLElement | null>(null);

function surfacePath(source: Element | null): string | undefined {
  return source?.closest<HTMLElement>("[data-onno-surface-path]")?.dataset.onnoSurfacePath;
}

export function requestSurfaceRefresh(path: string): void {
  // A local invalidation, not a fabricated entity mutation or a request to replace the DivKit card.
  window.dispatchEvent(new CustomEvent<UiEvent>("onno:dataevent", {
    detail: { type: "refresh", surfacePath: path },
  }));
}

export function subscribeSurfaceRefresh(path: string | (() => string | undefined), refresh: () => void): () => void {
  const listener = (event: Event) => {
    const detail = (event as CustomEvent<UiEvent>).detail;
    const target = typeof path === "function" ? path() : path;
    if (target != null && detail?.type === "refresh" && detail.surfacePath === target) refresh();
  };
  window.addEventListener("onno:dataevent", listener);
  return () => window.removeEventListener("onno:dataevent", listener);
}

/** Each data-owning island reuses its own soft refresh, preserving local controls and drafts. */
export function useSurfaceRefresh(refresh: () => void): void {
  const host = useContext(SurfaceHost);
  const refreshRef = useRef(refresh);
  refreshRef.current = refresh;
  useEffect(() => {
    if (!host) return;
    return subscribeSurfaceRefresh(() => host.isConnected ? surfacePath(host) : undefined,
      () => refreshRef.current());
  }, [host]);
}

/** Capture the source before the async action runs: focus may move to another tab meanwhile. */
export function surfaceRefreshCallback(source: Element | null, fallbackPath?: string): () => void {
  const path = surfacePath(source) ?? fallbackPath;
  return () => {
    if (path != null) requestSurfaceRefresh(path);
  };
}
