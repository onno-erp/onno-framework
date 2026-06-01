import type { Meta, StoryObj } from "@storybook/react";
import { KanbanWidget } from "./kanban-widget";
import type { DashboardWidgetMeta } from "@/lib/types";

const meta: Meta<typeof KanbanWidget> = {
  title: "Widgets/KanbanWidget",
  component: KanbanWidget,
  parameters: { layout: "padded" },
  decorators: [
    (Story) => (
      <div className="max-w-3xl">
        <Story />
      </div>
    ),
  ],
};

export default meta;
type Story = StoryObj<typeof KanbanWidget>;

const baseWidget: DashboardWidgetMeta = {
  title: "Invoices by status",
  widgetType: "kanban",
  order: 0,
  width: "full",
  entityType: "document",
  entityName: "Invoice",
  maxItems: 8,
  dateField: "_date",
  titleField: "_number",
  extraConfig: { groupBy: "_posted" },
};

export const Invoices: Story = {
  args: { widget: baseWidget },
};

export const GoodsReceipts: Story = {
  args: {
    widget: {
      ...baseWidget,
      title: "Goods receipts by status",
      entityName: "GoodsReceipt",
    },
  },
};

export const Sales: Story = {
  args: {
    widget: {
      ...baseWidget,
      title: "Sales by status",
      entityName: "Sale",
    },
  },
};
