import { afterEach, describe, expect, it, vi } from "vitest";
import { cleanup, fireEvent, render, screen } from "@testing-library/react";
import { I18nProvider } from "react-aria-components";
import { TimePicker } from "@/components/fields/time-picker";
import { InstantPicker } from "@/components/fields/instant-picker";
import { formatTimestampDefault, formatValue } from "@/lib/cell-format";
import {
  instantToWall,
  isInstantString,
  publishDisplayTimeZone,
  wallToInstant,
} from "@/lib/time-zone";
import { setMessagesOutsideReact } from "@/lib/messages-outside-react";
import { makeTranslate, DEFAULT_MESSAGES } from "@/lib/messages";

const publishLocale = (locale: string | undefined) => setMessagesOutsideReact(makeTranslate(DEFAULT_MESSAGES), locale);

afterEach(() => {
  cleanup();
  publishDisplayTimeZone(undefined);
  publishLocale(undefined);
});

describe("display time zone conversion", () => {
  it("renders an absolute moment as wall time in a zone and back", () => {
    expect(instantToWall("2026-01-16T01:00:00Z", "Europe/Moscow")).toBe("2026-01-16T04:00:00");
    expect(wallToInstant("2026-01-16T04:00", "Europe/Moscow")).toBe("2026-01-16T01:00:00.000Z");
    // A zone with DST: the same wall hour maps to different offsets across the year.
    expect(wallToInstant("2026-01-16T09:00", "Europe/Berlin")).toBe("2026-01-16T08:00:00.000Z");
    expect(wallToInstant("2026-07-16T09:00", "Europe/Berlin")).toBe("2026-07-16T07:00:00.000Z");
  });

  it("returns empty for empty or malformed input", () => {
    expect(instantToWall("", "UTC")).toBe("");
    expect(instantToWall("nonsense", "UTC")).toBe("");
    expect(wallToInstant("", "UTC")).toBe("");
  });

  it("tells an instant from a wall-clock date-time", () => {
    expect(isInstantString("2026-01-16T01:00:00Z")).toBe(true);
    expect(isInstantString("2026-01-16T01:00:00+03:00")).toBe(true);
    expect(isInstantString("2026-01-16T01:00:00")).toBe(false);
  });

  it("ignores a zone id the browser does not know", () => {
    publishDisplayTimeZone("Mars/Olympus");
    expect(instantToWall("2026-01-16T01:00:00Z", "UTC")).toBe("2026-01-16T01:00:00");
    expect(formatTimestampDefault("2026-01-16T01:00:00")).not.toBeNull();
  });
});

describe("list cells", () => {
  it("shows an Instant in the configured zone and never shifts a wall-clock value", () => {
    publishLocale("en-GB");
    publishDisplayTimeZone("Asia/Tokyo");
    expect(formatTimestampDefault("2026-01-16T01:00:00Z")).toBe("16 Jan 2026, 10:00");
    expect(formatTimestampDefault("2026-01-16T01:00:00")).toBe("16 Jan 2026, 01:00");
    const dateTime = { date: { day: "2-digit", month: "2-digit", year: "numeric", hour: "2-digit", minute: "2-digit", hourCycle: "h23" } } as const;
    expect(formatValue("2026-01-16T01:00:00Z", dateTime)).toBe("16/01/2026, 10:00");
    expect(formatValue("2026-01-16T01:00:00", dateTime)).toBe("16/01/2026, 01:00");
  });
});

describe("TimePicker", () => {
  it("edits a LocalTime at minute precision in 24-hour form", () => {
    const onChange = vi.fn();
    const { container } = render(
      <I18nProvider locale="en-US">
        <TimePicker aria-label="Shift start" value="09:30:15" onChange={onChange} />
      </I18nProvider>
    );
    expect(container.querySelector('[data-type="dayPeriod"]')).toBeNull();
    expect(container.querySelector('[data-type="second"]')).toBeNull();
    expect(container.querySelector('[data-type="hour"]')).toHaveAttribute("aria-valuenow", "9");
    fireEvent.keyDown(container.querySelector('[data-type="minute"]')!, { key: "ArrowUp" });
    expect(onChange).toHaveBeenLastCalledWith("09:31");
  });

  it.each(["", "25:99", "soon"])("renders an empty field for %j", (value) => {
    const { container } = render(<TimePicker aria-label="Shift start" value={value} onChange={vi.fn()} />);
    expect(container.querySelector('[data-type="hour"]')).toHaveAttribute("data-placeholder", "true");
  });
});

describe("InstantPicker", () => {
  it("shows the wall time of the display zone and emits the absolute UTC moment", () => {
    publishDisplayTimeZone("Europe/Moscow");
    const onChange = vi.fn();
    const { container } = render(
      <I18nProvider locale="en-GB">
        <InstantPicker aria-label="Checked in" value="2026-01-16T01:00:00Z" onChange={onChange} />
      </I18nProvider>
    );
    expect(container.querySelector('[data-type="hour"]')).toHaveAttribute("aria-valuenow", "4");
    expect(screen.getByTestId("instant-zone")).toHaveTextContent("GMT+3");
    fireEvent.keyDown(container.querySelector('[data-type="hour"]')!, { key: "ArrowUp" });
    expect(onChange).toHaveBeenLastCalledWith("2026-01-16T02:00:00.000Z");
  });
});
