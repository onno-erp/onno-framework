import { Page, Row, Stat, TimeRange } from "@onno/ui";
import { bookSalesRevenue, unitsSold } from "../lib/measures";

/** Replaces the Book Sales register screen with its two headline totals. */
export function SalesReportPage() {
  return (
    <Page title="Sales report" subtitle="Turnover across the Book Sales register">
      <TimeRange title="Time range" presets={["30d", "90d", "1y", "all"]} default="all" />
      <Row>
        <Stat title="Total revenue" measure={bookSalesRevenue} currency="USD" trend={false} hint="Sum of the register's Revenue resource." />
        <Stat title="Units sold" measure={unitsSold} trend={false} hint="Sum of the register's Quantity resource." />
      </Row>
    </Page>
  );
}
