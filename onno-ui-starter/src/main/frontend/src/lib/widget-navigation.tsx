import type { ComponentPropsWithRef, MouseEvent } from "react";
import { withBasePath } from "./base-path";
import { isAppRoute, openRoute } from "./navigation";
import { toSnakeCase } from "./utils";

type NavigateOptions = { newTab?: boolean };
type RecordKind = "catalogs" | "documents";
type LinkProps = Omit<ComponentPropsWithRef<"a">, "href"> & { to: string };

function isWidgetRoute(path: string): boolean {
  // Native links resolve dot segments and strip control characters before following the URL.
  return isAppRoute(path)
    && !/[\x00-\x1f\x7f]/.test(path)
    && !/(?:^|\/)(?:\.|%2e){1,2}(?:\/|$)/i.test(path.split(/[?#]/, 1)[0]);
}

function navigate(path: string, options?: NavigateOptions): void {
  if (!isWidgetRoute(path)) return;
  if (options?.newTab) {
    window.open(withBasePath(path), "_blank", "noopener,noreferrer");
  } else {
    openRoute(path);
  }
}

function openRecord(kind: RecordKind, name: string, id: string): void {
  if (!name || !id) return;
  // Route names are snake_case logical names (SalesOrders / "Sales Orders" → sales_orders), the
  // spelling that carries live updates and the localized tab title.
  navigate(`/${kind}/${encodeURIComponent(toSnakeCase(name))}/${encodeURIComponent(id)}`);
}

function Link({ to, onClick, target, download, ...props }: LinkProps) {
  const href = isWidgetRoute(to) ? withBasePath(to) : undefined;

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

export const widgetNavigation = Object.freeze({ navigate, openRecord, Link });
