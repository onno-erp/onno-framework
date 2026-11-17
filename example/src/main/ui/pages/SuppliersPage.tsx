import { EntityList, Page, Row, Stat, count, type ListPageProps } from "@onno/ui";

/** The suppliers list under a headline count. Columns and fields come from entities/Suppliers.ts. */
export function SuppliersPage({ list }: ListPageProps<"Suppliers">) {
  return (
    <Page title="Suppliers" subtitle="Who we buy stock from">
      <Row widths={[1, 2]}>
        <Stat title="Total suppliers" measure={count("Suppliers")} trend={false} />
        <div />
      </Row>
      <EntityList list={list} />
    </Page>
  );
}
