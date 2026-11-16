import { Page, useHasRole } from "@onno/ui";
import { DashboardPage } from "./DashboardPage";
import { OrdersBoard } from "../components/OrdersBoard";

/** Admins land on the dashboard; everyone else on the working Orders list. */
export function HomePage() {
  if (useHasRole("ADMIN")) return <DashboardPage />;
  return (
    <Page>
      <OrdersBoard fill />
    </Page>
  );
}
