import type { ComponentType } from "react";
import type { DashboardWidgetMeta } from "@/lib/types";

/** Lightweight widget-type registry shared by dashboard islands and record forms. */
const REGISTRY: Record<string, ComponentType<{ widget: DashboardWidgetMeta }>> = {};
let version = 0;
const listeners = new Set<() => void>();

/** Register or replace a widget renderer. Late registrations notify every lookup consumer. */
export function registerWidget(
  widgetType: string,
  component: ComponentType<{ widget: DashboardWidgetMeta }>
) {
  REGISTRY[widgetType] = component;
  version += 1;
  for (const listener of listeners) listener();
}

export function registeredWidgetTypes(): string[] {
  return Object.keys(REGISTRY);
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
