import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import { Lock, LogIn, SearchX, TriangleAlert } from "lucide-react";
import { Button } from "../components/ui/button";
import { useMessages } from "../providers/messages-provider";
import { RouteView } from "./route-view";
import { usePageRoute } from "../lib/app-config";
import type { ViewModel } from "../lib/view-types";
import { isEntitySurface } from "../lib/app-model";
import { DefaultHome } from "./default-home";
import { linkedWhere } from "../lib/links";

/**
 * A list route opened from an `onno://` list link (`…?where=<json>`): its list shows only the
 * records the link selects, on top of the app's customization.
 */
function withLinkedWhere(view: ViewModel, path: string): ViewModel {
  const where = linkedWhere(path);
  if (!where) return view;
  return "list" in view ? { list: { ...view.list, baseWhere: where } } : view;
}

/** An action a surface raises to the shell: an `onno://` URL or an app route (see AppShell). */
export type ContentAction = { url?: string };

/** Fetch the server view for a route; rejects with a {@link PaneError}. */
export function viewEndpoint(path: string): string {
  // A form path may carry its own query (…/new?startsAt=…&room=<id> for a prefilled New form);
  // it rides along as the form's prefill.
  const [pathname, search] = path.split("?");
  const route = pathname === "/" || pathname === "" ? "" : pathname;
  return search ? `/api/ui/view${route}?${search}` : `/api/ui/view${route}`;
}

// The initial load's failure, kept as status + the server's reason (Spring's error JSON
// `message`, present when the app sets server.error.include-message=always) so the error
// surface can shape its copy by status instead of dumping "HTTP 403".
type PaneError = { status: number | null; detail: string | null };

/**
 * The pane-level error surface — what renders when a route's content fetch fails outright.
 * Copy is shaped by status: 401 → the session is gone (offer sign-in), 403 → RBAC denied this
 * route (this is what a user without read access to an entity sees), 404 → nothing served here
 * (unknown entity / stale link), anything else → generic with retry. All strings localize via
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
 * One content surface — the body of an island (or the whole content area in the non-desktop
 * layouts). Owns its own fetch and skeleton/error for {@code path}, so several can render side by
 * side without sharing state. The view is the screen's shape, not its data: the widgets in it read
 * their data live, so a server change never remounts the pane.
 *
 * A route an app overrode with {@code registerRoute(...)} renders that component instead, handed
 * the server's default view (unless the override is client-only).
 */
export function ContentPane({
  path,
  onAction,
  skeletonBg,
  onNotFound,
}: {
  path: string;
  onAction: (action: ContentAction) => void;
  skeletonBg: string;
  // Called when this surface's route resolves to 404 for the current user (e.g. a stale
  // post-login `from`, or a deep link to a route this user can't reach). The primary pane
  // uses it to fall back to the shell's home instead of stranding the user on a 404.
  onNotFound?: (path: string) => void;
}) {
  const [content, setContent] = useState<{ key: string; view: ViewModel } | null>(null);
  const [error, setError] = useState<PaneError | null>(null);
  const override = usePageRoute(path);
  // Only the model's entity surfaces have a server view; every other route is client-side.
  const serverView = isEntitySurface(path);
  const clientOnly = override?.options.clientOnly === true || !serverView;
  const pathname = path.split("?")[0];

  const endpoint = useMemo(() => viewEndpoint(path), [path]);

  const endpointRef = useRef(endpoint);
  endpointRef.current = endpoint;
  const pathRef = useRef(path);
  pathRef.current = path;
  const onNotFoundRef = useRef(onNotFound);
  onNotFoundRef.current = onNotFound;

  // Initial view load per surface — a skeleton until it lands, the status-shaped error surface if it
  // fails (403 access denied, 404 no view, …). Also the Retry handler.
  const load = useCallback(() => {
    const ep = endpointRef.current;
    setContent(null);
    setError(null);
    fetch(ep, { credentials: "include" })
      .then(async (r) => {
        if (r.ok) return r.json() as Promise<ViewModel>;
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
        return Promise.reject<never>({ status: r.status, detail } satisfies PaneError);
      })
      .then((view) => {
        if (endpointRef.current === ep) setContent({ key: ep, view });
      })
      .catch((e: unknown) => {
        if (endpointRef.current !== ep) return;
        const pe = e as Partial<PaneError>;
        // A 404 means this route doesn't exist for the current user; let the host redirect to
        // home rather than leaving a dead surface. Access-denied (403) still shows its message.
        if (pe?.status === 404 && onNotFoundRef.current && !override) {
          onNotFoundRef.current(pathRef.current);
          return;
        }
        setError({ status: typeof pe?.status === "number" ? pe.status : null, detail: pe?.detail ?? null });
      });
    // `override` only gates the 404 redirect; reading it at call time is enough.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  useEffect(() => {
    if (clientOnly) return;
    load();
  }, [endpoint, load, clientOnly]);

  // A client route nobody registered: home falls back to the default overview, anything else is
  // not found (the primary pane redirects to the shell's home).
  // The app's pages are registered before the shell mounts, so an unmatched route is final.
  const pendingClientRoute = false;
  const unknownClientRoute = !serverView && !override && pathname !== "/" && pathname !== "";
  useEffect(() => {
    if (unknownClientRoute) onNotFoundRef.current?.(pathRef.current);
  }, [unknownClientRoute]);

  const view = useMemo(
    () => (content && content.key === endpoint ? withLinkedWhere(content.view, path) : null),
    [content, endpoint, path],
  );
  const renderDefault = () =>
    view ? <RouteView view={view} /> : null;

  let body;
  if (override && (clientOnly || view || error?.status === 404)) {
    const { Component, params } = override;
    const [pathname, search] = path.split("?");
    body = (
      <Component
        path={pathname}
        params={params}
        query={new URLSearchParams(search ?? "")}
        view={view}
        renderDefault={renderDefault}
      />
    );
  } else if (pendingClientRoute) {
    body = (
      <div className="flex flex-col gap-3 px-4 py-4 sm:px-6 sm:py-5">
        <div className="h-8 w-48 rounded-field" style={{ background: skeletonBg }} />
        <div className="h-64 w-full rounded-card" style={{ background: skeletonBg }} />
      </div>
    );
  } else if (!serverView) {
    body = unknownClientRoute
      ? <PaneErrorState error={{ status: 404, detail: null }} onRetry={load} onAction={onAction} />
      : <DefaultHome />;
  } else if (error) {
    body = <PaneErrorState error={error} onRetry={load} onAction={onAction} />;
  } else if (!view) {
    body = (
      <div className="flex flex-col gap-3 px-4 py-4 sm:px-6 sm:py-5">
        <div className="h-8 w-48 rounded-field" style={{ background: skeletonBg }} />
        <div className="h-64 w-full rounded-card" style={{ background: skeletonBg }} />
      </div>
    );
  } else {
    body = renderDefault();
  }

  return (
    <div className="onno-surface min-h-full">
      {body}
    </div>
  );
}
