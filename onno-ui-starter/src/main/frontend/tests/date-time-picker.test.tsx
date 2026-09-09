import { afterEach, describe, expect, it, vi } from "vitest";
import { cleanup, fireEvent, render, screen } from "@testing-library/react";
import { I18nProvider } from "react-aria-components";
import { DatePicker } from "@/components/date-picker";
import { DateTimePicker } from "@/components/date-time-picker";

afterEach(cleanup);

describe("separate date and time pickers", () => {
  it("keeps the date-only picker free of time segments", () => {
    const { container } = render(
      <I18nProvider locale="en-GB">
        <DatePicker aria-label="Day" value="2026-09-09" onChange={vi.fn()} />
      </I18nProvider>
    );
    expect(container.querySelector('[data-type="day"]')).not.toBeNull();
    expect(container.querySelector('[data-type="hour"]')).toBeNull();
  });

  it("edits the time without changing the date and emits minute precision", () => {
    const onChange = vi.fn();
    const { container } = render(
      <I18nProvider locale="en-GB">
        <DateTimePicker aria-label="Starts at" value="2026-09-09T14:30:45" onChange={onChange} />
      </I18nProvider>
    );
    const minute = container.querySelector('[data-type="minute"]')!;
    fireEvent.keyDown(minute, { key: "ArrowUp" });
    expect(onChange).toHaveBeenLastCalledWith("2026-09-09T14:31");
    expect(container.querySelector('[data-type="second"]')).toBeNull();
    expect(container.querySelector('[data-type="dayPeriod"]')).toBeNull();
    expect(minute.closest('[role="group"]')).toHaveClass("rounded-field");
  });

  it("changes the day while retaining the selected time", () => {
    const onChange = vi.fn();
    const { container } = render(
      <I18nProvider locale="en-GB">
        <DateTimePicker aria-label="Starts at" value="2026-09-09T14:30" onChange={onChange} />
      </I18nProvider>
    );
    fireEvent.keyDown(container.querySelector('[data-type="day"]')!, { key: "ArrowUp" });
    expect(onChange).toHaveBeenLastCalledWith("2026-09-10T14:30");
  });

  it("initializes a date-only value at midnight and honors disabled state", () => {
    const { container } = render(
      <I18nProvider locale="en-GB">
        <DateTimePicker aria-label="Starts at" value="2026-09-09" onChange={vi.fn()} isDisabled />
      </I18nProvider>
    );
    expect(container.querySelector('[data-type="hour"]')).toHaveAttribute("aria-valuenow", "0");
    expect(container.querySelector('[data-type="minute"]')).toHaveAttribute("aria-valuenow", "0");
    expect(screen.getByRole("button")).toBeDisabled();
  });

  it.each(["", "invalid"])("renders an empty editable date/time field for %j", (value) => {
    const { container } = render(
      <DateTimePicker aria-label="Starts at" value={value} onChange={vi.fn()} />
    );
    expect(container.querySelector('[data-type="hour"]')).toHaveAttribute("data-placeholder", "true");
    expect(container.querySelector('[data-type="minute"]')).toHaveAttribute("data-placeholder", "true");
  });
});
