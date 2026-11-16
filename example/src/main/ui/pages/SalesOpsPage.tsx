import { useState } from "react";
import { Chart, Page, Row, Stat, TimeRange } from "@onno/ui";
import { OrdersBoard } from "../components/OrdersBoard";
import { SelectedOrders } from "../components/SelectedOrders";
import { allOrders, openOrders, revenue } from "../lib/measures";

/** The working Orders list with its KPIs in a right rail; the rail totals what the user selects. */
export function SalesOpsPage() {
  const [selection, setSelection] = useState<string[]>([]);
  return (
    <Page title="Sales ops" subtitle="The Orders list beside its numbers">
      <Row widths={[2, 1]}>
        <OrdersBoard selection={selection} onSelectionChange={setSelection} />
        <>
          <SelectedOrders ids={selection} />
          <TimeRange title="Time range" presets={["7d", "30d", "90d", "1y", "all"]} default="all" />
          <Stat title="Open orders" measure={openOrders} trend={false} hint="Orders not yet completed or cancelled." />
          <Stat title="Posted revenue" measure={revenue} currency="USD" trend={false} />
          <Chart title="Orders by status" type="pie" measure={allOrders} by="status" hint="Where orders sit in the lifecycle." />
        </>
      </Row>
    </Page>
  );
}
