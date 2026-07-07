import { registerWidget, useEffect, useState, api, type WidgetProps, type EntityRecord } from "@onno/widget-sdk";

/**
 * A custom widget — the kind the framework has no built-in for. It renders the bound document's
 * recent records as a vertical event log (timeline), reading its parameters from the server-side
 * `.config(...)` on the widget declaration. This whole file is compiled by the `su.onno.widgets`
 * Gradle plugin into `onno-plugins/EventLog.js`, served by the app and loaded by the SPA at boot.
 *
 * Type + spacing mirror the built-in list widget (13px medium titles, 11px muted secondary,
 * 12px tabular amounts, "MMM d" dates) so the card reads as part of the same dashboard family.
 *
 * Server side (example DashboardPage):
 *   b.widget("Recent activity").type("eventLog").document(Payment.class)
 *       .config("amountField", "amount").config("currency", "EUR")
 */

/** "Jul 4"-style short day, matching the built-in list widget's date column. */
function fmtDay(value: unknown): string {
  if (typeof value !== "string" || !value) return "";
  try {
    return new Intl.DateTimeFormat(undefined, { month: "short", day: "numeric" }).format(new Date(value));
  } catch {
    return "";
  }
}

/** Route segment for an entity display name ("StockReceipts" → "stock_receipts"). */
function toSnake(name: string): string {
  return name.replace(/([a-z])([A-Z])/g, "$1_$2").replace(/\s+/g, "_").toLowerCase();
}

function EventLog({ widget }: WidgetProps) {
  const [rows, setRows] = useState<EntityRecord[]>([]);
  const [error, setError] = useState<string | null>(null);

  const cfg = widget.extraConfig ?? {};
  const dateField = cfg.dateField || "_date";
  const titleField = cfg.titleField || "_number";
  const amountField = cfg.amountField;
  const currency = cfg.currency;
  const max = widget.maxItems && widget.maxItems > 0 ? widget.maxItems : 12;

  useEffect(() => {
    let cancelled = false;
    api
      .listDocuments(widget.entityName)
      .then((data) => {
        if (cancelled) return;
        const sorted = [...data].sort((a, b) =>
          String(b[dateField] ?? "").localeCompare(String(a[dateField] ?? ""))
        );
        setRows(sorted.slice(0, max));
      })
      .catch((e) => !cancelled && setError(String(e?.message ?? e)));
    return () => {
      cancelled = true;
    };
  }, [widget.entityName, dateField, max]);

  const money = (v: unknown) => {
    if (v == null || v === "") return null;
    const n = Number(v);
    if (Number.isNaN(n)) return String(v);
    return currency
      ? new Intl.NumberFormat(undefined, { style: "currency", currency }).format(n)
      : String(v);
  };

  // Open the record like a built-in list row: the host routes "onno://{kind}s/{name}/{id}" through
  // the same handler as a DivKit row tap (widget.entityType is singular: "document" | "catalog").
  const open = (r: EntityRecord) => {
    const id = String(r._id ?? "");
    if (!id) return;
    window.dispatchEvent(
      new CustomEvent("onno:action", {
        detail: `onno://${widget.entityType}s/${toSnake(widget.entityName)}/${id}`,
      })
    );
  };

  if (error) {
    return <div className="rounded-card border border-border p-4 text-xs text-destructive">Failed to load: {error}</div>;
  }

  return (
    <div className="rounded-card border bg-card p-6 text-card-foreground">
      <div className="mb-6 text-[13px] font-medium text-foreground">{widget.title}</div>
      {rows.length === 0 ? (
        <div className="text-xs text-muted-foreground">No activity yet.</div>
      ) : (
        // Layout-critical styles are inline: widget .tsx is compiled by esbuild outside the host
        // SPA's Tailwind build, so any utility class the host doesn't already emit (border-l,
        // -left-[5px], …) silently produces no CSS. Theme colors come from the host's HSL vars.
        <ol className="relative" style={{ marginLeft: 8, borderLeft: "1px solid hsl(var(--border))" }}>
          {rows.map((r) => {
            const label = cfg.secondaryDisplay && r[cfg.secondaryDisplay] ? String(r[cfg.secondaryDisplay]) : null;
            const amount = amountField ? money(r[amountField]) : null;
            const date = fmtDay(r[dateField]);
            return (
              <li key={String(r._id ?? r[titleField])} style={{ marginLeft: 16 }}>
                <span
                  className="rounded-full"
                  style={{
                    position: "absolute",
                    // Offsets resolve against the ol's padding box; the 1px border-left sits at
                    // [-1, 0], so an 8px dot is dead-centred on it at -4.5 (center -0.5). marginTop
                    // parks it level with the title line inside the row button's padding.
                    left: -4.5,
                    marginTop: 12,
                    height: 8,
                    width: 8,
                    background: "hsl(var(--primary))",
                  }}
                  aria-hidden
                />
                <button
                  type="button"
                  onClick={() => open(r)}
                  className="-mx-2 flex w-full items-center justify-between gap-3 rounded-card px-2 py-2 text-left transition-colors hover:bg-accent/40"
                  style={{ width: "calc(100% + 16px)" }}
                >
                  <div className="min-w-0">
                    <div className="truncate text-[13px] font-medium leading-tight text-foreground">
                      {String(r[titleField] ?? "")}
                    </div>
                    {label ? <div className="truncate text-[11px] text-muted-foreground">{label}</div> : null}
                  </div>
                  <div className="flex shrink-0 flex-col items-end">
                    {amount ? <span className="text-[12px] font-medium tabular-nums text-foreground">{amount}</span> : null}
                    {date ? <span className="text-[10px] tabular-nums text-muted-foreground">{date}</span> : null}
                  </div>
                </button>
              </li>
            );
          })}
        </ol>
      )}
    </div>
  );
}

registerWidget("eventLog", EventLog);
