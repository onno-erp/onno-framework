import type { ComponentType, ReactNode } from "react";
import type { ViewModel } from "./view-types";

/**
 * Route matching for the app's pages. A page replaces what a route renders with a React component:
 *
 *   { path: "/documents/orders", component: OrdersPage }      // one exact route
 *   { path: "/documents/orders/:id", component: OrderRecord } // a record surface; params.id
 *   { path: "/reports/*", component: ReportsArea }            // a whole subtree; params["*"]
 *
 * Among patterns matching a path the most specific wins (more literal segments, then fewer
 * wildcards); for the same pattern the later one.
 */
export type RouteParams = Record<string, string>;

export type RouteProps = {
  /** The route path being rendered (no query string). */
  path: string;
  /** Named `:param` and `*` captures from the matched pattern. */
  params: RouteParams;
  /** The query string parameters of the route. */
  query: URLSearchParams;
  /**
   * The server's default view for this route, or null while loading / when the route has no server
   * view (a purely client-side page). The same contract `<DefaultView>` renders.
   */
  view: ViewModel | null;
  /** Renders the framework's default view for this route — wrap or extend it. */
  renderDefault: () => ReactNode;
};

export type RouteOptions = {
  /**
   * Skip fetching the server's view for this route. Use for purely client-side pages that don't
   * need the default (no `/api/ui/view` round trip, and no 404 when the server has no page there).
   */
  clientOnly?: boolean;
};

export type Route = { pattern: string; segments: string[]; Component: ComponentType<RouteProps>; options: RouteOptions };

export type MatchedRoute = { Component: ComponentType<RouteProps>; params: RouteParams; options: RouteOptions };

function split(path: string): string[] {
  return path.split("?")[0].split("/").filter(Boolean);
}

/** A route for a pattern. */
export function route(pattern: string, Component: ComponentType<RouteProps>, options: RouteOptions = {}): Route {
  const segments = split(pattern);
  return { pattern: "/" + segments.join("/"), segments, Component, options };
}

function match(entry: Route, path: string[]): RouteParams | null {
  const params: RouteParams = {};
  for (let i = 0; i < entry.segments.length; i++) {
    const seg = entry.segments[i];
    if (seg === "*") {
      params["*"] = path.slice(i).join("/");
      return params;
    }
    const value = path[i];
    if (value === undefined) return null;
    if (seg.startsWith(":")) params[seg.slice(1)] = decodeURIComponent(value);
    else if (seg !== value) return null;
  }
  return path.length === entry.segments.length ? params : null;
}

function specificity(entry: Route): number {
  let score = 0;
  for (const seg of entry.segments) {
    if (seg === "*") score -= 1;
    else if (!seg.startsWith(":")) score += 10;
    else score += 1;
  }
  return score;
}

/** The most specific route matching a path, or null. */
export function matchRoute(routes: readonly Route[], path: string): MatchedRoute | null {
  const segs = split(path);
  let best: { entry: Route; params: RouteParams } | null = null;
  for (let i = routes.length - 1; i >= 0; i--) {
    const params = match(routes[i], segs);
    if (params && (!best || specificity(routes[i]) > specificity(best.entry))) {
      best = { entry: routes[i], params };
    }
  }
  return best ? { Component: best.entry.Component, params: best.params, options: best.entry.options } : null;
}
