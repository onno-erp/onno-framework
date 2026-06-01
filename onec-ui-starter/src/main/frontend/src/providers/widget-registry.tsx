import { createContext, useContext, useState, useCallback, type ReactNode, type ComponentType } from "react";
import type { DashboardWidgetMeta, AttributeMeta, EntityRecord } from "@/lib/types";

export interface DashboardWidgetProps {
  widget: DashboardWidgetMeta;
}

export interface FieldRendererProps {
  attr: AttributeMeta;
  value: unknown;
  onChange: (value: unknown) => void;
}

interface WidgetRegistryState {
  dashboardWidgets: Map<string, ComponentType<DashboardWidgetProps>>;
  fieldRenderers: Map<string, ComponentType<FieldRendererProps>>;
  registerDashboardWidget: (type: string, component: ComponentType<DashboardWidgetProps>) => void;
  registerFieldRenderer: (javaType: string, component: ComponentType<FieldRendererProps>) => void;
}

const WidgetRegistryContext = createContext<WidgetRegistryState | null>(null);

export function WidgetRegistryProvider({
  children,
  builtInDashboardWidgets = {},
}: {
  children: ReactNode;
  builtInDashboardWidgets?: Record<string, ComponentType<DashboardWidgetProps>>;
}) {
  const [dashboardWidgets, setDashboardWidgets] = useState<Map<string, ComponentType<DashboardWidgetProps>>>(
    () => new Map(Object.entries(builtInDashboardWidgets))
  );
  const [fieldRenderers, setFieldRenderers] = useState<Map<string, ComponentType<FieldRendererProps>>>(
    () => new Map()
  );

  const registerDashboardWidget = useCallback((type: string, component: ComponentType<DashboardWidgetProps>) => {
    setDashboardWidgets((prev) => new Map(prev).set(type, component));
  }, []);

  const registerFieldRenderer = useCallback((javaType: string, component: ComponentType<FieldRendererProps>) => {
    setFieldRenderers((prev) => new Map(prev).set(javaType, component));
  }, []);

  return (
    <WidgetRegistryContext.Provider
      value={{ dashboardWidgets, fieldRenderers, registerDashboardWidget, registerFieldRenderer }}
    >
      {children}
    </WidgetRegistryContext.Provider>
  );
}

export function useWidgetRegistry() {
  const ctx = useContext(WidgetRegistryContext);
  if (!ctx) throw new Error("useWidgetRegistry must be used within WidgetRegistryProvider");
  return ctx;
}
