import { useState } from "react";
import type { Meta, StoryObj } from "@storybook/react";
import { DatePicker } from "./date-picker";
import { DateRangePicker, type DateRangeValue } from "./date-range-picker";

const meta: Meta = {
  title: "Inputs/DatePicker",
  parameters: { layout: "centered" },
};

export default meta;
type Story = StoryObj;

export const SingleDate: Story = {
  render: () => {
    const [value, setValue] = useState<string>("");
    return (
      <div className="w-72 space-y-2">
        <DatePicker value={value} onChange={setValue} />
        <p className="text-[11px] text-muted-foreground">
          value: <code>{value || "—"}</code>
        </p>
      </div>
    );
  },
};

export const SingleDateAndTime: Story = {
  render: () => {
    const [value, setValue] = useState<string>("");
    return (
      <div className="w-80 space-y-2">
        <DatePicker value={value} onChange={setValue} includeTime />
        <p className="text-[11px] text-muted-foreground">
          value: <code>{value || "—"}</code>
        </p>
      </div>
    );
  },
};

export const Range: Story = {
  render: () => {
    const [value, setValue] = useState<DateRangeValue>({});
    return (
      <div className="w-96 space-y-2">
        <DateRangePicker value={value} onChange={setValue} />
        <p className="text-[11px] text-muted-foreground">
          value: <code>{JSON.stringify(value)}</code>
        </p>
      </div>
    );
  },
};

export const RangeWithTimes: Story = {
  render: () => {
    const [value, setValue] = useState<DateRangeValue>({});
    return (
      <div className="w-[28rem] space-y-2">
        <DateRangePicker value={value} onChange={setValue} includeTime />
        <p className="text-[11px] text-muted-foreground">
          value: <code>{JSON.stringify(value)}</code>
        </p>
      </div>
    );
  },
};
