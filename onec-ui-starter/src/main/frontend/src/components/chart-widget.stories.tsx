import type { Meta, StoryObj } from "@storybook/react";
import { ChartWidget } from "./chart-widget";
import type { DashboardWidgetMeta } from "@/lib/types";

const meta: Meta<typeof ChartWidget> = {
  title: "Widgets/ChartWidget",
  component: ChartWidget,
  parameters: { layout: "padded" },
  decorators: [
    (Story) => (
      <div className="w-[36rem]">
        <Story />
      </div>
    ),
  ],
};

export default meta;
type Story = StoryObj<typeof ChartWidget>;

const base: DashboardWidgetMeta = {
  title: "Invoices per day",
  widgetType: "chart",
  order: 0,
  width: "1/2",
  entityType: "document",
  entityName: "Invoice",
  maxItems: 0,
  dateField: "_date",
  titleField: "_number",
  extraConfig: { kind: "bar", groupBy: "_date", groupByDate: "day", metric: "count" },
};

export const Bar: Story = { args: { widget: base } };

export const Line: Story = {
  args: {
    widget: {
      ...base,
      title: "Revenue trend",
      extraConfig: {
        kind: "line",
        groupBy: "_date",
        groupByDate: "day",
        metric: "sum",
        metricField: "total",
      },
    },
  },
};

export const Area: Story = {
  args: {
    widget: {
      ...base,
      title: "Cumulative volume",
      extraConfig: {
        kind: "area",
        groupBy: "_date",
        groupByDate: "day",
        metric: "sum",
        metricField: "total",
      },
    },
  },
};

export const Donut: Story = {
  args: {
    widget: {
      ...base,
      title: "Invoices by status",
      extraConfig: { kind: "donut", groupBy: "_posted", metric: "count" },
    },
  },
};

export const ByCustomer: Story = {
  args: {
    widget: {
      ...base,
      title: "Revenue by customer",
      extraConfig: {
        kind: "bar",
        groupBy: "customer_display",
        metric: "sum",
        metricField: "total",
      },
    },
  },
};
