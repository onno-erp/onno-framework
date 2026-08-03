import { describe, expect, it } from "vitest";
import { calendarMoveValues } from "@/components/calendar-widget";

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
