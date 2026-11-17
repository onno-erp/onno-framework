import type { ComponentPropsWithRef, MouseEvent } from "react";
import { openRoute, routeHref } from "../../lib/navigation";

/** Props of {@link RouteLink}: an anchor's props, with `to` (an app path or `onno://` link) for `href`. */
export type RouteLinkProps = Omit<ComponentPropsWithRef<"a">, "href"> & { to: string };

/**
 * A real link to an app page or record: a plain click opens it in the workspace (keeping its tabs
 * and panes), while ⌘/Ctrl-click, middle-click, `target="_blank"` and "copy link" use its browser
 * URL. External URLs and command links render no `href` and never navigate.
 */
export function RouteLink({ to, onClick, target, download, ...props }: RouteLinkProps) {
  const href = routeHref(to);

  function handleClick(event: MouseEvent<HTMLAnchorElement>) {
    onClick?.(event);
    if (!href || event.defaultPrevented || event.button !== 0
      || event.metaKey || event.ctrlKey || event.shiftKey || event.altKey
      || (target && target.toLowerCase() !== "_self")
      || (download != null && download !== false)) return;
    event.preventDefault();
    openRoute(to);
  }

  return <a {...props} href={href} target={target} download={download} onClick={handleClick} />;
}
