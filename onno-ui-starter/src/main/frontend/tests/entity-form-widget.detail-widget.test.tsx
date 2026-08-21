import { afterEach, describe, expect, it, vi } from "vitest";
import { cleanup, render, screen } from "@testing-library/react";

const widgetBridge = vi.hoisted(() => ({
  registry: new Map<string, unknown>(),
  listeners: new Set<() => void>(),
  version: 0,
}));

vi.mock("@/lib/api", () => ({
  api: {},
  ApiError: class ApiError extends Error {},
}));
vi.mock("@/components/ref-select", () => ({ RefSelect: () => null }));
vi.mock("@/components/date-picker", () => ({ DatePicker: () => null }));
vi.mock("@/components/map-editor", () => ({ MapEditor: () => null }));
vi.mock("@/components/image-picker", () => ({ ImagePicker: () => null, GalleryPicker: () => null }));
vi.mock("@/components/file-picker", () => ({ FilePicker: () => null }));
vi.mock("@/components/related-list-panel", () => ({ RelatedListPanel: () => null }));
vi.mock("@/lib/actions-menu-bridge", () => ({ ActionsCluster: () => null }));
vi.mock("@/lib/widget-registry", () => ({
  registerWidget: (type: string, component: unknown) => {
    widgetBridge.registry.set(type, component);
    widgetBridge.version += 1;
    widgetBridge.listeners.forEach((listener) => listener());
  },
  resolveWidget: (type: string) => widgetBridge.registry.get(type),
  subscribeRegistry: (listener: () => void) => {
    widgetBridge.listeners.add(listener);
    return () => widgetBridge.listeners.delete(listener);
  },
  getRegistryVersion: () => widgetBridge.version,
}));

import { EntityFormWidget, type FormDescriptor } from "@/components/entity-form-widget";
import { registerWidget } from "@/lib/widget-registry";

afterEach(cleanup);

function form(id: string | null): FormDescriptor {
  return {
    kind: "catalogs",
    name: "products",
    id,
    title: id ? "Edit Product" : "New Product",
    submitLabel: "Save",
    readOnly: true,
    meta: {
      name: "Products",
      attributes: [],
      detailWidgets: [{
        title: "Product summary",
        widgetType: "testRecordSummary",
        order: 0,
        width: "full",
        entityType: "catalog",
        entityName: "Products",
        maxItems: 10,
        dateField: "",
        titleField: "",
        extraConfig: { tone: "quiet" },
      }],
    },
    initial: id ? { _id: id, description: "Tea" } : null,
  };
}

describe("record detail widgets", () => {
  it("passes saved-record context to the registered renderer", () => {
    registerWidget("testRecordSummary", ({ widget }) => (
      <div data-testid="record-widget">
        {widget.record?.kind}:{widget.record?.name}:{widget.record?.id}:
        {String(widget.record?.data.description)}:{String(widget.record?.readOnly)}
      </div>
    ));

    render(<EntityFormWidget form={form("p-1")} />);

    expect(screen.getByTestId("record-widget").textContent)
      .toBe("catalogs:products:p-1:Tea:true");
  });

  it("does not render a detail widget before the record is saved", () => {
    render(<EntityFormWidget form={form(null)} />);
    expect(screen.queryByTestId("record-widget")).toBeNull();
  });
});
