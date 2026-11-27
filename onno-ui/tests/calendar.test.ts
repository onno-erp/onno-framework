import { describe, expect, it } from "vitest";
import { calendarEvent, calendarMoveValues, catalogWindowParams, formatDuration, timedMinutes, weekTitle, windowStart } from "@/components/widgets/calendar";

describe("calendarMoveValues", () => {
  it("keeps a custom business start field aligned with the document date", () => {
    const move = calendarMoveValues(
      "starts_at",
      "ends_at",
      "2026-07-27T10:00:00.000Z",
      "2026-07-27T12:00:00.000Z"
    );

    expect(move.optimistic).toEqual({
      date: "2026-07-27T10:00:00.000Z",
      startsAt: "2026-07-27T10:00:00.000Z",
      endsAt: "2026-07-27T12:00:00.000Z",
    });
    expect(move.payload).toEqual({
      date: "2026-07-27T10:00:00.000Z",
      startsAt: "2026-07-27T10:00:00.000Z",
      endsAt: "2026-07-27T12:00:00.000Z",
    });
  });

  it("does not duplicate the default document date field", () => {
    expect(calendarMoveValues("_date", undefined, "2026-07-27T10:00:00.000Z", undefined))
      .toEqual({
        optimistic: {
          date: "2026-07-27T10:00:00.000Z",
        },
        payload: {
          date: "2026-07-27T10:00:00.000Z",
        },
      });
  });
});

describe("catalog calendars", () => {
  it("writes only the configured fields, since catalogs have no system date", () => {
    expect(calendarMoveValues("startsAt", "endsAt", "2026-07-27T10:00:00", "2026-07-27T12:00:00", false)).toEqual({
      optimistic: { startsAt: "2026-07-27T10:00:00", endsAt: "2026-07-27T12:00:00" },
      payload: { startsAt: "2026-07-27T10:00:00", endsAt: "2026-07-27T12:00:00" },
    });
  });

  it("windows rows on the start column with whole-day bounds", () => {
    const params = catalogWindowParams("startsAt", new Date(2026, 9, 1), new Date(2026, 9, 31, 23, 59), { status: { not: "DONE" } });
    expect(params.getAll("ge")).toEqual(["starts_at,2026-10-01"]);
    expect(params.getAll("le")).toEqual(["starts_at,2026-10-31"]);
    expect(params.get("where")).toBe(JSON.stringify({ status: { not: "DONE" } }));
    expect(params.get("filter")).toBeNull();
  });
});

describe("calendar items", () => {
  const row = { id: "e-1", number: "EV-1", startsAt: "2026-10-01T10:00:00", endsAt: "2026-10-01T11:00:00",
    subject: "Kick-off", customerDisplay: "Acme", total: 120, posted: true, currency: "EUR" };
  const base = { kind: "document" as const, start: "startsAt", end: "endsAt", allDay: false };

  it("labels, colours and prices items through the render functions", () => {
    const event = calendarEvent(row, {
      ...base,
      title: (r) => String(r.subject),
      subtitle: (r) => `for ${r.customerDisplay}`,
      color: (r) => (r.posted ? "#0B8043" : undefined),
      amount: (r) => Number(r.total) * 2,
      currency: (r) => String(r.currency),
    })!;
    expect(event).toMatchObject({ id: "e-1", title: "Kick-off", start: row.startsAt, end: row.endsAt, borderColor: "#0B8043",
      classNames: ["fc-onno-colored"] });
    expect(event.extendedProps).toMatchObject({ primary: "Kick-off", secondary: "for Acme", amount: 240, currency: "EUR", color: "#0B8043" });
  });

  it("falls back to the number, a reference display and the total", () => {
    const event = calendarEvent(row, { ...base, columns: [{ fieldName: "customer", type: "ref" }] })!;
    expect(event.title).toBe("EV-1");
    expect(event.extendedProps).toMatchObject({ primary: "EV-1", secondary: "Acme", amount: 120, color: undefined });
    expect(event.classNames).toEqual(["fc-onno-posted"]);
  });

  it("skips records without a start, and spans a duration in days", () => {
    expect(calendarEvent({ id: "x" }, base)).toBeNull();
    const event = calendarEvent({ id: "d", date: "2026-10-01T00:00:00", nights: 3 },
      { kind: "catalog", start: "date", duration: "nights", allDay: true })!;
    expect(new Date(event.end as string).getDate()).toBe(4);
  });
});

describe("item length", () => {
  const t = (key: string, p?: Record<string, string | number>) => (key === "calendar.hours" ? `${p?.count} h` : `${p?.count} min`);
  it("measures timed items only", () => {
    expect(timedMinutes(new Date(2026, 9, 1, 10), new Date(2026, 9, 1, 10, 30), false)).toBe(30);
    expect(timedMinutes(new Date(2026, 9, 1), new Date(2026, 9, 2), true)).toBe(0);
    expect(timedMinutes(new Date(2026, 9, 1, 10), null, false)).toBe(0);
  });
  it("formats minutes and hours", () => {
    expect(formatDuration(45, t)).toBe("45 min");
    expect(formatDuration(60, t)).toBe("1 h");
    expect(formatDuration(150, t)).toBe("2 h 30 min");
  });
});

describe("smooth scrolling windows", () => {
  it("renders room around the month or week in view", () => {
    // Month: eight weeks before the week holding the 1st (Mon 28 Sep 2026 → Mon 3 Aug).
    expect(windowStart("dayGridMonth", new Date(2026, 9, 16))).toEqual(new Date(2026, 7, 3));
    // Week: the Monday before the week in view.
    expect(windowStart("timeGridWeek", new Date(2026, 9, 16))).toEqual(new Date(2026, 9, 5));
  });

  it("titles the seven visible days across month and year boundaries", () => {
    expect(weekTitle(new Date(2026, 9, 12))).toBe("Oct 12 – 18, 2026");
    expect(weekTitle(new Date(2026, 8, 28))).toBe("Sep 28 – Oct 4, 2026");
    expect(weekTitle(new Date(2026, 11, 28))).toBe("Dec 28, 2026 – Jan 3, 2027");
  });
});
