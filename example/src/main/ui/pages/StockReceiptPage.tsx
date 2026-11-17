import { ReceiptNotesEditor } from "../components/ReceiptNotesEditor";
import { RecordPage, RecordFields, RecordLines, Row, type RecordPageProps } from "@onno/ui";

/**
 * A stock receipt with its notes in a side column, edited by a custom control. The page only
 * chooses the layout; how each field looks comes from entities/StockReceipts.ts.
 */
export function StockReceiptPage({ form }: RecordPageProps<"Stock Receipts">) {
  return (
    <RecordPage form={form}>
      <Row widths={[2, 1]}>
        <div className="space-y-4">
          <RecordFields form={form} fields={["supplier"]} />
          <RecordLines form={form} section="lines" />
        </div>
        <ReceiptNotesEditor form={form} />
      </Row>
    </RecordPage>
  );
}
