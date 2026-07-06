import { registerWidget, useEffect, useState, api, type WidgetProps, type EntityRecord } from "@onno/widget-sdk";

/**
 * A custom widget — the kind the framework has no built-in for. It renders the bound document's
 * recent records as a vertical event log (timeline), reading its parameters from the server-side
 * `.config(...)` on the widget declaration. This whole file is compiled by the `su.onno.widgets`
 * Gradle plugin into `onno-plugins/EventLog.js`, served by the app and loaded by the SPA at boot.
 *
 * Server side (example DashboardPage):
 *   b.widget("Recent activity").type("eventLog").document(Payment.class)
 *       .config("amountField", "amount").config("currency", "EUR")
 */
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

  if (error) {
    return <div className="rounded-lg border border-border p-4 text-xs text-destructive">Failed to load: {error}</div>;
  }

  return (
    <div className="rounded-lg border border-border bg-card p-4">
      <div className="mb-3 text-sm font-medium text-foreground">{widget.title}</div>
      {rows.length === 0 ? (
        <div className="text-xs text-muted-foreground">No activity yet.</div>
      ) : (
        <ol className="relative ml-2 border-l border-border">
          {rows.map((r) => {
            const label = cfg.secondaryDisplay && r[cfg.secondaryDisplay] ? String(r[cfg.secondaryDisplay]) : null;
            const amount = amountField ? money(r[amountField]) : null;
            return (
              <li key={String(r.id ?? r[titleField])} className="mb-3 ml-4">
                <span className="absolute -left-[5px] mt-1.5 h-2 w-2 rounded-full bg-primary" aria-hidden />
                <div className="flex items-baseline justify-between gap-3">
                  <span className="text-sm text-foreground">{String(r[titleField] ?? "")}</span>
                  {amount && <span className="text-sm font-medium text-foreground">{amount}</span>}
                </div>
                <div className="text-xs text-muted-foreground">
                  {String(r[dateField] ?? "")}
                  {label ? ` · ${label}` : ""}
                </div>
              </li>
            );
          })}
        </ol>
      )}
    </div>
  );
}

registerWidget("eventLog", EventLog);
