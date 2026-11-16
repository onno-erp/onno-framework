import { Receipt } from "@onno/ui/icons";
import type { EntityCustomization } from "@onno/ui";

export const stockReceipts = {
  icon: Receipt,
  list: {
    columns: ["number", "date", "supplier", "posted"],
    sort: "-date",
    groupable: ["supplier", { date: "month" }],
    filters: [
      { field: "date", label: "Received" },
      { field: "note", label: "Note" },
    ],
  },
  fields: {
    date: { format: { date: "medium" } },
    note: { input: "textarea" },
    // Once the supplier is picked, a line's book picker only offers that supplier's titles.
    "lines.book": { filterBy: { supplier: "supplier" } },
  },
} satisfies EntityCustomization<"Stock Receipts">;
