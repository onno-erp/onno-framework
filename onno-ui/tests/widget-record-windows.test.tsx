import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { act, cleanup, render, screen, waitFor } from "@testing-library/react";
import type { ReactNode } from "react";
import { setModel } from "@/lib/model-store";
import { queryClient } from "@/lib/query";
import { Kanban } from "@/components/widgets/kanban";
import { Calendar } from "@/components/widgets/calendar";
import { RecentRecords } from "@/components/widgets/recent-records";
import { EntityMap } from "@/components/maps/entity-map";

vi.mock("@hello-pangea/dnd", () => ({
  DragDropContext: ({ children }: { children: ReactNode }) => <>{children}</>,
  Droppable: ({ children }: { children: (p: unknown, s: unknown) => ReactNode }) =>
    <>{children({ innerRef() {}, droppableProps: {}, placeholder: null }, { isDraggingOver: false })}</>,
  Draggable: ({ children }: { children: (p: unknown, s: unknown) => ReactNode }) =>
    <>{children({ innerRef() {}, draggableProps: {}, dragHandleProps: {} }, { isDragging: false })}</>,
}));
const calendar = vi.hoisted(() => ({ events: [] as { title?: string; extendedProps?: Record<string, unknown> }[] }));
vi.mock("@fullcalendar/react", async () => {
  const React = await import("react");
  return { default: React.forwardRef((props: { events: typeof calendar.events }, ref) => {
    calendar.events = props.events;
    React.useImperativeHandle(ref, () => ({
      getApi: () => ({ gotoDate() {}, changeView() {}, view: { type: "dayGridMonth" }, getDate: () => new Date() }),
    }));
    return null;
  }) };
});
vi.mock("@/components/maps/map-view", () => ({ MapView: () => <div data-testid="map" /> }));

const response = (body: unknown) => new Response(JSON.stringify(body), { headers: { "Content-Type": "application/json" } });
const rows = [{ id: "1", number: "AP-1", description: "Visit", date: "2026-11-20T10:00:00", posted: false,
  patient: "p-1", patientDisplay: "Rex", customerDisplay: "Wrong", lat: 1, lng: 2 }];
const view = { list: { columns: [{ fieldName: "patient", type: "ref" }] } };

beforeEach(() => {
  queryClient.clear();
  vi.stubGlobal("matchMedia", () => ({ matches: false, addEventListener() {}, removeEventListener() {} }));
  vi.stubGlobal("ResizeObserver", class { observe() {} unobserve() {} disconnect() {} });
  setModel({ user: { username: "u", displayName: "U", avatarUrl: "", roles: [], permissions: [], unrestricted: false }, roles: [],
    features: { comments: false, notifications: false }, informationRegisters: [], constants: [],
    entities: ["visits", "empty"].map(name => ({ kind: "documents" as const, name,
      logicalName: name, title: name, context: "", route: `/documents/${name}`, canWrite: false })) });
});
afterEach(() => { cleanup(); queryClient.clear(); vi.unstubAllGlobals(); });

function serve(hasMore: boolean, records = rows) {
  const fetch = vi.fn(async (input: string) => {
    if (input.startsWith("/api/ui/view/")) return response(view);
    if (input.startsWith("/api/list/documents/empty")) return response({ rows: [], hasMore: false, nextCursor: null });
    if (input.startsWith("/api/list/documents/visits")) return response({ rows: records, hasMore, nextCursor: hasMore ? "next" : null });
    throw new Error(`Unexpected request ${input}`);
  });
  vi.stubGlobal("fetch", fetch);
  return fetch;
}

describe("bounded record widgets", () => {
  it.each(["kanban", "calendar", "recent", "map"])("shows hasMore and clears it on source change in %s", async widget => {
    serve(true);
    const renderWidget = (entity: string) => widget === "kanban" ? <Kanban entity={entity} />
      : widget === "calendar" ? <Calendar entity={entity} />
      : widget === "recent" ? <RecentRecords entity={entity} /> : <EntityMap entity={entity} lat="lat" lng="lng" />;
    const ui = render(renderWidget("visits"));
    expect(await screen.findByRole("status")).toHaveTextContent("Showing the first 1 records");
    if (widget === "kanban" || widget === "recent") {
      expect(await screen.findByText("Rex")).toBeTruthy();
      expect(screen.queryByText("Wrong")).toBeNull();
    }
    if (widget === "calendar") await waitFor(() => expect(calendar.events[0]?.extendedProps?.secondary).toBe("Rex"));
    ui.rerender(renderWidget("empty"));
    await waitFor(() => expect(screen.queryByRole("status")).toBeNull());
    expect(screen.queryByText("Rex")).toBeNull();
  });

  it.each(["kanban", "calendar", "recent", "map"])("does not infer truncation from exactly 500 rows in %s", async widget => {
    // Large data is a warning only when the server says so (or the widget applies a smaller limit).
    const many = Array.from({ length: 500 }, (_, i) => ({ ...rows[0], id: String(i), number: `AP-${i}` }));
    const fetch = serve(false, many);
    render(widget === "kanban" ? <Kanban entity="visits" limit={500} />
      : widget === "calendar" ? <Calendar entity="visits" />
      : widget === "recent" ? <RecentRecords entity="visits" limit={500} /> : <EntityMap entity="visits" lat="lat" lng="lng" />);
    await waitFor(() => expect(fetch).toHaveBeenCalledWith(expect.stringContaining("/api/list/documents/visits"), expect.anything()));
    await waitFor(() => {
      if (widget === "calendar") expect(calendar.events).toHaveLength(500);
      else if (widget === "recent") expect(screen.getAllByRole("button")).toHaveLength(500);
      else if (widget === "map") expect(screen.getByTestId("map")).toBeTruthy();
      else expect(screen.getAllByText("Visit")).toHaveLength(500);
    });
    expect(screen.queryByRole("status")).toBeNull();
  });

  it("reports the actual displayed count when kanban columns or a recent list hide loaded rows", async () => {
    serve(false, Array.from({ length: 15 }, (_, i) => ({ ...rows[0], id: String(i) })));
    const ui = render(<Kanban entity="visits" />);
    expect(await screen.findByRole("status")).toHaveTextContent("Showing the first 12 records");
    ui.unmount();
    render(<RecentRecords entity="visits" limit={3} />);
    expect(await screen.findByRole("status")).toHaveTextContent("Showing the first 3 records");
  });

  it.each(["kanban", "map"])("counts actual displayed rows for fractional limits in %s", async widget => {
    serve(false, Array.from({ length: 5 }, (_, i) => ({ ...rows[0], id: String(i) })));
    render(widget === "kanban" ? <Kanban entity="visits" limit={3.5} />
      : <EntityMap entity="visits" lat="lat" lng="lng" limit={3.5} />);
    expect(await screen.findByRole("status")).toHaveTextContent("Showing the first 3 records");
  });

  it.each(["kanban", "calendar"])("ignores an old %s response when another entity was selected meanwhile", async widget => {
    let release!: (value: Response) => void;
    const old = new Promise<Response>(resolve => { release = resolve; });
    vi.stubGlobal("fetch", vi.fn(async (url: string) => url.startsWith("/api/ui/view/") ? response(view)
      : url.includes("/visits?") ? old : response({ rows: [], hasMore: false, nextCursor: null })));
    const ui = render(widget === "kanban" ? <Kanban entity="visits" /> : <Calendar entity="visits" />);
    ui.rerender(widget === "kanban" ? <Kanban entity="empty" /> : <Calendar entity="empty" />);
    await act(async () => { release(response({ rows, hasMore: true, nextCursor: "next" })); await old; });
    expect(screen.queryByRole("status")).toBeNull();
    expect(screen.queryByText("Visit")).toBeNull();
  });
});
