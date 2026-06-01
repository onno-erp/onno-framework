import type { Meta, StoryObj } from "@storybook/react";
import { CalendarWidget } from "./calendar-widget";
import type { DashboardWidgetMeta } from "@/lib/types";

const meta: Meta<typeof CalendarWidget> = {
  title: "Widgets/CalendarWidget",
  component: CalendarWidget,
  parameters: { layout: "padded" },
  decorators: [
    (Story) => (
      <div className="w-[64rem] max-w-full">
        <Story />
      </div>
    ),
  ],
};

export default meta;
type Story = StoryObj<typeof CalendarWidget>;

const widget: DashboardWidgetMeta = {
  title: "Invoice calendar",
  widgetType: "calendar",
  order: 0,
  width: "1/2",
  entityType: "document",
  entityName: "Invoice",
  maxItems: 0,
  dateField: "_date",
  titleField: "_number",
  extraConfig: {},
};

export const Invoices: Story = {
  args: { widget },
};

export const ReadOnly: Story = {
  args: {
    widget: {
      ...widget,
      title: "Invoice calendar (read only)",
      extraConfig: { readOnly: "true" },
    },
  },
};

export const Bookings: Story = {
  args: {
    widget: {
      title: "Bookings",
      widgetType: "calendar",
      order: 0,
      width: "full",
      entityType: "document",
      entityName: "Booking",
      maxItems: 0,
      dateField: "_date",
      titleField: "_number",
      extraConfig: { endDateField: "_end_date", colorBy: "property_display" },
    },
  },
};

export const BookingsByDuration: Story = {
  args: {
    widget: {
      title: "Bookings (duration)",
      widgetType: "calendar",
      order: 0,
      width: "full",
      entityType: "document",
      entityName: "Booking",
      maxItems: 0,
      dateField: "_date",
      titleField: "_number",
      extraConfig: { durationField: "duration_days" },
    },
  },
};
