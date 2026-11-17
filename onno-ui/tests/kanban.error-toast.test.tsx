import { afterEach, describe, expect, it, vi } from "vitest";
import { act, cleanup, render, screen } from "@testing-library/react";
import type { ReactNode } from "react";
import { setModel } from "@/lib/model-store";

// #505: dragging a card across columns posts/unposts the document; a failure shows exactly one
// message. The real api.ts runs; fetch, the toast sink and the drag-and-drop library are stubbed.
const toast = vi.hoisted(() => ({
  error: vi.fn(),
  success: vi.fn(),
  info: vi.fn(),
  warning: vi.fn(),
  dismiss: vi.fn(),
}));
vi.mock("@/components/ui/toast", () => ({ toast }));

vi.mock("react-router", async (importOriginal) => ({
  ...(await importOriginal<typeof import("react-router")>()),
  useNavigate: () => vi.fn(),
}));

// Render the board's render-prop children and capture onDragEnd, so a test can drop a card directly.
const dnd = vi.hoisted(() => ({ onDragEnd: null as null | ((result: unknown) => Promise<void>) }));
vi.mock("@hello-pangea/dnd", () => ({
  DragDropContext: ({ onDragEnd, children }: { onDragEnd: (result: unknown) => Promise<void>; children: ReactNode }) => {
    dnd.onDragEnd = onDragEnd;
    return <>{children}</>;
  },
  Droppable: ({ children }: { children: (provided: unknown, snapshot: unknown) => ReactNode }) =>
    <>{children({ innerRef: () => {}, droppableProps: {}, placeholder: null }, { isDraggingOver: false })}</>,
  Draggable: ({ children }: { children: (provided: unknown, snapshot: unknown) => ReactNode }) =>
    <>{children({ innerRef: () => {}, draggableProps: {}, dragHandleProps: {} }, { isDragging: false })}</>,
}));

import { Kanban } from "@/components/widgets/kanban";

const response = (status: number, body: unknown) => new Response(JSON.stringify(body), { status });

// The board resolves `entity` (and the viewer's write right) from the loaded model.
setModel({
  user: { username: "u", displayName: "U", avatarUrl: "", roles: [] },
  features: { comments: false, notifications: false },
  informationRegisters: [],
  constants: [],
  entities: [{ kind: "documents", name: "orders", logicalName: "Orders", title: "Orders", context: "",
    route: "/documents/orders", canWrite: true }],
});

afterEach(() => {
  cleanup();
  vi.unstubAllGlobals();
  vi.clearAllMocks();
  dnd.onDragEnd = null;
});

/** Drag the draft order SO-1 into "Posted"; the post request answers with `post`. */
async function dragToPosted(post: () => Response) {
  vi.stubGlobal("fetch", vi.fn(async (url: string, init?: RequestInit) => {
    const method = (init?.method ?? "GET").toUpperCase();
    if (url.startsWith("/api/list/documents/orders")) return response(200, { rows: [{ id: "o-1", number: "SO-1", posted: false }] });
    if (url === "/api/documents/orders/o-1/post" && method === "POST") return post();
    throw new Error(`unexpected ${method} ${url}`);
  }));
  render(<Kanban title="Orders" entity="Orders" />);
  await screen.findByText("SO-1");
  await act(async () => {
    await dnd.onDragEnd!({
      draggableId: "o-1",
      source: { droppableId: "draft", index: 0 },
      destination: { droppableId: "posted", index: 0 },
    });
  });
  await new Promise((resolve) => setTimeout(resolve, 0));
}

describe("Kanban move failure (#505)", () => {
  it("shows the api layer's toast once when the server rejects the post", async () => {
    await dragToPosted(() => response(409, { message: "Insufficient stock" }));

    expect(toast.error.mock.calls).toEqual([["Insufficient stock"]]);
  });

  it("reports a field-bound posting rule, which the api layer leaves to its caller, once", async () => {
    await dragToPosted(() => response(400, {
      error: "validation",
      message: "Quantity must be positive",
      fieldErrors: { quantity: ["Quantity must be positive"] },
    }));

    expect(toast.error.mock.calls).toEqual([["Quantity must be positive"]]);
  });

  it("reports a network failure once", async () => {
    await dragToPosted(() => {
      throw new TypeError("Failed to fetch");
    });

    expect(toast.error.mock.calls).toEqual([["Failed to fetch"]]);
  });

  it("reports typed feedback, which the api layer leaves to its caller, once", async () => {
    await dragToPosted(() => response(409, {
      severity: "error",
      title: "Posting blocked",
      message: "The period is closed",
    }));

    expect(toast.error.mock.calls).toEqual([["The period is closed"]]);
  });

  it("reports a lapsed session (401), which the api layer leaves to its caller, once", async () => {
    await dragToPosted(() => response(401, { error: "unauthenticated" }));

    expect(toast.error.mock.calls).toEqual([["unauthenticated"]]);
  });
});
