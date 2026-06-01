import { useState } from "react";
import type { Meta, StoryObj } from "@storybook/react";
import {
  CalendarDate,
  parseDate,
  today,
  getLocalTimeZone,
} from "@internationalized/date";
import { Calendar, RangeCalendar } from "./calendar";

const meta: Meta = {
  title: "Primitives/Calendar",
  parameters: { layout: "centered" },
  decorators: [
    (Story) => (
      <div className="rounded-md border bg-popover w-fit shadow-sm">
        <Story />
      </div>
    ),
  ],
};

export default meta;
type Story = StoryObj;

export const SingleDate: Story = {
  render: () => {
    const [date, setDate] = useState<CalendarDate | null>(today(getLocalTimeZone()));
    return <Calendar value={date} onChange={setDate} />;
  },
};

export const DisabledWeekends: Story = {
  render: () => {
    const [date, setDate] = useState<CalendarDate | null>(null);
    return (
      <Calendar
        value={date}
        onChange={setDate}
        isDateUnavailable={(d) => {
          const weekday = d.toDate(getLocalTimeZone()).getDay();
          return weekday === 0 || weekday === 6;
        }}
      />
    );
  },
};

export const RangeOneMonth: Story = {
  render: () => {
    const [range, setRange] = useState<{ start: CalendarDate; end: CalendarDate } | null>({
      start: parseDate("2026-05-06"),
      end: parseDate("2026-05-12"),
    });
    return <RangeCalendar value={range} onChange={setRange} numberOfMonths={1} />;
  },
};

export const RangeTwoMonths: Story = {
  render: () => {
    const [range, setRange] = useState<{ start: CalendarDate; end: CalendarDate } | null>({
      start: parseDate("2026-05-06"),
      end: parseDate("2026-05-22"),
    });
    return <RangeCalendar value={range} onChange={setRange} numberOfMonths={2} />;
  },
};
