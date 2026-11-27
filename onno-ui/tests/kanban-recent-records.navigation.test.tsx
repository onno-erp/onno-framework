import { afterEach, describe, expect, it, vi } from "vitest";
import { cleanup, fireEvent, render, screen } from "@testing-library/react";
import type { ReactNode } from "react";
import { setModel } from "@/lib/model-store";
import { Kanban } from "@/components/widgets/kanban";
import { RecentRecords } from "@/components/widgets/recent-records";

const api = vi.hoisted(() => ({ listCatalogPage: vi.fn(), listDocumentsPage: vi.fn(), getJson: vi.fn().mockResolvedValue({}) }));
vi.mock("@/lib/api", () => ({ api }));
vi.mock("@/lib/aggregate-data", () => ({ useEntityVersion: () => 0 }));

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

/** Load a model holding SalesOrders as a catalog or document, writable or not. */
function model(entityType: "catalog" | "document", canWrite = true) {
  const kind = entityType === "catalog" ? "catalogs" : "documents";
  setModel({
    user: { username: "u", displayName: "U", avatarUrl: "", roles: [], permissions: [], unrestricted: false },
    roles: [],
    features: { comments: false, notifications: false },
    informationRegisters: [],
    constants: [],
    entities: [{ kind, name: "sales_orders", logicalName: "SalesOrders", title: "Sales orders", context: "",
      route: `/${kind}/sales_orders`, canWrite }],
  });
}

function mockRows() {
  const rows = [{ id: "r-1", description: "Example record", posted: false }];
  api.listCatalogPage.mockResolvedValue({ rows, hasMore: false, nextCursor: null });
  api.listDocumentsPage.mockResolvedValue({ rows, hasMore: false, nextCursor: null });
  vi.stubGlobal("fetch", vi.fn().mockResolvedValue(new Response(JSON.stringify({ rows }), {
    headers: { "Content-Type": "application/json" },
  })));
}

describe("dashboard records use workspace navigation", () => {
  it.each(["catalog", "document"] as const)("opens a %s from the list without requiring a Router", async (type) => {
    mockRows();
    model(type);
    const dispatch = vi.spyOn(window, "dispatchEvent");
    render(<RecentRecords title="Records" entity="SalesOrders" primary={(r) => r.description as string} />);
    fireEvent.click(await screen.findByRole("button", { name: /Example record/ }));
    expect(dispatch).toHaveBeenCalledWith(expect.objectContaining({
      type: "onno:open", detail: `/${type}s/sales_orders/r-1`,
    }));
  });

  it.each(["catalog", "document"] as const)("opens a read-only %s kanban card", async (type) => {
    mockRows();
    model(type, false);
    const dispatch = vi.spyOn(window, "dispatchEvent");
    render(<Kanban title="Records" entity="SalesOrders" caption={(r) => r.description as string} />);
    const record = await screen.findAllByText("Example record");
    fireEvent.click(record[0]);
    expect(dispatch).toHaveBeenCalledWith(expect.objectContaining({
      type: "onno:open", detail: `/${type}s/sales_orders/r-1`,
    }));
    expect(screen.getByTestId("card")).toHaveAttribute("data-drag-disabled", "true");
  });

  it("does not enable catalog posting mutations even for a writable catalog", async () => {
    mockRows();
    model("catalog");
    render(<Kanban title="Records" entity="SalesOrders" caption={(r) => r.description as string} />);
    await screen.findAllByText("Example record");
    expect(screen.getByTestId("card")).toHaveAttribute("data-drag-disabled", "true");
  });

  it("does not navigate while a document card is being dragged", async () => {
    mockRows();
    model("document");
    drag.active = true;
    const dispatch = vi.spyOn(window, "dispatchEvent");
    render(<Kanban title="Records" entity="SalesOrders" caption={(r) => r.description as string} />);
    fireEvent.click((await screen.findAllByText("Example record"))[0]);
    expect(dispatch).not.toHaveBeenCalled();
    expect(screen.getByTestId("card")).toHaveAttribute("data-drag-disabled", "false");
  });
});

describe("record components take typed where and render functions", () => {
  it("RecentRecords sends where as JSON and renders primary/secondary/amount", async () => {
    model("document");
    const rows = [{ id: "r-1", number: "SO-1", customerDisplay: "Acme", statusDisplay: "Open", total: 10, date: "2026-11-01T00:00:00" }];
    const fetchMock = vi.fn().mockResolvedValue(new Response(JSON.stringify({ rows })));
    vi.stubGlobal("fetch", fetchMock);
    render(<RecentRecords entity="SalesOrders" limit={5} where={{ posted: true }}
      primary={(o) => `${o.number} · ${o.customerDisplay}`} secondary={(o) => o.statusDisplay as string}
      amount={(o) => Number(o.total) * 3} />);

    expect(await screen.findByText("SO-1 · Acme")).toBeTruthy();
    expect(screen.getByText("Open")).toBeTruthy();
    expect(screen.getByText("30.00")).toBeTruthy();
    const query = new URL(fetchMock.mock.calls[0][0] as string, "http://x").searchParams;
    expect(query.get("where")).toBe(JSON.stringify({ posted: true }));
    expect(query.get("sort")).toBe("date");
    expect(query.get("filter")).toBeNull();
  });

  it("Kanban lists with where and renders caption/primary/secondary", async () => {
    model("document");
    api.listDocumentsPage.mockResolvedValue({ rows: [{ id: "r-1", number: "SO-1", subject: "Ship pallets", posted: false }], hasMore: false, nextCursor: null });
    render(<Kanban entity="SalesOrders" where={{ total: { gte: 100 } }} caption={(o) => `#${o.number}`}
      primary={(o) => o.subject as string} secondary={() => "second line"} />);

    expect(await screen.findByText("#SO-1")).toBeTruthy();
    expect(screen.getByText("Ship pallets")).toBeTruthy();
    expect(screen.getByText("second line")).toBeTruthy();
    expect(api.listDocumentsPage).toHaveBeenCalledWith("sales_orders", undefined, undefined, { total: { gte: 100 } }, { limit: 500 });
  });
});
