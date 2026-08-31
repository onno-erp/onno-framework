import { afterEach, describe, expect, it } from "vitest";
import { cleanup, render, screen } from "@testing-library/react";
import { CalendarDate, parseDate } from "@internationalized/date";
import { I18nProvider } from "react-aria-components";
import { calendarRangeForTimeRange } from "@/components/date-range-facet";
import { Calendar, RangeCalendar } from "@/components/ui/calendar";

afterEach(cleanup);

describe("calendarRangeForTimeRange", () => {
  const currentDay = new CalendarDate(2026, 7, 27);

  it("projects a seven-day preset onto exactly seven calendar cells ending today", () => {
    expect(calendarRangeForTimeRange(
      { kind: "relative", amount: 7, unit: "d" },
      currentDay
    )).toEqual({ from: "2026-07-21", to: "2026-07-27" });
  });

  it("projects sub-day presets onto today", () => {
    expect(calendarRangeForTimeRange(
      { kind: "relative", amount: 6, unit: "h" },
      currentDay
    )).toEqual({ from: "2026-07-27", to: "2026-07-27" });
  });

  it("keeps date-only absolute ranges and leaves all-time unbounded", () => {
    expect(calendarRangeForTimeRange(
      { kind: "absolute", from: "2026-07-01", to: "2026-07-12" },
      currentDay
    )).toEqual({ from: "2026-07-01", to: "2026-07-12" });
    expect(calendarRangeForTimeRange({ kind: "all" }, currentDay)).toBeNull();
  });
});

describe("calendar day states", () => {
  it("uses the semantic field radius for month and year picker triggers", () => {
    render(
      <I18nProvider locale="en-US">
        <Calendar aria-label="Date" />
      </I18nProvider>
    );

    for (const trigger of [
      screen.getByRole("button", { name: /month/i }),
      screen.getByRole("button", { name: /year/i }),
    ]) {
      expect(trigger).toHaveClass("rounded-field");
      expect(trigger).not.toHaveClass("rounded-md");
    }
  });

  it("marks today with the shared dot styling", () => {
    const { container } = render(
      <I18nProvider locale="en-US">
        <Calendar aria-label="Date" />
      </I18nProvider>
    );

    const todayCell = container.querySelector("[data-today]");
    expect(todayCell).not.toBeNull();
    expect(todayCell).toHaveClass("data-[today]:after:rounded-full");
    expect(todayCell).toHaveClass("data-[today]:after:bg-primary");
  });

  it("paints every day in a projected seven-day range", () => {
    const projected = calendarRangeForTimeRange(
      { kind: "relative", amount: 7, unit: "d" },
      new CalendarDate(2026, 7, 27)
    )!;
    const { container } = render(
      <I18nProvider locale="en-US">
        <RangeCalendar
          aria-label="Date range"
          value={{ start: parseDate(projected.from), end: parseDate(projected.to) }}
          numberOfMonths={1}
        />
      </I18nProvider>
    );

    expect(container.querySelectorAll("[data-selected]")).toHaveLength(7);
  });
});
