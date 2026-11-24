/**
 * What the workspace's "New tab" palette offers: every screen the navigation reaches, then a New
 * form for every entity the viewer may create. Pure data, so the palette only renders and filters.
 */

import type { ModelEntity } from "./app-model";
import type { IconSource } from "./icons";
import { navigationLinks } from "./navigation-tree";
import type { ShellNavNode, ShellNavSection } from "./view-types";

export type NewTabDestination = {
  /** The route the destination opens; unique within the list. */
  path: string;
  label: string;
  /** Where it sits in the navigation ("Sales", "Sales / Work"); absent for a top-level screen. */
  hint?: string;
  icon?: IconSource;
  group: "screens" | "create";
};

type DestinationSource = {
  navigation: readonly ShellNavSection[];
  entities: readonly ModelEntity[];
  /** The app's landing route; listed first when the navigation does not already reach it. */
  home: string;
  homeLabel: string;
  homeIcon?: IconSource;
  /** Entity titles by route, as the shell localized them. */
  titles?: Record<string, string>;
  icons?: Record<string, IconSource>;
  /** The route of the entity the island currently shows; its New form leads the "create" group. */
  currentEntityRoute?: string;
};

function screensOf(items: readonly ShellNavNode[], trail: string[]): NewTabDestination[] {
  return items.flatMap((item) => {
    if ("items" in item) return screensOf(item.items, [...trail, item.title]);
    const hint = trail.filter(Boolean).join(" / ");
    return [{ path: item.path, label: item.label, hint: hint || undefined, icon: item.icon, group: "screens" as const }];
  });
}

/** Every destination, screens in navigation order first; a route listed twice keeps its first entry. */
export function newTabDestinations(source: DestinationSource): NewTabDestination[] {
  const { navigation, entities, home, homeLabel, homeIcon, titles, icons, currentEntityRoute } = source;
  const screens = navigation.flatMap((section) => screensOf(section.items, section.title ? [section.title] : []));
  const reachesHome = navigation.some((section) => navigationLinks(section.items).some((item) => item.path === home));
  const title = (entity: ModelEntity) => titles?.[entity.route] ?? entity.title;
  const create = entities
    .filter((entity) => entity.canWrite && (entity.kind === "documents" || entity.kind === "catalogs"))
    .sort((a, b) =>
      Number(b.route === currentEntityRoute) - Number(a.route === currentEntityRoute)
      || (a.kind === b.kind ? 0 : a.kind === "documents" ? -1 : 1)
      || title(a).localeCompare(title(b)))
    .map((entity): NewTabDestination => ({
      path: `${entity.route}/new`,
      label: title(entity),
      icon: icons?.[entity.route],
      group: "create",
    }));
  const all = [
    ...(reachesHome ? [] : [{ path: home, label: homeLabel, icon: homeIcon, group: "screens" as const }]),
    ...screens,
    ...create,
  ];
  const seen = new Set<string>();
  return all.filter((destination) => !seen.has(destination.path) && seen.add(destination.path));
}

function normalized(text: string): string {
  return text.toLocaleLowerCase().normalize("NFKD").replace(/\p{Diacritic}/gu, "");
}

/**
 * The destinations matching a typed query: every word must appear in the label or its hint. A label
 * that starts with the query ranks above one that merely contains it; otherwise the order is kept.
 */
export function filterDestinations(destinations: readonly NewTabDestination[], query: string): NewTabDestination[] {
  const words = normalized(query).split(/\s+/).filter(Boolean);
  if (words.length === 0) return [...destinations];
  const first = words[0];
  // Screens stay ahead of New forms; inside a group a label starting with the query leads.
  const rank = (destination: NewTabDestination) =>
    (destination.group === "screens" ? 0 : 2) + (normalized(destination.label).startsWith(first) ? 0 : 1);
  return destinations
    .filter((destination) => {
      const text = normalized(`${destination.label} ${destination.hint ?? ""}`);
      return words.every((word) => text.includes(word));
    })
    .map((destination, index) => ({ destination, index }))
    .sort((a, b) => rank(a.destination) - rank(b.destination) || a.index - b.index)
    .map(({ destination }) => destination);
}
