import { DefaultHome } from "@onno/ui";

/**
 * The home page. It starts as the framework's default (entity cards grouped by context);
 * replace it with your own, e.g.
 *
 *   <Page title="Overview">
 *     <Stat title="Open orders" measure={count("Orders", { open: true })} />
 *     <EntityList entity="Orders" where={{ open: true }} />
 *   </Page>
 *
 * and read data with useRecords("Orders", { where: { open: true } }) or useQuery.
 */
export function HomePage() {
  return <DefaultHome />;
}
