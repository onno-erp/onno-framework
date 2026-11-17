import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { act, cleanup, render, waitFor } from "@testing-library/react";
import { setModel } from "@/lib/model-store";

// #505: dragging an event to a new date reschedules it; a failure reverts the drag and shows
// exactly one message. The real api.ts runs; fetch, the toast sink and FullCalendar are stubbed.
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

// Capture FullCalendar's props so a test can fire eventDrop directly; the ref exposes a no-op api.
const fc = vi.hoisted(() => ({ props: null as null | { eventDrop: (arg: unknown) => Promise<void> } }));
vi.mock("@fullcalendar/react", async () => {
  const React = await import("react");
  return {
    default: React.forwardRef((props: { eventDrop: (arg: unknown) => Promise<void> }, ref) => {
      fc.props = props;
      React.useImperativeHandle(ref, () => ({
        getApi: () => ({ gotoDate() {}, changeView() {}, view: { type: "dayGridMonth" }, getDate: () => new Date() }),
      }));
      return null;
    }),
  };
});

import { Calendar } from "@/components/widgets/calendar";

// The calendar resolves `entity` (and the viewer's write right) from the loaded model.
setModel({
  user: { username: "u", displayName: "U", avatarUrl: "", roles: [] },
  features: { comments: false, notifications: false },
  informationRegisters: [],
  constants: [],
  entities: [{ kind: "catalogs", name: "rooms", logicalName: "Rooms", title: "Rooms", context: "",
    route: "/catalogs/rooms", canWrite: true }],
});

type Route = (url: string, method: string) => Response;

const response = (status: number, body: unknown) => new Response(JSON.stringify(body), { status });

function stubFetch(route: Route) {
  vi.stubGlobal("fetch", vi.fn(async (url: string, init?: RequestInit) =>
    route(url, (init?.method ?? "GET").toUpperCase())));
}

beforeEach(() => {
  // jsdom has neither; the widget reads the viewport and observes its own width.
  vi.stubGlobal("matchMedia", () => ({ matches: false, addEventListener() {}, removeEventListener() {} }));
  vi.stubGlobal("ResizeObserver", class { observe() {} unobserve() {} disconnect() {} });
});

afterEach(() => {
  cleanup();
  vi.unstubAllGlobals();
  vi.clearAllMocks();
  fc.props = null;
});

/** Drop event r-1 on a new day; resolves with the drag's revert spy once the failure settled. */
async function dropEvent() {
  await waitFor(() => expect(fc.props).not.toBeNull());
  const revert = vi.fn();
  await act(async () => {
    await fc.props!.eventDrop({ event: { id: "r-1", start: new Date(2026, 10, 10), end: null, allDay: true }, revert });
  });
  await new Promise((resolve) => setTimeout(resolve, 0));
  return revert;
}

describe("Calendar reschedule failure (#505)", () => {
  describe("entity calendar", () => {
    const rooms = (put: () => Response): Route => (url, method) => {
      if (url.startsWith("/api/list/catalogs/rooms")) return response(200, { rows: [] });
      if (url === "/api/catalogs/rooms/r-1" && method === "PUT") return put();
      throw new Error(`unexpected ${method} ${url}`);
    };

    it("reverts the drag and shows the api layer's toast once", async () => {
      stubFetch(rooms(() => response(409, { message: "Room is locked" })));
      render(<Calendar title="Rooms" entity="Rooms" start="date" />);

      const revert = await dropEvent();

      expect(revert).toHaveBeenCalledOnce();
      expect(toast.error.mock.calls).toEqual([["Room is locked"]]);
    });

    it("reports a network failure once", async () => {
      stubFetch(rooms(() => {
        throw new TypeError("Failed to fetch");
      }));
      render(<Calendar title="Rooms" entity="Rooms" start="date" />);

      const revert = await dropEvent();

      expect(revert).toHaveBeenCalledOnce();
      expect(toast.error.mock.calls).toEqual([["Failed to fetch"]]);
    });
  });
});

describe("Calendar data (#widget v2)", () => {
  it("windows a catalog by its start field, sends where as JSON and titles items with the render function", async () => {
    const urls: string[] = [];
    stubFetch((url) => {
      urls.push(url);
      if (url.startsWith("/api/list/catalogs/rooms")) {
        return response(200, { rows: [{ id: "r-1", description: "Room 1", subject: "Cleaning", startsAt: "2026-11-10T10:00:00" }] });
      }
      throw new Error(`unexpected ${url}`);
    });
    render(<Calendar title="Rooms" entity="Rooms" start="startsAt" where={{ active: true }}
      itemTitle={(r) => `${r.subject} · ${r.description}`} />);

    await waitFor(() => expect((fc.props as unknown as { events?: unknown[] })?.events?.length).toBe(1));
    const query = new URL(urls[0], "http://x").searchParams;
    expect(query.get("where")).toBe(JSON.stringify({ active: true }));
    expect(query.getAll("ge")[0]).toMatch(/^starts_at,/);
    expect((fc.props as unknown as { events: Array<{ title: string }> }).events[0].title).toBe("Cleaning · Room 1");
  });
});
