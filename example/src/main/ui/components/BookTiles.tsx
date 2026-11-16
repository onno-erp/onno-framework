import { Badge, formatMoney, type ListRendererProps } from "@onno/ui";

/**
 * The Books "Shelf" view: entities/books.ts passes this component as the list's custom view
 * (`custom: { component: BookTiles, label: "Shelf" }`), which puts a Table ⇄ Shelf toggle in the
 * toolbar. It renders the *same* rows — searched, filtered, sorted and paged by the framework's
 * list — as cover tiles. Plain Tailwind, including arbitrary values the host never emits
 * (`grid-cols-[repeat(auto-fill,minmax(160px,1fr))]`, `aspect-[3/4]`): the build runs Tailwind over
 * `src/main/ui`.
 */

export function BookTiles({ rows, open }: ListRendererProps) {
  return (
    <div className="grid grid-cols-[repeat(auto-fill,minmax(160px,1fr))] gap-3">
      {rows.map((row) => {
        const cover = row.coverUrl ? String(row.coverUrl) : null;
        const p = formatMoney(row.price, "USD");
        return (
          <button
            key={String(row.id)}
            type="button"
            onClick={() => open(row)}
            className="overflow-hidden rounded-panel border border-border bg-card p-0 text-left transition-colors hover:bg-accent/40"
          >
            <div className="flex aspect-[3/4] items-center justify-center bg-muted">
              {cover ? (
                <img src={cover} alt="" loading="lazy" className="size-full object-cover" />
              ) : (
                <span className="text-[12px] font-bold text-muted-foreground" aria-hidden>
                  BOOK
                </span>
              )}
            </div>
            <div className="px-2.5 pb-2.5 pt-2">
              <div className="truncate text-[13px] font-medium text-foreground">
                {String(row.description ?? "")}
              </div>
              <div className="truncate text-[11px] text-muted-foreground">{String(row.author ?? "")}</div>
              {p ? (
                <Badge variant="secondary" className="mt-1.5 tabular-nums font-medium">
                  {p}
                </Badge>
              ) : null}
            </div>
          </button>
        );
      })}
    </div>
  );
}

