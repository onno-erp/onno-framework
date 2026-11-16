import { ReceiptNotesEditor } from "../components/ReceiptNotesEditor";
import {
  EntityList,
  RecordPage,
  RecordFields,
  RecordLines,
  Row,
  type ListPageProps,
  type RecordPageProps,
} from "@onno/ui";

export function StockReceiptsPage({ list }: ListPageProps<"Stock Receipts">) {
  return (
    <EntityList
      list={list}
      columns={["number", "date", "supplier", "posted"]}
      sort={"-date"}
      groupable={["supplier", { date: "month" }]}
      filters={[
        { field: "date", label: "Received" },
        { field: "note", label: "Note" },
      ]}
      fields={{
        supplier: {},
        date: { format: { date: "medium" } },
        note: { input: "textarea" },
        // Once the supplier is picked, a line's book picker only offers that supplier's titles.
        "lines.book": { filterBy: { supplier: "supplier" } },
      }}
    />
  );
}

export function StockReceiptPage({ form }: RecordPageProps<"Stock Receipts">) {
  return (
    <RecordPage form={form}>
      <Row widths={[2, 1]}>
        <div className="space-y-4">
          <RecordFields
            form={form}
            fields={{
              supplier: {},
              date: { format: { date: "medium" } },
            }}
          />
          <RecordLines
            form={form}
            section="lines"
            fields={{ book: { filterBy: { supplier: "supplier" } } }}
          />
        </div>
        <ReceiptNotesEditor form={form} />
      </Row>
    </RecordPage>
  );
}
