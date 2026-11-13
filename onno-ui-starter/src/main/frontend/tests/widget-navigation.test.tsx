import { afterEach, describe, expect, it, vi } from "vitest";
import { cleanup, fireEvent, render, screen } from "@testing-library/react";
import type { ReactNode } from "react";
import type { DashboardWidgetMeta } from "@/lib/types";
import { KanbanWidget } from "@/components/kanban-widget";
import { ListWidget } from "@/components/list-widget";

const api = vi.hoisted(() => ({ listCatalog: vi.fn(), listDocuments: vi.fn() }));
vi.mock("@/lib/api", () => ({ api }));
vi.mock("@/lib/widget-data", () => ({ useWidgetLiveVersion: () => 0 }));

const drag = vi.hoisted(() => ({ active: false }));
vi.mock("@hello-pangea/dnd", () => ({
  DragDropContext: ({ children }: { children: ReactNode }) => <>{children}</>,
  Droppable: ({ children }: { children: (provided: unknown, snapshot: unknown) => ReactNode }) =>
    <>{children({ innerRef: () => {}, droppableProps: {}, placeholder: null }, { isDraggingOver: false })}</>,
  Draggable: ({ children, isDragDisabled }: {
    children: (provided: unknown, snapshot: unknown) => ReactNode;
    isDragDisabled: boolean;
  }) => <div data-testid="card" data-drag-disabled={isDragDisabled}>
    {children({ innerRef: () => {}, draggableProps: {}, dragHandleProps: {} }, { isDragging: drag.active })}
  </div>,
}));

afterEach(() => {
  cleanup();
  vi.restoreAllMocks();
  vi.unstubAllGlobals();
  vi.clearAllMocks();
  drag.active = false;
});

function widget(entityType: string, canWrite = true): DashboardWidgetMeta {
  return { title: "Records", entityName: "SalesOrders", entityType, canWrite,
    extraConfig: {}, titleField: "description" } as DashboardWidgetMeta;
}

function mockRows() {
  const rows = [{ id: "r-1", description: "Example record", posted: false }];
  api.listCatalog.mockResolvedValue(rows);
  api.listDocuments.mockResolvedValue(rows);
  vi.stubGlobal("fetch", vi.fn().mockResolvedValue(new Response(JSON.stringify({ rows }), {
    headers: { "Content-Type": "application/json" },
  })));
}

describe("dashboard records use workspace navigation", () => {
  it.each(["catalog", "document"])("opens a %s from the list without requiring a Router", async (type) => {
    mockRows();
    const dispatch = vi.spyOn(window, "dispatchEvent");
    render(<ListWidget widget={widget(type)} />);
    fireEvent.click(await screen.findByRole("button", { name: /Example record/ }));
    expect(dispatch).toHaveBeenCalledWith(expect.objectContaining({
      type: "onno:action", detail: `/${type}s/sales_orders/r-1`,
    }));
  });

  it.each(["catalog", "document"])("opens a read-only %s kanban card", async (type) => {
    mockRows();
    const dispatch = vi.spyOn(window, "dispatchEvent");
    render(<KanbanWidget widget={widget(type, false)} />);
    const record = await screen.findAllByText("Example record");
    fireEvent.click(record[0]);
    expect(dispatch).toHaveBeenCalledWith(expect.objectContaining({
      type: "onno:action", detail: `/${type}s/sales_orders/r-1`,
    }));
    expect(screen.getByTestId("card")).toHaveAttribute("data-drag-disabled", "true");
  });

  it("does not enable catalog posting mutations even for a writable catalog", async () => {
    mockRows();
    render(<KanbanWidget widget={widget("catalog")} />);
    await screen.findAllByText("Example record");
    expect(screen.getByTestId("card")).toHaveAttribute("data-drag-disabled", "true");
  });

  it("does not navigate while a document card is being dragged", async () => {
    mockRows();
    drag.active = true;
    const dispatch = vi.spyOn(window, "dispatchEvent");
    render(<KanbanWidget widget={widget("document")} />);
    fireEvent.click((await screen.findAllByText("Example record"))[0]);
    expect(dispatch).not.toHaveBeenCalled();
    expect(screen.getByTestId("card")).toHaveAttribute("data-drag-disabled", "false");
  });
});
