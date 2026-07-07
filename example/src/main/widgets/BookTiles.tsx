import { registerListRenderer, type EntityRecord, type ListRendererProps } from "@onno/widget-sdk";

/**
 * A custom list-body renderer — the first consumer of `ListSpec.custom(...)`. The Books catalog
 * declares `list.custom("bookTiles").label("Shelf")` (see BookView), which puts a Table ⇄ Shelf
 * toggle in the list toolbar; picking Shelf renders the *same* rows — searched, filtered, sorted
 * and fed by the framework-owned grid — through this component instead of the table.
 *
 * Compiled by the `su.onno.widgets` Gradle plugin into `onno-plugins/BookTiles.js` (like
 * EventLog.tsx), served by the app and loaded by the SPA at boot. Layout-critical styles are
 * inline: widget .tsx compiles outside the host SPA's Tailwind build, so utility classes the host
 * doesn't already emit would silently produce no CSS.
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
    <div
      style={{
        display: "grid",
        gridTemplateColumns: "repeat(auto-fill, minmax(160px, 1fr))",
        gap: 12,
      }}
    >
      {rows.map((row) => {
        const cover = coverUrl(row);
        return (
          <button
            key={String(row._id)}
            type="button"
            onClick={() => open(row)}
            className="rounded-card border border-border bg-card text-left transition-colors hover:bg-accent/40"
            style={{ overflow: "hidden", padding: 0 }}
          >
            <div
              className="bg-muted"
              style={{ aspectRatio: "3 / 4", display: "flex", alignItems: "center", justifyContent: "center" }}
            >
              {cover ? (
                <img
                  src={cover}
                  alt=""
                  loading="lazy"
                  style={{ height: "100%", width: "100%", objectFit: "cover" }}
                />
              ) : (
                <span className="text-muted-foreground" style={{ fontSize: 12, fontWeight: 700 }} aria-hidden>
                  BOOK
                </span>
              )}
            </div>
            <div style={{ padding: "8px 10px 10px" }}>
              <div className="truncate text-[13px] font-medium text-foreground">
                {String(row._description ?? "")}
              </div>
              <div className="truncate text-[11px] text-muted-foreground">{String(row.author ?? "")}</div>
              {price(row) ? (
                <div className="text-[12px] font-medium tabular-nums text-foreground" style={{ marginTop: 4 }}>
                  {price(row)}
                </div>
              ) : null}
            </div>
          </button>
        );
      })}
    </div>
  );
}

registerListRenderer("bookTiles", BookTiles);
