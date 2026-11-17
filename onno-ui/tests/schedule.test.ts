import { describe, expect, it } from "vitest";
import {
  dateAtMinutes,
  dragResult,
  isDateOnly,
  minutesFromDay,
  overlapLayout,
  parseTemporal,
  refId,
  rowAccent,
  rowTitle,
  snapMinutes,
  windowQueries,
  writeTemporal,
} from "@/lib/schedule";

// #428: the framework-free pieces of the calendar / timeline list views.

describe("temporal round-trip", () => {
  it("parses a bare date as local midnight, not UTC midnight", () => {
    const d = parseTemporal("2026-11-14")!;
    expect([d.getFullYear(), d.getMonth(), d.getDate(), d.getHours()]).toEqual([2026, 10, 14, 0]);
    expect(isDateOnly("2026-11-14")).toBe(true);
    expect(isDateOnly("2026-11-14T10:00:00")).toBe(false);
  });

  it("parses a zone-less date-time as wall-clock time and ignores junk", () => {
    const d = parseTemporal("2026-11-14T10:30:00")!;
    expect([d.getHours(), d.getMinutes()]).toEqual([10, 30]);
    expect(parseTemporal("")).toBeNull();
    expect(parseTemporal(null)).toBeNull();
    expect(parseTemporal("soon")).toBeNull();
  });

  it("writes a value back in the shape the field already had", () => {
    const at = new Date(2026, 10, 14, 9, 5, 0);
    expect(writeTemporal(at, "2026-01-01")).toBe("2026-11-14");
    expect(writeTemporal(at, "2026-01-01T00:00:00")).toBe("2026-11-14T09:05:00");
    expect(writeTemporal(at, undefined)).toBe("2026-11-14T09:05:00");
    expect(writeTemporal(at, "2026-01-01T00:00:00Z")).toBe(at.toISOString());
    expect(writeTemporal(at, "2026-01-01T00:00:00+03:00")).toBe(at.toISOString());
  });
});

describe("windowQueries", () => {
  const from = new Date(2026, 10, 9);
  const to = new Date(2026, 10, 15);

  it("bounds the start column and keeps the list's own query", () => {
    const [only, ...rest] = windowQueries("q=rex&eq=status%2COPEN", "starts_at", undefined, from, to);
    expect(rest).toHaveLength(0);
    expect(only.get("q")).toBe("rex");
    expect(only.getAll("eq")).toEqual(["status,OPEN"]);
    expect(only.getAll("ge")).toEqual(["starts_at,2026-11-09"]);
    expect(only.getAll("le")).toEqual(["starts_at,2026-11-15"]);
  });

  it("asks for overlapping records, plus those starting inside with no end yet", () => {
    const [overlaps, startsInside] = windowQueries("", "starts_at", "ends_at", from, to);
    expect(overlaps.getAll("le")).toEqual(["starts_at,2026-11-15"]);
    expect(overlaps.getAll("ge")).toEqual(["ends_at,2026-11-09"]);
    expect(startsInside.getAll("ge")).toEqual(["starts_at,2026-11-09"]);
    expect(startsInside.getAll("le")).toEqual(["starts_at,2026-11-15"]);
  });

  it("appends to a date-range filter on the same column instead of replacing it", () => {
    const [only] = windowQueries("ge=starts_at%2C2026-11-10", "starts_at", undefined, from, to);
    expect(only.getAll("ge")).toEqual(["starts_at,2026-11-10", "starts_at,2026-11-09"]);
  });
});

describe("row presentation", () => {
  it("titles an entry from the configured field's display value, else a system identifier", () => {
    expect(rowTitle({ id: "1", patient: "p-1", patientDisplay: "Rex", number: "A-1" }, "patient")).toBe("Rex");
    expect(rowTitle({ id: "1", subject: "Vaccination" }, "subject")).toBe("Vaccination");
    expect(rowTitle({ id: "1", subject: " ", number: "A-1" }, "subject")).toBe("A-1");
    expect(rowTitle({ id: "1" })).toBe("");
  });

  it("uses an authored enum colour, else a stable hue per value, else nothing", () => {
    expect(rowAccent({ status: "u-1", statusColor: "#0B8043" }, "status")).toBe("#0B8043");
    const hue = rowAccent({ doctor: "d-1" }, "doctor");
    expect(hue).toMatch(/^hsl\(\d+ 65% 45%\)$/);
    expect(rowAccent({ doctor: "d-1" }, "doctor")).toBe(hue);
    expect(rowAccent({ doctor: null }, "doctor")).toBeNull();
    expect(rowAccent({ doctor: "d-1" })).toBeNull();
  });

  it("reads a reference id from a bare value or a storage-shaped object", () => {
    expect(refId("d-1")).toBe("d-1");
    expect(refId({ id: "d-2" })).toBe("d-2");
    expect(refId(null)).toBe("");
  });
});

describe("timeline geometry", () => {
  const day = new Date(2026, 10, 14);

  it("converts between dates and minutes of the day", () => {
    expect(minutesFromDay(new Date(2026, 10, 14, 9, 30), day)).toBe(570);
    expect(minutesFromDay(new Date(2026, 10, 13, 23, 0), day)).toBe(-60);
    const at = dateAtMinutes(day, 570);
    expect([at.getDate(), at.getHours(), at.getMinutes()]).toEqual([14, 9, 30]);
    const midnight = dateAtMinutes(day, 1440);
    expect([midnight.getDate(), midnight.getHours()]).toEqual([15, 0]);
    expect(snapMinutes(577, 10)).toBe(580);
    expect(snapMinutes(574, 10)).toBe(570);
  });

  it("lays overlapping blocks side by side and leaves the rest full width", () => {
    const layout = overlapLayout([
      { id: "a", start: 540, end: 600 },
      { id: "b", start: 570, end: 630 },
      { id: "c", start: 600, end: 660 },
      { id: "d", start: 720, end: 750 },
    ]);
    expect(layout.get("a")).toEqual({ column: 0, columns: 2 });
    expect(layout.get("b")).toEqual({ column: 1, columns: 2 });
    // c starts when a ends, so it reuses a's column inside the same cluster.
    expect(layout.get("c")).toEqual({ column: 0, columns: 2 });
    expect(layout.get("d")).toEqual({ column: 0, columns: 1 });
  });

  it("moves a block by whole slots, keeping its length inside the visible day", () => {
    const bounds = { start: 480, end: 1260 };
    const origin = { start: 540, end: 570 };
    expect(dragResult("move", origin, 34, 10, bounds)).toEqual({ start: 570, end: 600 });
    expect(dragResult("move", origin, -500, 10, bounds)).toEqual({ start: 480, end: 510 });
    expect(dragResult("move", origin, 5000, 10, bounds)).toEqual({ start: 1230, end: 1260 });
  });

  it("resizes the end by whole slots, never shorter than one slot or past the day", () => {
    const bounds = { start: 480, end: 1260 };
    const origin = { start: 540, end: 570 };
    expect(dragResult("resize", origin, 28, 10, bounds)).toEqual({ start: 540, end: 600 });
    expect(dragResult("resize", origin, -300, 10, bounds)).toEqual({ start: 540, end: 550 });
    expect(dragResult("resize", origin, 5000, 10, bounds)).toEqual({ start: 540, end: 1260 });
  });
});
