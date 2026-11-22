import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { act, cleanup, render, screen } from "@testing-library/react";
import type { ReactNode } from "react";
import { setModel } from "@/lib/model-store";
import { queryClient } from "@/lib/query";

// A board grouped by an enumeration (a column per value, in order) or a reference (a column per
// referenced record plus "no value"); dropping a card in a column writes the field.
vi.mock("@/components/ui/toast", () => ({ toast: { error: vi.fn(), success: vi.fn(), info: vi.fn(), warning: vi.fn(), dismiss: vi.fn() } }));
vi.mock("react-router", async (importOriginal) => ({
  ...(await importOriginal<typeof import("react-router")>()),
  useNavigate: () => vi.fn(),
}));
const dnd = vi.hoisted(() => ({ onDragEnd: null as null | ((result: unknown) => Promise<void>) }));
vi.mock("@hello-pangea/dnd", () => ({
  DragDropContext: ({ onDragEnd, children }: { onDragEnd: (result: unknown) => Promise<void>; children: ReactNode }) => {
    dnd.onDragEnd = onDragEnd;
    return <>{children}</>;
  },
  Droppable: ({ droppableId, children }: { droppableId: string; children: (provided: unknown, snapshot: unknown) => ReactNode }) =>
    <div data-testid={`column-${droppableId}`}>{children({ innerRef: () => {}, droppableProps: {}, placeholder: null }, { isDraggingOver: false })}</div>,
  Draggable: ({ children }: { children: (provided: unknown, snapshot: unknown) => ReactNode }) =>
    <>{children({ innerRef: () => {}, draggableProps: {}, dragHandleProps: {} }, { isDragging: false })}</>,
}));

import { Kanban, kanbanColumns } from "@/components/widgets/kanban";

const model = () => setModel({
  user: { username: "u", displayName: "U", avatarUrl: "", roles: [], permissions: [], unrestricted: false },
  roles: [],
  features: { comments: false, notifications: false },
  informationRegisters: [],
  constants: [],
  entities: [{ kind: "documents", name: "orders", logicalName: "Orders", title: "Orders", context: "",
    route: "/documents/orders", canWrite: true }],
});

const NEW = "u-new";
const SHIPPED = "u-shipped";
const rows = [
  { id: "o-1", number: "SO-1", status: SHIPPED, assignedTo: "e-2", assignedToDisplay: "Zoe", assignedToRef: { id: "e-2" } },
  { id: "o-2", number: "SO-2", status: NEW, assignedTo: "e-1", assignedToDisplay: "Ann", assignedToRef: { id: "e-1" } },
  { id: "o-3", number: "SO-3", status: null, assignedTo: null },
];
const view: { list: { columns: { fieldName: string; type: string }[]; filters: { key: string; type: string; options: { value: string; label: string; color?: string }[] }[] } } = {
  list: {
    columns: [{ fieldName: "status", type: "enum" }, { fieldName: "assignedTo", type: "ref" }],
    filters: [{ key: "status", type: "multiOptions", options: [
      { value: NEW, label: "New", color: "#ff0000" },
      { value: "u-confirmed", label: "Confirmed" },
      { value: SHIPPED, label: "Shipped" },
    ] }],
  },
};

const json = (body: unknown, status = 200) => new Response(JSON.stringify(body), { status });
const requests: { method: string; url: string; body?: unknown }[] = [];

function serve() {
  vi.stubGlobal("fetch", vi.fn(async (url: string, init?: RequestInit) => {
    const method = (init?.method ?? "GET").toUpperCase();
    requests.push({ method, url, body: init?.body ? JSON.parse(String(init.body)) : undefined });
    if (url.startsWith("/api/list/documents/orders")) return json({ rows });
    if (url === "/api/ui/view/documents/orders") return json(view);
    if (url === "/api/documents/orders/o-2" && method === "PUT") return json({ ...rows[1], status: SHIPPED });
    throw new Error(`unexpected ${method} ${url}`);
  }));
}

beforeEach(model);
afterEach(() => {
  cleanup();
  vi.unstubAllGlobals();
  queryClient.clear();
  requests.length = 0;
  dnd.onDragEnd = null;
});

describe("Kanban grouping", () => {
  it("groups by an enumeration: a column per value in declaration order, then no value", () => {
    const columns = kanbanColumns("document", "status", { type: "enum", options: view.list.filters[0].options }, rows);
    expect(columns.map((c) => c.label)).toEqual(["New", "Confirmed", "Shipped", "—"]);
    expect(columns.map((c) => rows.filter(c.match).map((r) => r.id))).toEqual([["o-2"], [], ["o-1"], ["o-3"]]);
    expect(columns[0].color).toBe("#ff0000");
  });

  it("groups by a reference: a column per referenced record by label, always with no value", () => {
    const columns = kanbanColumns("document", "assignedTo", { type: "ref" }, rows);
    expect(columns.map((c) => [c.key, c.label])).toEqual([["e-1", "Ann"], ["e-2", "Zoe"], ["__none__", "—"]]);
    expect(columns[2].optimistic!(rows[0])).toMatchObject({ assignedTo: null, assignedToDisplay: null });
  });

  it("keeps Draft / Posted for documents", () => {
    expect(kanbanColumns("document", "posted", undefined, rows).map((c) => c.key)).toEqual(["draft", "posted"]);
  });

  it("tells an enumeration by the list's enum filter and a reference by the records' Ref companion", async () => {
    serve();
    view.list.columns = [];
    try {
      render(<Kanban title="Orders" entity="Orders" by="status" />);
      expect(await screen.findByText("Confirmed")).toBeTruthy();
      cleanup();
      render(<Kanban title="Orders" entity="Orders" by="assignedTo" />);
      expect(await screen.findByText("Ann")).toBeTruthy();
    } finally {
      view.list.columns = [{ fieldName: "status", type: "enum" }, { fieldName: "assignedTo", type: "ref" }];
    }
  });

  it("writes the field when a card is dropped in another column", async () => {
    serve();
    render(<Kanban title="Orders" entity="Orders" by="status" />);
    expect(await screen.findByText("Confirmed")).toBeTruthy();
    expect(screen.getByTestId(`column-${NEW}`).textContent).toContain("SO-2");
    await act(async () => {
      await dnd.onDragEnd!({ draggableId: "o-2", source: { droppableId: NEW, index: 0 }, destination: { droppableId: SHIPPED, index: 0 } });
    });
    expect(requests.find((r) => r.method === "PUT")).toEqual({
      method: "PUT", url: "/api/documents/orders/o-2", body: { status: SHIPPED },
    });
  });
});
