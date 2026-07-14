import { useState, type ReactNode } from "react";
import { CalendarDays } from "lucide-react";
import { CalendarDate, getLocalTimeZone, parseDate, startOfMonth, startOfYear, today } from "@internationalized/date";
import { cn } from "@/lib/utils";
import { Badge } from "@/components/ui/badge";
import { Popover, PopoverContent, PopoverTrigger } from "@/components/ui/popover";
import { FacetSheet, SheetDoneButton, useFacetOverlay } from "@/components/ui/facet-sheet";
import { RangeCalendar } from "@/components/ui/calendar";
import { useAppLocale, useMessages } from "@/providers/messages-provider";
import { presetLabel, sameRange, type RangePreset, type TimeRange } from "@/lib/time-range";

/**
 * Shared faceted-filter chrome, used by both the list toolbar (search/filter facets) and the
 * dashboard time-range control so the two read as the same UI. A facet trigger is a shadcn/Linear
 * style "chip": a pill that reads as an inert prompt while empty (dashed border, muted, a leading +)
 * and as a committed selection once it carries a value (solid accented border, the picked value(s)
 * shown inline as small badges).
 */
export const facetTriggerCls = (active: boolean) =>
  cn(
    "inline-flex h-8 shrink-0 items-center gap-1.5 rounded-control border px-3 text-xs font-medium outline-none transition-colors focus-visible:ring-2 focus-visible:ring-ring focus-visible:ring-offset-1 focus-visible:ring-offset-background",
    active
      ? "border-solid border-primary/40 bg-primary/5 text-foreground hover:bg-primary/10"
      : "border-dashed border-input bg-transparent text-muted-foreground hover:border-solid hover:border-input hover:bg-accent hover:text-foreground"
  );

/** A thin vertical rule between a facet's label and its selected-value badges. */
export function FacetDivider() {
  return <span aria-hidden="true" className="mx-0.5 h-3.5 w-px shrink-0 bg-border" />;
}

/** A selected value rendered inside a facet trigger. */
export function FacetValueBadge({ children }: { children: ReactNode }) {
  return (
    <Badge variant="secondary" className="rounded-control border-transparent bg-accent px-1 font-normal text-foreground">
      {children}
    </Badge>
  );
}

/** Short, locale-aware day label ("May 9") for a stored ISO date, shown inside the date-range chip. */
export function fmtDay(iso: string, locale?: string): string {
  try {
    return new Intl.DateTimeFormat(locale || undefined, { month: "short", day: "numeric" }).format(
      new Date(`${iso}T00:00`)
    );
  } catch {
    return iso;
  }
}

/** A quick-pick entry in the {@link DateRangePanel} preset column. */
export interface PanelPreset {
  label: string;
  active?: boolean;
  onSelect: () => void;
}

/**
 * The date-range popover body — quick presets beside a two-month range calendar, the pattern
 * every analytics tool (Grafana, Stripe, Linear) uses. State is a {from,to} ISO pair; presets and
 * the calendar both write both ends at once. Shared between the list filter bar's chip and the
 * dashboard time-range control, so the two are literally the same picker behind different
 * triggers. The preset column defaults to fixed date windows (Today, Last 7 days, This month, …);
 * {@code presetItems} swaps in a caller-supplied list — the dashboard passes its configured
 * rolling presets ("Last 24 hours", "All time", …) there.
 */
export function DateRangePanel({
  ariaLabel,
  from,
  to,
  onChange,
  onClose,
  presetItems,
  touch = false,
}: {
  ariaLabel: string;
  from: string;
  to: string;
  onChange: (next: { from: string; to: string }) => void;
  onClose: () => void;
  presetItems?: PanelPreset[];
  /** Sheet layout for phones/tablets: a preset chip grid over one finger-sized month. */
  touch?: boolean;
}) {
  const t = useMessages();
  const active = !!from && !!to;
  const range = (() => {
    if (!from || !to) return null;
    try {
      return { start: parseDate(from), end: parseDate(to) };
    } catch {
      return null;
    }
  })();
  const setRange = (start: CalendarDate, end: CalendarDate) =>
    onChange({ from: start.toString(), to: end.toString() });
  const now = today(getLocalTimeZone());
  const datePresets: { label: string; start: CalendarDate; end: CalendarDate }[] = [
    { label: t("list.dateToday"), start: now, end: now },
    { label: t("list.dateYesterday"), start: now.subtract({ days: 1 }), end: now.subtract({ days: 1 }) },
    { label: t("list.dateLast7"), start: now.subtract({ days: 6 }), end: now },
    { label: t("list.dateLast30"), start: now.subtract({ days: 29 }), end: now },
    { label: t("list.dateThisMonth"), start: startOfMonth(now), end: now },
    { label: t("list.dateThisYear"), start: startOfYear(now), end: now },
  ];
  const presets: PanelPreset[] =
    presetItems ??
    datePresets.map((p) => ({
      label: p.label,
      onSelect: () => {
        setRange(p.start, p.end);
        onClose();
      },
    }));
  // Touch: a preset chip grid over a single finger-sized month — the two-month row
  // simply doesn't fit a phone (and preset lists like the dashboard's can be long).
  if (touch) {
    return (
      <>
        <div className="grid grid-cols-2 gap-2 px-4 pt-3">
          {presets.map((p) => (
            <button
              key={p.label}
              type="button"
              onClick={p.onSelect}
              className={cn(
                "h-10 truncate rounded-field border px-2 text-xs font-medium transition-colors active:bg-accent",
                p.active
                  ? "border-primary/40 bg-primary/10 text-foreground"
                  : "border-input text-foreground"
              )}
            >
              {p.label}
            </button>
          ))}
        </div>
        <div className="flex justify-center">
          <RangeCalendar
            aria-label={ariaLabel}
            value={range}
            numberOfMonths={1}
            touch
            onChange={(v) => {
              if (v) setRange(v.start as CalendarDate, v.end as CalendarDate);
            }}
          />
        </div>
      </>
    );
  }

  return (
    <div className="flex flex-col sm:flex-row">
      <div className="flex flex-row gap-1 border-b p-2 sm:flex-col sm:border-b-0 sm:border-r">
        {presets.map((p) => (
          <button
            key={p.label}
            type="button"
            onClick={p.onSelect}
            className={cn(
              "whitespace-nowrap rounded-field px-2.5 py-1.5 text-left text-xs hover:bg-accent",
              p.active ? "bg-accent font-medium text-foreground" : "text-foreground"
            )}
          >
            {p.label}
          </button>
        ))}
      </div>
      <div>
        <RangeCalendar
          aria-label={ariaLabel}
          value={range}
          onChange={(v) => {
            if (v) setRange(v.start as CalendarDate, v.end as CalendarDate);
          }}
        />
        {active ? (
          <div className="flex justify-end border-t px-3 py-2">
            <button
              type="button"
              onClick={() => {
                onChange({ from: "", to: "" });
                onClose();
              }}
              className="rounded-field px-2 py-1 text-xs text-muted-foreground hover:bg-accent hover:text-foreground"
            >
              {t("list.clear")}
            </button>
          </div>
        ) : null}
      </div>
    </div>
  );
}

/** A from/to bound the date-only calendar can display; a datetime bound (chart drag-zoom) can't. */
const DATE_ONLY = /^\d{4}-\d{2}-\d{2}$/;

/**
 * The dashboard's shared time-range control: the same facet chip as the list toolbar's date
 * filter, whose popover lists the dashboard's configured rolling presets ("Last 24 hours" …
 * "All time") beside the range calendar for an absolute window. The chip always carries the
 * active window as its value badge, so the current range reads at a glance.
 */
export function TimeRangeFacet({
  label,
  presets,
  range,
  onPreset,
  onAbsolute,
}: {
  label: string;
  presets: RangePreset[];
  range: TimeRange;
  onPreset: (id: string) => void;
  onAbsolute: (from?: string, to?: string) => void;
}) {
  const t = useMessages();
  const locale = useAppLocale();
  const [open, setOpen] = useState(false);
  const abs = range.kind === "absolute" ? range : null;
  const dateOnly = !!abs && !!abs.from && !!abs.to && DATE_ONLY.test(abs.from) && DATE_ONLY.test(abs.to);
  const activePreset = abs ? undefined : presets.find((p) => sameRange(p.range, range));
  // The chip's value: the humanized preset, the picked window, or "Custom" for a datetime window
  // (drag-zoom). Null only when a persisted selection no longer matches the configured presets.
  const value = abs
    ? dateOnly
      ? `${fmtDay(abs.from!, locale)} – ${fmtDay(abs.to!, locale)}`
      : t("timeRange.custom")
    : activePreset
      ? presetLabel(activePreset, t)
      : null;
  const items: PanelPreset[] = presets.map((p) => ({
    label: presetLabel(p, t),
    active: p === activePreset,
    onSelect: () => {
      onPreset(p.id);
      setOpen(false);
    },
  }));
  const overlay = useFacetOverlay();
  const framedOverlay = overlay !== "popover";
  const phoneSheet = overlay === "sheet";

  const trigger = (
    <button
      type="button"
      className={facetTriggerCls(value != null)}
      title={label}
      onClick={framedOverlay ? () => setOpen(true) : undefined}
    >
      <CalendarDays className="size-3.5 shrink-0 opacity-60" />
      <span className="whitespace-nowrap">{label}</span>
      {value != null ? (
        <>
          <FacetDivider />
          <FacetValueBadge>{value}</FacetValueBadge>
        </>
      ) : null}
    </button>
  );

  // Phones/tablets: use the shared framed overlay, but only phones stack presets above the
  // calendar. Tablet modals have enough width for the nicer left preset rail.
  if (framedOverlay) {
    return (
      <>
        {trigger}
        {open ? (
          <FacetSheet
            label={label}
            onClose={() => setOpen(false)}
            footer={<SheetDoneButton onClick={() => setOpen(false)}>{t("list.done")}</SheetDoneButton>}
          >
            <DateRangePanel
              ariaLabel={label}
              from={dateOnly ? abs!.from! : ""}
              to={dateOnly ? abs!.to! : ""}
              onChange={({ from, to }) => onAbsolute(from || undefined, to || undefined)}
              onClose={() => setOpen(false)}
              presetItems={items}
              touch={phoneSheet}
            />
          </FacetSheet>
        ) : null}
      </>
    );
  }

  return (
    <Popover open={open} onOpenChange={setOpen}>
      <PopoverTrigger asChild>{trigger}</PopoverTrigger>
      <PopoverContent align="end" className="w-auto p-0">
        <DateRangePanel
          ariaLabel={label}
          from={dateOnly ? abs!.from! : ""}
          to={dateOnly ? abs!.to! : ""}
          onChange={({ from, to }) => onAbsolute(from || undefined, to || undefined)}
          onClose={() => setOpen(false)}
          presetItems={items}
        />
      </PopoverContent>
    </Popover>
  );
}

/**
 * A date-range facet: one chip that opens the shared {@link DateRangePanel} popover. Used by the
 * list toolbar's filter bar.
 */
export function DateRangeFacet({
  label,
  from,
  to,
  onChange,
}: {
  label: string;
  from: string;
  to: string;
  onChange: (next: { from: string; to: string }) => void;
}) {
  const locale = useAppLocale();
  const [open, setOpen] = useState(false);
  const active = !!from && !!to;
  return (
    <Popover open={open} onOpenChange={setOpen}>
      <PopoverTrigger asChild>
        <button type="button" className={facetTriggerCls(active)} title={label}>
          <CalendarDays className="size-3.5 shrink-0 opacity-60" />
          <span className="whitespace-nowrap">{label}</span>
          {active ? (
            <>
              <FacetDivider />
              <FacetValueBadge>
                {fmtDay(from, locale)} – {fmtDay(to, locale)}
              </FacetValueBadge>
            </>
          ) : null}
        </button>
      </PopoverTrigger>
      <PopoverContent align="start" className="w-auto p-0">
        <DateRangePanel ariaLabel={label} from={from} to={to} onChange={onChange} onClose={() => setOpen(false)} />
      </PopoverContent>
    </Popover>
  );
}
