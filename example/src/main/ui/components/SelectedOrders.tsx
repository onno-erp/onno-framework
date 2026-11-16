import { Card, CardContent, formatMoney, useRecords } from "@onno/ui";

/** What the user has selected in the Orders list: how many orders and their combined total. */
export function SelectedOrders({ ids }: { ids: string[] }) {
  // Exactly the selected orders, fetched (and kept live) by id.
  const { data: picked = [] } = useRecords("Orders", { where: { id: { in: ids } } });
  const total = picked.reduce((sum, o) => sum + (o.total ?? 0), 0);
  return (
    <Card>
      <CardContent className="p-5">
        <div className="text-[13px] font-medium text-foreground">Selection</div>
        {ids.length === 0 ? (
          <p className="mt-1 text-xs text-muted-foreground">Select orders in the list to total them here.</p>
        ) : (
          <p className="mt-1 text-2xl font-semibold tabular-nums">
            {ids.length} · {formatMoney(total, "USD")}
          </p>
        )}
      </CardContent>
    </Card>
  );
}
