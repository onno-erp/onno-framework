import { describe, expect, it } from "vitest";
import { applyListOptions } from "@/lib/entity-customizations";
import type { ListDescriptor } from "@/components/lists/entity-list";

const list = {
  kind: "documents",
  name: "schedule_events",
  title: "Team events",
  columns: [
    { columnName: "starts_at", fieldName: "startsAt", label: "Starts", width: "" },
    { columnName: "ends_at", fieldName: "endsAt", label: "Ends", width: "" },
    { columnName: "organizer", fieldName: "organizer", label: "Organizer", width: "" },
  ],
  searchable: true,
  sort: { column: null, descending: false },
  newUrl: null,
} as unknown as ListDescriptor;

describe("calendar and timeline list options", () => {
  it("map field names to the feed's row keys and data columns", () => {
    const out = applyListOptions(list, {
      calendar: { start: "startsAt", end: "endsAt", title: "subject", mode: "week" },
      timeline: { start: "startsAt", end: "endsAt", lane: "organizer", laneEntity: "Employees", slotMinutes: 30, startHour: 7, endHour: 22, defaultView: true },
    });
    expect(out.calendar).toMatchObject({ startField: "startsAt", startColumn: "starts_at", endField: "endsAt", endColumn: "ends_at", titleField: "subject", mode: "week" });
    expect(out.timeline).toMatchObject({
      startColumn: "starts_at", endColumn: "ends_at", laneField: "organizer", laneColumn: "organizer",
      laneEntity: "Employees", slotMinutes: 30, startHour: 7, endHour: 22, defaultView: true,
    });
  });
});
