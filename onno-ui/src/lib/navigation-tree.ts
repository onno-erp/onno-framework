import type { ShellNavItem, ShellNavNode } from "./view-types";

/** All destinations, depth first; shared by tabs, route metadata and mobile roots. */
export function navigationLinks(
  items: readonly ShellNavNode[],
): ShellNavItem[] {
  return items.flatMap((item) =>
    "items" in item ? navigationLinks(item.items) : [item],
  );
}
export function navigationActive(
  items: readonly ShellNavNode[],
  path: string,
): boolean {
  const route = path.split("?")[0];
  return navigationLinks(items).some((item) =>
    item.path === "/"
      ? route === "/"
      : route === item.path || route.startsWith(item.path + "/"),
  );
}
