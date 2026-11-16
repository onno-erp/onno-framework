import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { act, cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { format } from "date-fns";

// #428: the calendar and timeline list views read the list feed with the list's query, open the
// record on click, and save a drag through the generic update command — only for a viewer who may
// write, and with exactly one message when the server refuses. The real api.ts runs; fetch, the
// toast sink and FullCalendar are stubbed.
const toast = vi.hoisted(() => ({
  error: vi.fn(),
  success: vi.fn(),
  info: vi.fn(),
  warning: vi.fn(),
  dismiss: vi.fn(),
}));
vi.mock("@/components/ui/toast", () => ({ toast }));

type FcProps = {
  events: Array<{ id: string; title: string; allDay: boolean; start: Date; end?: Date }>;
  editable: boolean;
  eventDurationEditable: boolean;
  datesSet: (arg: unknown) => void;
  eventDrop: (arg: unknown) => Promise<void>;
  eventClick: (arg: unknown) => void;
};
const fc = vi.hoisted(() => ({ props: null as null | FcProps }));
vi.mock("@fullcalendar/react", async () => {
  const React = await import("react");
  return {
    default: React.forwardRef((props: FcProps, ref) => {
      fc.props = props;
      React.useImperativeHandle(ref, () => ({ getApi: () => ({ changeView() {}, prev() {}, next() {}, today() {} }) }));
      return null;
    }),
  };
});

import { ListCalendarView, ListTimelineView, calendarEvents, calendarMovePayload } from "@/components/list-schedule-view";
import type { ListCalendarConfig, ListTimelineConfig } from "@/lib/schedule";

const today = format(new Date(), "yyyy-MM-dd");
const response = (status: number, body: unknown) => new Response(JSON.stringify(body), { status });

type Call = { url: string; method: string; body?: unknown };
let calls: Call[] = [];
let putStatus = 200;
let appointments: Record<string, unknown>[] = [];

beforeEach(() => {
  calls = [];
  putStatus = 200;
  appointments = [{
    id: "a1", number: "AP-1", patient: "p1", patientDisplay: "Rex",
    startsAt: `${today}T10:00:00`, endsAt: `${today}T10:30:00`, doctor: "d1", doctorDisplay: "Dr. Ada",
  }];
  vi.stubGlobal("matchMedia", () => ({ matches: false, addEventListener() {}, removeEventListener() {} }));
  vi.stubGlobal("ResizeObserver", class { observe() {} unobserve() {} disconnect() {} });
  vi.stubGlobal("fetch", vi.fn(async (url: string, init?: RequestInit) => {
    const method = (init?.method ?? "GET").toUpperCase();
    calls.push({ url, method, body: init?.body ? JSON.parse(String(init.body)) : undefined });
    if (url.startsWith("/api/list/catalogs/doctors?")) {
      return response(200, { rows: [{ id: "d1", description: "Dr. Ada" }, { id: "d2", description: "Dr. Bob" }] });
    }
    if (url.startsWith("/api/list/documents/appointments?")) return response(200, { rows: appointments, hasMore: false });
    if (url === "/api/documents/appointments/a1" && method === "PUT") {
      return putStatus === 200 ? response(200, {}) : response(putStatus, { message: "The doctor is busy" });
    }
    throw new Error(`unexpected ${method} ${url}`);
  }));
});

afterEach(() => {
  cleanup();
  vi.unstubAllGlobals();
  vi.clearAllMocks();
  fc.props = null;
});

const timeline: ListTimelineConfig = {
  startField: "startsAt", startColumn: "starts_at", endField: "endsAt", endColumn: "ends_at",
  laneField: "doctor", laneColumn: "doctor", laneKind: "catalogs", laneTarget: "Doctors", laneLabel: "Doctor",
  titleField: "patient", slotMinutes: 10, startHour: 8, endHour: 21,
};

function renderTimeline(canWrite: boolean, config: ListTimelineConfig = timeline) {
  return render(
    <ListTimelineView kind="documents" name="appointments" feedBase="/api/list/documents/appointments"
      query="q=rex" config={config} canWrite={canWrite} />
  );
}

const feedCalls = () => calls.filter((c) => c.url.startsWith("/api/list/documents/appointments?"));
const puts = () => calls.filter((c) => c.method === "PUT");

/** A pointer event with real coordinates: jsdom's PointerEvent does not carry MouseEvent init fields. */
function pointer(type: string, x: number, y: number) {
  const event = new MouseEvent(type, { bubbles: true, cancelable: true, button: 0, clientX: x, clientY: y });
  Object.defineProperty(event, "pointerType", { value: "mouse" });
  Object.defineProperty(event, "pointerId", { value: 1 });
  return event;
}

/** Drag the block (or its resize handle) down by `minutes`; slot 10 → 2.8px per minute. */
async function drag(target: Element, minutes: number, dx = 0) {
  await act(async () => {
    fireEvent(target, pointer("pointerdown", 100, 100));
    fireEvent(target, pointer("pointermove", 100 + dx, 100 + minutes * 2.8));
    fireEvent(target, pointer("pointerup", 100 + dx, 100 + minutes * 2.8));
  });
}

describe("ListTimelineView", () => {
  it("draws a lane per reference record and reads the feed with the list's query and the day window", async () => {
    renderTimeline(true);

    const block = await screen.findByRole("button", { name: /Rex 10:00–10:30/ });
    expect(block).toBeTruthy();
    expect(screen.getAllByRole("columnheader").map((h) => h.textContent)).toEqual(["Dr. Ada", "Dr. Bob"]);
    // The idle doctor still has a column; the block sits in its own doctor's lane.
    expect(block.closest("[data-onno-lane]")?.getAttribute("data-onno-lane")).toBe("d1");

    const queries = feedCalls().map((c) => new URLSearchParams(c.url.split("?")[1]));
    expect(queries).toHaveLength(2);
    for (const q of queries) expect(q.get("q")).toBe("rex");
    expect(queries[0].getAll("le")).toEqual([`starts_at,${today}`]);
    expect(queries[0].getAll("ge")).toEqual([`ends_at,${today}`]);
    expect(queries[1].getAll("ge")).toEqual([`starts_at,${today}`]);
  });

  it("collects records without a lane value under Unassigned", async () => {
    appointments = [{ ...appointments[0], doctor: null, doctorDisplay: null }];
    renderTimeline(true);

    const block = await screen.findByRole("button", { name: /Rex/ });
    expect(screen.getAllByRole("columnheader").map((h) => h.textContent)).toEqual(["Dr. Ada", "Dr. Bob", "Unassigned"]);
    expect(block.closest("[data-onno-lane]")?.getAttribute("data-onno-lane")).toBe("");
  });

  it("opens the record on click", async () => {
    const opened = vi.fn();
    window.addEventListener("onno:action", opened);
    try {
      renderTimeline(false);
      fireEvent.click(await screen.findByRole("button", { name: /Rex/ }));
      expect(opened).toHaveBeenCalledOnce();
      expect((opened.mock.calls[0][0] as CustomEvent).detail).toBe("onno://documents/appointments/a1");
    } finally {
      window.removeEventListener("onno:action", opened);
    }
  });

  it("saves a move through the update command, snapped to the slot, without opening the record", async () => {
    const opened = vi.fn();
    window.addEventListener("onno:action", opened);
    try {
      renderTimeline(true);
      const block = await screen.findByRole("button", { name: /Rex/ });

      await drag(block, 58);
      fireEvent.click(block); // the click a real pointer-up would produce

      await waitFor(() => expect(puts()).toHaveLength(1));
      expect(puts()[0].url).toBe("/api/documents/appointments/a1");
      // +58 min snaps to +60; only the changed fields are sent — the lane was not touched.
      expect(puts()[0].body).toEqual({ startsAt: `${today}T11:00:00`, endsAt: `${today}T11:30:00` });
      expect(opened).not.toHaveBeenCalled();
      expect(await screen.findByRole("button", { name: /Rex 11:00–11:30/ })).toBeTruthy();
    } finally {
      window.removeEventListener("onno:action", opened);
    }
  });

  it("rewrites the lane reference when a block is dropped in another lane", async () => {
    renderTimeline(true);
    const block = await screen.findByRole("button", { name: /Rex/ });
    // jsdom has no layout: give the two lanes side-by-side rectangles.
    for (const [id, left] of [["d1", 0], ["d2", 300]] as const) {
      const el = document.querySelector(`[data-onno-lane="${id}"]`) as HTMLElement;
      el.getBoundingClientRect = () => ({ left, right: left + 300, top: 0, bottom: 1000, width: 300, height: 1000, x: left, y: 0, toJSON() {} });
    }

    await drag(block, 0, 350);

    await waitFor(() => expect(puts()).toHaveLength(1));
    expect(puts()[0].body).toEqual({ doctor: "d2" });
  });

  it("saves a resize of the lower edge as a new end only", async () => {
    renderTimeline(true);
    const block = await screen.findByRole("button", { name: /Rex/ });

    await drag(block.querySelector("[data-onno-resize]")!, 20);

    await waitFor(() => expect(puts()).toHaveLength(1));
    expect(puts()[0].body).toEqual({ endsAt: `${today}T10:50:00` });
  });

  it("does not drag or offer a resize handle for a read-only viewer", async () => {
    renderTimeline(false);
    const block = await screen.findByRole("button", { name: /Rex/ });

    expect(block.querySelector("[data-onno-resize]")).toBeNull();
    await drag(block, 60);

    expect(puts()).toHaveLength(0);
  });

  it("refetches and reports the server's refusal once when a move is rejected", async () => {
    putStatus = 422;
    renderTimeline(true);
    const block = await screen.findByRole("button", { name: /Rex/ });
    const before = feedCalls().length;

    await drag(block, 60);

    await waitFor(() => expect(toast.error).toHaveBeenCalledOnce());
    expect(toast.error.mock.calls[0][0]).toContain("The doctor is busy");
    await waitFor(() => expect(feedCalls().length).toBeGreaterThan(before));
  });

  it("refetches when a live change to the entity arrives", async () => {
    renderTimeline(true);
    await screen.findByRole("button", { name: /Rex/ });
    const before = feedCalls().length;

    act(() => {
      window.dispatchEvent(new CustomEvent("onno:dataevent", { detail: { type: "updated", entityType: "document", entityName: "Appointments" } }));
      window.dispatchEvent(new CustomEvent("onno:dataevent", { detail: { type: "updated", entityType: "catalog", entityName: "Doctors" } }));
    });

    await waitFor(() => expect(feedCalls().length).toBe(before + 2));
  });
});

describe("calendar list view", () => {
  const config: ListCalendarConfig = { startField: "startsAt", startColumn: "starts_at", endField: "endsAt", endColumn: "ends_at", titleField: "patient" };

  it("maps timed and all-day rows to events, a date-only end being the inclusive last day", () => {
    const events = calendarEvents([
      { id: "a", patient: "Rex", startsAt: "2026-11-14T10:00:00", endsAt: "2026-11-14T10:30:00" },
      { id: "b", number: "B-2", startsAt: "2026-11-14", endsAt: "2026-11-16" },
      { id: "c", startsAt: null },
    ], config);

    expect(events.map((e) => [e.id, e.title, e.allDay])).toEqual([["a", "Rex", false], ["b", "B-2", true]]);
    expect((events[1].end as Date).getDate()).toBe(17);
  });

  it("builds the partial update in each field's own shape", () => {
    expect(calendarMovePayload({ id: "a", startsAt: "2026-11-14T10:00:00", endsAt: "2026-11-14T10:30:00" }, config,
      new Date(2026, 10, 15, 9, 0), new Date(2026, 10, 15, 9, 30)))
      .toEqual({ startsAt: "2026-11-15T09:00:00", endsAt: "2026-11-15T09:30:00" });
    // All-day: FullCalendar's exclusive end goes back to the inclusive last day.
    expect(calendarMovePayload({ id: "b", startsAt: "2026-11-14", endsAt: "2026-11-16" }, config,
      new Date(2026, 10, 20), new Date(2026, 10, 23)))
      .toEqual({ startsAt: "2026-11-20", endsAt: "2026-11-22" });
    // No end field declared, or no end on the dropped event: only the start is written.
    expect(calendarMovePayload({ id: "c", startsAt: "2026-11-14" }, { startField: "startsAt" }, new Date(2026, 10, 20), new Date(2026, 10, 21)))
      .toEqual({ startsAt: "2026-11-20" });
    expect(calendarMovePayload({ id: "d", startsAt: "2026-11-14T10:00:00" }, config, new Date(2026, 10, 20, 8, 0), null))
      .toEqual({ startsAt: "2026-11-20T08:00:00" });
  });

  async function renderCalendar(canWrite: boolean) {
    render(<ListCalendarView kind="documents" name="appointments" feedBase="/api/list/documents/appointments"
      query="eq=status%2COPEN" config={config} canWrite={canWrite} />);
    await waitFor(() => expect(fc.props).not.toBeNull());
    // FullCalendar reports the visible range (end exclusive); that is the window the feed is asked for.
    act(() => fc.props!.datesSet({ start: new Date(2026, 10, 9), end: new Date(2026, 10, 16), view: { title: "Nov 9 – 15, 2026" } }));
    await waitFor(() => expect(fc.props!.events).toHaveLength(1));
  }

  it("loads the visible range through the list feed with the list's filters", async () => {
    await renderCalendar(true);

    const queries = feedCalls().map((c) => new URLSearchParams(c.url.split("?")[1]));
    expect(queries[0].getAll("eq")).toEqual(["status,OPEN"]);
    expect(queries[0].getAll("le")).toEqual(["starts_at,2026-11-15"]);
    expect(queries[0].getAll("ge")).toEqual(["ends_at,2026-11-09"]);
    expect(fc.props!.editable).toBe(true);
    expect(fc.props!.eventDurationEditable).toBe(true);
    expect(screen.getByText("Nov 9 – 15, 2026")).toBeTruthy();
  });

  it("is not editable for a read-only viewer", async () => {
    await renderCalendar(false);
    expect(fc.props!.editable).toBe(false);
    expect(fc.props!.eventDurationEditable).toBe(false);
  });

  it("saves a drop through the update command and reverts on refusal with one message", async () => {
    await renderCalendar(true);
    const revert = vi.fn();
    await act(async () => {
      await fc.props!.eventDrop({ event: { id: "a1", start: new Date(2026, 10, 12, 9, 0), end: new Date(2026, 10, 12, 9, 30) }, revert });
    });
    expect(puts()[0].body).toEqual({ startsAt: "2026-11-12T09:00:00", endsAt: "2026-11-12T09:30:00" });
    expect(revert).not.toHaveBeenCalled();

    putStatus = 409;
    await act(async () => {
      await fc.props!.eventDrop({ event: { id: "a1", start: new Date(2026, 10, 13, 9, 0), end: new Date(2026, 10, 13, 9, 30) }, revert });
    });
    expect(revert).toHaveBeenCalledOnce();
    expect(toast.error).toHaveBeenCalledOnce();
    expect(toast.error.mock.calls[0][0]).toContain("The doctor is busy");
  });

  it("opens the record on click", async () => {
    const opened = vi.fn();
    window.addEventListener("onno:action", opened);
    try {
      await renderCalendar(false);
      act(() => fc.props!.eventClick({ event: { id: "a1" }, jsEvent: { preventDefault() {} } }));
      expect((opened.mock.calls[0][0] as CustomEvent).detail).toBe("onno://documents/appointments/a1");
    } finally {
      window.removeEventListener("onno:action", opened);
    }
  });
});
