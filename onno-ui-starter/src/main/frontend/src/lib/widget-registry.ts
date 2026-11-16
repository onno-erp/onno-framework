import type { ComponentType } from "react";
import type { DashboardWidgetMeta } from "@/lib/types";
import type { ListRendererProps } from "@/components/entity-list-widget";

/**
 * Widget registries, one per kind: dashboard/record widgets ({@code {widget}} props), list body
 * renderers ({@link ListRendererProps}) and list selection widgets. The same type name may be used
 * in each without collision.
 */
const REGISTRY: Record<string, ComponentType<{ widget: DashboardWidgetMeta }>> = Object.create(null);
const BUILTIN_WIDGET_TYPES = new Set<string>();

let version = 0;
const listeners = new Set<() => void>();

export function registerBuiltinWidget(
  widgetType: string,
  component: ComponentType<{ widget: DashboardWidgetMeta }>
) {
  BUILTIN_WIDGET_TYPES.add(widgetType);
  registerWidget(widgetType, component, { override: true });
}

/**
 * Register a dashboard/record widget renderer. Replacing a built-in type throws unless
 * {@code options.override} is true; other types are last-registration-wins. Late registrations
 * notify every lookup consumer.
 */
export function registerWidget(
  widgetType: string,
  component: ComponentType<{ widget: DashboardWidgetMeta }>,
  options?: { override?: boolean }
) {
  if (BUILTIN_WIDGET_TYPES.has(widgetType) && options?.override !== true) {
    throw new Error(
      `Widget "${widgetType}" is built in. Pass { override: true } to replace it.`
    );
  }

  REGISTRY[widgetType] = component;
  version += 1;
  for (const listener of listeners) listener();
}

export function registeredWidgetTypes(): string[] {
  return Object.keys(REGISTRY);
}

type ListRendererOptions = {
  ownsLoadingMore?: boolean;
};

const LIST_RENDERERS = new Map<
  string,
  {
    component: ComponentType<ListRendererProps>;
    ownsLoadingMore: boolean;
  }
>();

export function registerListRenderer(
  type: string,
  component: ComponentType<ListRendererProps>,
  options?: ListRendererOptions
) {
  LIST_RENDERERS.set(type, {
    component,
    ownsLoadingMore: options?.ownsLoadingMore === true,
  });

  version += 1;
  for (const listener of listeners) listener();
}

/**
 * Plugin bundles built with an SDK older than the split registries register list renderers and
 * selection widgets through registerWidget. Resolve such a non-built-in entry as a fallback (with the
 * old static ownsLoadingMore marker) so those lists keep their custom view until the bundle is
 * rebuilt; warn once per type.
 */
const warnedLegacy = new Set<string>();
function legacyEntry(kind: string, type: string): ComponentType<never> | undefined {
  if (BUILTIN_WIDGET_TYPES.has(type)) return undefined;
  const component = REGISTRY[type] as ComponentType<never> | undefined;
  if (component && !warnedLegacy.has(`${kind}:${type}`)) {
    warnedLegacy.add(`${kind}:${type}`);
    console.warn(`[onno] ${kind} "${type}" was registered with registerWidget by an outdated plugin bundle; rebuild it with the current @onno/widget-sdk.`);
  }
  return component;
}

export function resolveListRenderer(type: string) {
  const registered = LIST_RENDERERS.get(type);
  if (registered) return registered;
  const legacy = legacyEntry("List renderer", type) as
    (ComponentType<ListRendererProps> & { ownsLoadingMore?: boolean }) | undefined;
  return legacy ? { component: legacy, ownsLoadingMore: legacy.ownsLoadingMore === true } : undefined;
}

type ListSelectionProps = {
  ids: string[];
  complete: () => void;
};

const LIST_SELECTIONS = new Map<string, ComponentType<ListSelectionProps>>();

export function registerListSelection(
  type: string,
  component: ComponentType<ListSelectionProps>
) {
  LIST_SELECTIONS.set(type, component);

  version += 1;
  for (const listener of listeners) listener();
}

export function resolveListSelection(type: string) {
  return LIST_SELECTIONS.get(type)
    ?? (legacyEntry("Selection widget", type) as ComponentType<ListSelectionProps> | undefined);
}

export function resolveWidget(widgetType: string): ComponentType<never> | undefined {
  return REGISTRY[widgetType] as ComponentType<never> | undefined;
}

export function subscribeRegistry(listener: () => void): () => void {
  listeners.add(listener);
  return () => listeners.delete(listener);
}

export function getRegistryVersion(): number {
  return version;
}
