/** Explicit action refreshes are scoped to their originating tab, independently of SSE. */
const EVENT = "onno:surface-refresh";

export function requestSurfaceRefresh(path: string): void {
  window.dispatchEvent(new CustomEvent<string>(EVENT, { detail: path }));
}

export function subscribeSurfaceRefresh(path: string, refresh: () => void): () => void {
  const listener = (event: Event) => {
    if ((event as CustomEvent<string>).detail === path) refresh();
  };
  window.addEventListener(EVENT, listener);
  return () => window.removeEventListener(EVENT, listener);
}

/** Capture the source before the async action runs: focus may move to another tab meanwhile. */
export function surfaceRefreshCallback(source: Element | null, fallbackPath?: string): () => void {
  const path = source?.closest<HTMLElement>("[data-onno-surface-path]")?.dataset.onnoSurfacePath
    ?? fallbackPath;
  return () => {
    if (path != null) requestSurfaceRefresh(path);
  };
}
