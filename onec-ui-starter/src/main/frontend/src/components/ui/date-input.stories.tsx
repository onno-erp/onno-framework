import { useState } from "react";
import type { Meta, StoryObj } from "@storybook/react";
import {
  CalendarDate,
  CalendarDateTime,
  parseDate,
  parseDateTime,
  today,
  getLocalTimeZone,
} from "@internationalized/date";
import { DateInput, DateRangeInput } from "./date-input";

const meta: Meta = {
  title: "Inputs/DateInput",
  parameters: { layout: "centered" },
};

export default meta;
type Story = StoryObj;

export const SingleDate: Story = {
  render: () => {
    const [value, setValue] = useState<CalendarDate | null>(today(getLocalTimeZone()));
    return (
      <div className="w-72 space-y-2">
        <DateInput value={value} onChange={setValue} />
        <p className="text-[11px] text-muted-foreground">value: <code>{value?.toString() ?? "—"}</code></p>
      </div>
    );
  },
};

export const SingleDateTime: Story = {
  render: () => {
    const [value, setValue] = useState<CalendarDateTime | null>(
      parseDateTime("2026-05-09T14:30")
    );
    return (
      <div className="w-80 space-y-2">
        <DateInput
          value={value}
          onChange={setValue}
          granularity="minute"
          hourCycle={24}
        />
        <p className="text-[11px] text-muted-foreground">value: <code>{value?.toString() ?? "—"}</code></p>
      </div>
    );
  },
};

export const Range: Story = {
  render: () => {
    const [value, setValue] = useState<{ start: CalendarDate; end: CalendarDate } | null>({
      start: parseDate("2026-05-06"),
      end: parseDate("2026-05-12"),
    });
    return (
      <div className="w-[28rem] space-y-2">
        <DateRangeInput value={value} onChange={setValue} />
        <p className="text-[11px] text-muted-foreground">
          value: <code>{value ? `${value.start} → ${value.end}` : "—"}</code>
        </p>
      </div>
    );
  },
};

export const RangeWithTimes: Story = {
  render: () => {
    const [value, setValue] = useState<{ start: CalendarDateTime; end: CalendarDateTime } | null>({
      start: parseDateTime("2026-05-06T09:00"),
      end: parseDateTime("2026-05-12T17:30"),
    });
    return (
      <div className="w-[32rem] space-y-2">
        <DateRangeInput
          value={value}
          onChange={setValue}
          granularity="minute"
          hourCycle={24}
        />
        <p className="text-[11px] text-muted-foreground">
          value: <code>{value ? `${value.start} → ${value.end}` : "—"}</code>
        </p>
      </div>
    );
  },
};

export const Empty: Story = {
  render: () => {
    const [value, setValue] = useState<CalendarDate | null>(null);
    return (
      <div className="w-72 space-y-2">
        <DateInput value={value} onChange={setValue} />
        <p className="text-[11px] text-muted-foreground">value: <code>{value?.toString() ?? "—"}</code></p>
      </div>
    );
  },
};
