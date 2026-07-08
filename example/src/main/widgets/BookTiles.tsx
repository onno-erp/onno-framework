import { registerListRenderer, Badge, type EntityRecord, type ListRendererProps } from "@onno/widget-sdk";

/**
 * A custom list-body renderer — the first consumer of `ListSpec.custom(...)`. The Books catalog
 * declares `list.custom("bookTiles").label("Shelf")` (see BookView), which puts a Table ⇄ Shelf
 * toggle in the list toolbar; picking Shelf renders the *same* rows — searched, filtered, sorted
 * and fed by the framework-owned grid — through this component instead of the table.
 *
 * Compiled by the `su.onno.widgets` Gradle plugin into `onno-plugins/BookTiles.js` (like
 * EventLog.tsx), served by the app and loaded by the SPA at boot. The layout is plain Tailwind —
 * including arbitrary values the host never emits (`grid-cols-[repeat(auto-fill,minmax(160px,1fr))]`,
 * `aspect-[3/4]`) — because the Gradle plugin runs Tailwind over `src/main/widgets`, so the widget's
 * own classes get real CSS. The price rides in the app's real `Badge`, imported from the SDK.
 */

/** The price formatted like the table column would show it (BookView formats price as USD). */
function price(row: EntityRecord): string | null {
  const v = row.price;
  if (v == null || v === "") return null;
  const n = Number(v);
  if (Number.isNaN(n)) return String(v);
  return new Intl.NumberFormat(undefined, { style: "currency", currency: "USD" }).format(n);
}

function coverUrl(row: EntityRecord): string | null {
  const v = row.cover_url ?? row.coverUrl;
  return typeof v === "string" && v ? v : null;
}

function BookTiles({ rows, open }: ListRendererProps) {
  return (
    <div className="grid grid-cols-[repeat(auto-fill,minmax(160px,1fr))] gap-3">
      {rows.map((row) => {
        const cover = coverUrl(row);
        const p = price(row);
        return (
          <button
            key={String(row._id)}
            type="button"
            onClick={() => open(row)}
            className="overflow-hidden rounded-card border border-border bg-card p-0 text-left transition-colors hover:bg-accent/40"
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
                {String(row._description ?? "")}
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

registerListRenderer("bookTiles", BookTiles);
