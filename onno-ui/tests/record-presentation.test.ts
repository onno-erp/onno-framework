import { describe, expect, it } from "vitest";
import { pickAvatar, recordDetails, recordTitle } from "@/lib/record-presentation";
import { calendarEvent } from "@/components/widgets/calendar";

const columns = [
  { fieldName: "_number", type: "text" },
  { fieldName: "patient", type: "ref" },
  { fieldName: "subject", type: "text" },
  { fieldName: "state", type: "enum" },
  { fieldName: "total", type: "number" },
];
const row = { number: "AP-1", patient: "uuid-1", patientDisplay: "Rex", patientAvatar: "/rex.png",
  subject: "Check-up", state: "NEW", stateDisplay: "New", total: 25,
  customerDisplay: "Unrelated", customerAvatar: "/unrelated.png" };

describe("metadata-driven record labels", () => {
  it("uses declared fields in metadata order, independent of app names and JSON key order", () => {
    expect(recordDetails(row, columns)).toEqual(["Rex", "Check-up", "New"]);
    expect(recordDetails(row, [...columns].reverse())).toEqual(["New", "Check-up", "Rex"]);
    expect(pickAvatar(row, columns)).toEqual({ url: "/rex.png", label: "Rex" });
  });

  it("does not guess undeclared fields or show raw reference ids as labels", () => {
    expect(recordDetails({ ...row, patientDisplay: null, subject: " ", stateDisplay: "" }, columns))
      .toEqual(["NEW"]);
    expect(recordDetails(row, [])).toEqual([]);
    expect(pickAvatar(row, [])).toEqual({});
    expect(recordDetails({ password: "__SECRET_SET__" }, [{ fieldName: "password", type: "text" }])).toEqual([]);
    expect(pickAvatar({ patientAvatar: 42 }, columns)).toEqual({});
  });

  it("normalizes metadata keys and skips blank, duplicate and identity values", () => {
    expect(recordDetails({ number: "AP-1", assignedToDisplay: "Rex", note: "AP-1", other: "Rex" }, [
      { fieldName: "assigned_to", type: "ref" }, { fieldName: "note", type: "text" }, { fieldName: "other", type: "text" },
    ])).toEqual(["Rex"]);
    expect(recordTitle({ description: " ", number: 0, name: "Not a system field" })).toBe("0");
    expect(recordTitle({ id: "uuid", name: "Not a system field" })).toBe("");
  });

  it("uses the same metadata for calendar text/photos and retains explicit render callbacks", () => {
    const config = { kind: "document" as const, start: "date", allDay: true, columns };
    const item = { ...row, id: "1", date: "2026-11-20" };
    expect(calendarEvent({ ...item, description: "Description" }, config)?.title).toBe("AP-1");
    expect(calendarEvent({ ...item, description: "Description" }, { ...config, kind: "catalog" })?.title).toBe("Description");
    expect(calendarEvent(item, config)?.extendedProps).toMatchObject({
      primary: "AP-1", secondary: "Rex", avatarUrl: "/rex.png", avatarLabel: "Rex",
    });
    expect(calendarEvent(item, { ...config, title: () => "Custom title", subtitle: () => "Custom subtitle" }))
      .toMatchObject({ title: "Custom title", extendedProps: { secondary: "Custom subtitle" } });
  });
});
