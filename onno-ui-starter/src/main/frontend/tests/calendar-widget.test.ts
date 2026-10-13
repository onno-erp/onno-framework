import { describe, expect, it } from "vitest";
import { calendarMoveValues, catalogWindowParams, facetParams, formatDuration, timedMinutes, watchedChange, weekTitle, windowStart } from "@/components/calendar-widget";

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
    const params = catalogWindowParams("startsAt", new Date(2026, 9, 1), new Date(2026, 9, 31, 23, 59), "status != 'DONE'");
    expect(params.getAll("ge")).toEqual(["starts_at,2026-10-01"]);
    expect(params.getAll("le")).toEqual(["starts_at,2026-10-31"]);
    expect(params.get("filter")).toBe("status != 'DONE'");
  });
});

describe("source calendars", () => {
  const meta = {
    sources: [],
    watches: [],
    facets: [
      { key: "owner", label: "Planner", options: [{ id: "a", label: "Ann" }], me: "me-id" },
      { key: "client", label: "Client", options: [{ id: "c", label: "Cleo" }], me: null },
    ],
  };

  it("turns filter choices into facet params, resolving Only me", () => {
    expect(facetParams(meta, { owner: "__me", client: "c" })).toEqual(["owner:me-id", "client:c"]);
    expect(facetParams(meta, { owner: "__all" })).toEqual([]);
    expect(facetParams(null, { owner: "a" })).toEqual([]);
  });

  it("refreshes only for watched entities, whatever their casing", () => {
    expect(watchedChange({ type: "updated", entityName: "follow_ups" }, ["FollowUps"])).toBe(true);
    expect(watchedChange({ type: "updated", entityName: "Contacts" }, ["FollowUps"])).toBe(false);
    expect(watchedChange(undefined, ["FollowUps"])).toBe(false);
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
