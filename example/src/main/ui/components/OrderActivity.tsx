import { useState } from "react";
import { Badge, Card, CardContent, Segmented, formatDate, formatMoney, openRecord, useRecords } from "@onno/ui";

/**
 * A timeline of the latest orders: number, customer, total and day. Plain React over typed data —
 * `useRecords("Orders", { sort, limit })` returns `Order[]` and stays live as orders change.
 */
export function OrderActivity({ limit = 10 }: { limit?: number }) {
  const [order, setOrder] = useState<"newest" | "oldest">("newest");
  const { data: latest = [], error } = useRecords("Orders", { sort: order === "newest" ? "-date" : "date", limit });

  return (
    <Card>
      <CardContent className="p-6">
        <div className="mb-4 flex items-center justify-between gap-3">
          <div className="text-[13px] font-medium text-foreground">Recent activity</div>
          <Segmented
            value={order}
            onChange={setOrder}
            options={[
              { value: "newest", label: "Newest" },
              { value: "oldest", label: "Oldest" },
            ]}
          />
        </div>
        {error ? (
          <p className="text-xs text-destructive">Failed to load orders.</p>
        ) : latest.length === 0 ? (
          <p className="text-xs text-muted-foreground">No activity yet.</p>
        ) : (
          <ol className="relative ml-2 border-l border-border">
            {latest.map((o) => (
              <li key={o.id} className="ml-4">
                <span className="absolute -left-[4.5px] mt-3 size-2 rounded-full bg-primary" aria-hidden />
                <button
                  type="button"
                  onClick={() => openRecord("Orders", o.id)}
                  className="-mx-2 flex w-[calc(100%+1rem)] items-center justify-between gap-3 rounded-field px-2 py-2 text-left transition-colors hover:bg-accent/40"
                >
                  <div className="min-w-0">
                    <div className="truncate text-[13px] font-medium leading-tight text-foreground">{o.number}</div>
                    <div className="truncate text-[11px] text-muted-foreground">{o.customerDisplay}</div>
                  </div>
                  <div className="flex shrink-0 flex-col items-end gap-0.5">
                    {o.total != null ? (
                      <Badge variant="secondary" className="tabular-nums font-medium">{formatMoney(o.total, "USD")}</Badge>
                    ) : null}
                    <span className="text-[10px] tabular-nums text-muted-foreground">{formatDate(o.date)}</span>
                  </div>
                </button>
              </li>
            ))}
          </ol>
        )}
      </CardContent>
    </Card>
  );
}
