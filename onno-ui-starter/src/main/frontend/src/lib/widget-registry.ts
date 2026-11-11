import type { ComponentType } from "react";
import type { DashboardWidgetMeta } from "@/lib/types";
import type { ListRendererProps } from "@/components/entity-list-widget";

/** Lightweight widget-type registry shared by dashboard islands and record forms. */
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

/** Register or replace a widget renderer. Late registrations notify every lookup consumer. */
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

export function resolveListRenderer(type: string) {
  return LIST_RENDERERS.get(type);
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
  return LIST_SELECTIONS.get(type);
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