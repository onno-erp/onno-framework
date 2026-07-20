import {
  registerWidget,
  useEffect,
  useMemo,
  useState,
  api,
  Segmented,
  Badge,
  type WidgetProps,
  type EntityRecord,
} from "@onno/widget-sdk";

/**
 * A custom widget — the kind the framework has no built-in for. It renders the bound document's
 * recent records as a vertical event log (timeline), reading its parameters from the server-side
 * `.config(...)` on the widget declaration. This whole file is compiled by the `su.onno.widgets`
 * Gradle plugin into `onno-plugins/EventLog.js`, served by the app and loaded by the SPA at boot.
 *
 * It also shows off two SDK features:
 *   - **Host UI primitives** imported straight from the SDK: `Segmented` (the newest/oldest sort
 *     toggle) and `Badge` (the amount pill) are the app's *real* controls, not lookalikes.
 *   - **Tailwind in the widget build**: classes in this widget's own markup — including ones the host
 *     never emits, like `border-l` and the arbitrary value `-left-[4.5px]` — now produce real CSS
 *     (the Gradle plugin runs Tailwind over `src/main/widgets`). No more inline-style workarounds.
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

type Order = "newest" | "oldest";

function EventLog({ widget }: WidgetProps) {
  const [all, setAll] = useState<EntityRecord[]>([]);
  const [error, setError] = useState<string | null>(null);
  const [order, setOrder] = useState<Order>("newest");

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
      .then((data) => !cancelled && setAll(data))
      .catch((e) => !cancelled && setError(String(e?.message ?? e)));
    return () => {
      cancelled = true;
    };
  }, [widget.entityName]);

  // Sort by the date field in the chosen direction, then take the most recent `max`.
  const rows = useMemo(() => {
    const sorted = [...all].sort((a, b) => {
      const cmp = String(a[dateField] ?? "").localeCompare(String(b[dateField] ?? ""));
      return order === "newest" ? -cmp : cmp;
    });
    return sorted.slice(0, max);
  }, [all, dateField, order, max]);

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
    return <div className="rounded-panel border border-border p-4 text-xs text-destructive">Failed to load: {error}</div>;
  }

  return (
    <div className="rounded-panel border bg-card p-6 text-card-foreground">
      <div className="mb-4 flex items-center justify-between gap-3">
        <div className="text-[13px] font-medium text-foreground">{widget.title}</div>
        {/* Host primitive — the app's real segmented control, straight from the SDK. */}
        <Segmented
          value={order}
          onChange={setOrder}
          options={[
            { value: "newest", label: "Newest" },
            { value: "oldest", label: "Oldest" },
          ]}
        />
      </div>
      {rows.length === 0 ? (
        <div className="text-xs text-muted-foreground">No activity yet.</div>
      ) : (
        // `border-l` + `border-border` come from the widget's own Tailwind pass — classes the host
        // stylesheet doesn't itself emit, so before the build ran Tailwind these produced no CSS.
        <ol className="relative ml-2 border-l border-border">
          {rows.map((r) => {
            const label = cfg.secondaryDisplay && r[cfg.secondaryDisplay] ? String(r[cfg.secondaryDisplay]) : null;
            const amount = amountField ? money(r[amountField]) : null;
            const date = fmtDay(r[dateField]);
            return (
              <li key={String(r._id ?? r[titleField])} className="ml-4">
                {/* The dot is dead-centred on the 1px rule via an arbitrary-value class (`-left-[4.5px]`)
                    and parked level with the title line (`mt-3`) — both formerly-dropped utilities. */}
                <span
                  className="absolute -left-[4.5px] mt-3 size-2 rounded-full bg-primary"
                  aria-hidden
                />
                <button
                  type="button"
                  onClick={() => open(r)}
                  className="-mx-2 flex w-[calc(100%+1rem)] items-center justify-between gap-3 rounded-field px-2 py-2 text-left transition-colors hover:bg-accent/40"
                >
                  <div className="min-w-0">
                    <div className="truncate text-[13px] font-medium leading-tight text-foreground">
                      {String(r[titleField] ?? "")}
                    </div>
                    {label ? <div className="truncate text-[11px] text-muted-foreground">{label}</div> : null}
                  </div>
                  <div className="flex shrink-0 flex-col items-end gap-0.5">
                    {/* Host primitive — the app's real Badge, tabular-nums for aligned amounts. */}
                    {amount ? (
                      <Badge variant="secondary" className="tabular-nums font-medium">
                        {amount}
                      </Badge>
                    ) : null}
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
