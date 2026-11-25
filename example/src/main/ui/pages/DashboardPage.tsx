import { Chart, formatRecordNumber, Page, RecentRecords, Row, Stat, TimeRange } from "@onno/ui";
import { OrdersBoard } from "../components/OrdersBoard";
import { OrderActivity } from "../components/OrderActivity";
import { allOrders, openOrders, postedOrders, revenue } from "../lib/measures";

/** The at-a-glance board for the shop. One shared time picker drives every stat and chart. */
export function DashboardPage() {
  return (
    <Page title="Dashboard" subtitle="Onno Books — orders and stock at a glance">
      <TimeRange title="Time range" presets={["24h", "7d", "30d", "90d", "1y", "all"]} default="30d" />

      <Row>
        <Stat title="Open orders" measure={openOrders} compare trend={false} hint="Orders not yet completed or cancelled." />
        <Stat title="Total orders" measure={allOrders} compare trend={false} />
        <Stat title="Revenue (posted)" measure={revenue} currency="USD" compare trend={false} hint="Sum of order totals across posted orders." />
      </Row>

      <Row>
        <Chart title="Orders by status" type="pie" measure={allOrders} by="status" hint="Where orders sit in the lifecycle." />
        <Chart title="Revenue by day" type="area" measure={revenue} by={{ date: "day" }} currency="USD" hint="Posted-order revenue over time." />
      </Row>

      <Chart
        title="Orders & revenue"
        hint="Weekly posted revenue (left axis) against order count (right axis)."
        by={{ date: "week" }}
        bucketMode="fixed"
        series={[
          { measure: revenue, type: "area", label: "Revenue", color: "primary", currency: "USD" },
          { measure: postedOrders, type: "bar", label: "Orders", color: "warning", axis: "right" },
        ]}
        axes={{ left: { min: 0, label: "Revenue" }, right: { min: 0, label: "Orders" } }}
        thresholds={[{ value: 5000, label: "Weekly target", color: "success", style: "dashed" }]}
        legend="top"
        curve="linear"
      />

      <RecentRecords
        title="Recent orders"
        entity="Orders"
        limit={10}
        primary={(o) => `${formatRecordNumber(o.number)} · ${o.customerDisplay ?? ""}`}
        secondary={(o) => o.statusDisplay}
        amount={(o) => o.total}
        currency="USD"
      />

      <OrderActivity limit={10} />

      <OrdersBoard open />
    </Page>
  );
}
