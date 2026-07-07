import { useEffect, useMemo, useState } from "react";
import { format } from "date-fns";
import { useNavigate } from "react-router-dom";
import { toSnakeCase } from "@/lib/utils";
import { useWidgetLiveVersion } from "@/lib/widget-data";
import {
  formatAmount,
  pickField,
  resolveCurrency,
  resolveText,
  splitFields,
  toNumber,
} from "@/lib/format";
import type { DashboardWidgetMeta, EntityRecord } from "@/lib/types";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { HintIcon } from "@/components/ui/hint-icon";

interface ListWidgetProps {
  widget: DashboardWidgetMeta;
}

// Identity fields tried for the headline when no titleField/titleTemplate is authored.
const TITLE_FALLBACKS = ["_number", "_code", "_description", "name"];
// Default secondary line: the first *_display reference label we find (client, property…).
const DEFAULT_SECONDARY = ["client_display", "primary_client_display", "property_display", "customer_display"];
// Default trailing amount: the usual document money fields.
const DEFAULT_AMOUNT = ["total", "total_gross", "amount", "_sum"];

export function ListWidget({ widget }: ListWidgetProps) {
  const navigate = useNavigate();
  const cfg = widget.extraConfig ?? {};
  const [items, setItems] = useState<EntityRecord[]>([]);
  const liveVersion = useWidgetLiveVersion(widget);

  // Authored field config (FR-2/6/7) — falls back to the built-in conventions.
  const titleTemplate = cfg.titleTemplate;
  const titleFields = splitFields(widget.titleField);
  const secondaryFields = splitFields(cfg.secondaryField);
  const amountFields = cfg.amountField ? [cfg.amountField] : DEFAULT_AMOUNT;
  const dateField = widget.dateField || cfg.dateField || "_date";
  const locale = cfg.locale;

  const headline = (row: EntityRecord): string =>
    resolveText(row, { template: titleTemplate, fields: titleFields, fallbacks: TITLE_FALLBACKS });

  const subtitle = (row: EntityRecord): string => {
    if (secondaryFields.length) return pickField(row, secondaryFields) ?? "";
    const preferred = pickField(row, DEFAULT_SECONDARY);
    if (preferred) return preferred;
    for (const key of Object.keys(row)) {
      if (key.endsWith("_display") && typeof row[key] === "string" && (row[key] as string).trim()) {
        return row[key] as string;
      }
    }
    return "";
  };

  const amount = (row: EntityRecord): string => {
    for (const key of amountFields) {
      const n = toNumber(row[key]);
      if (n != null) {
        const currency = resolveCurrency(row, cfg.currencyField, cfg.currency);
        return formatAmount(n, { currency, unit: cfg.unit, unitPosition: cfg.unitPosition, format: cfg.format, locale });
      }
    }
    return "";
  };

  const when = (row: EntityRecord): string => {
    const raw = row[dateField];
    if (typeof raw === "string" && raw) {
      try {
        return format(new Date(raw), "MMM d");
      } catch {
        // fall through
      }
    }
    return "";
  };

  useEffect(() => {
    const name = toSnakeCase(widget.entityName);
    const kind = `${widget.entityType}s`;
    if (kind !== "documents" && kind !== "catalogs") {
      setItems([]);
      return;
    }
    const params = new URLSearchParams();
    params.set("limit", String(Math.max(widget.maxItems || 8, 8)));
    if (widget.entityType === "document") {
      params.set("sort", dateField);
      params.set("dir", "desc");
    }
    if (cfg.filter) params.set("filter", cfg.filter);
    fetch(`/api/list/${kind}/${name}?${params.toString()}`, { credentials: "include" })
      .then((r) => (r.ok ? r.json() : Promise.reject(new Error(`HTTP ${r.status}`))))
      .then((data: { rows?: EntityRecord[] }) => setItems(data.rows ?? []))
      .catch(() => setItems([]));
  }, [widget, cfg.filter, dateField, liveVersion]);

  const rows = useMemo(() => {
    // Most-recent first when the entity is dated; otherwise as served.
    const sorted = [...items].sort((a, b) => {
      const da = typeof a[dateField] === "string" ? (a[dateField] as string) : "";
      const db = typeof b[dateField] === "string" ? (b[dateField] as string) : "";
      return db.localeCompare(da);
    });
    return sorted.slice(0, widget.maxItems || 8);
  }, [items, widget.maxItems, dateField]);

  const open = (row: EntityRecord) => {
    const id = String(row._id ?? "");
    if (!id) return;
    const name = toSnakeCase(widget.entityName);
    navigate(`/${widget.entityType}s/${name}/${id}`);
  };

  return (
    <Card>
      <CardHeader>
        <div className="flex items-center gap-1.5">
          <CardTitle className="text-[13px] font-medium">{widget.title}</CardTitle>
          <HintIcon text={widget.hint} size={13} />
        </div>
      </CardHeader>
      <CardContent>
        {rows.length === 0 ? (
          <p className="py-6 text-center text-xs text-muted-foreground">No records yet.</p>
        ) : (
          <ul className="divide-y divide-border">
            {rows.map((row) => {
              const head = headline(row);
              const sub = subtitle(row);
              const money = amount(row);
              const date = when(row);
              return (
                <li key={String(row._id)}>
                  <button
                    type="button"
                    onClick={() => open(row)}
                    className="flex w-full items-center justify-between gap-3 rounded-card py-2 text-left transition-colors hover:bg-accent/40 -mx-2 px-2"
                  >
                    <div className="min-w-0">
                      <div className="truncate text-[13px] font-medium leading-tight">
                        {head || sub || "—"}
                      </div>
                      {head && sub && (
                        <div className="truncate text-[11px] text-muted-foreground">{sub}</div>
                      )}
                    </div>
                    <div className="flex shrink-0 flex-col items-end">
                      {money && <span className="text-[12px] font-medium tabular-nums">{money}</span>}
                      {date && <span className="text-[10px] text-muted-foreground tabular-nums">{date}</span>}
                    </div>
                  </button>
                </li>
              );
            })}
          </ul>
        )}
      </CardContent>
    </Card>
  );
}
