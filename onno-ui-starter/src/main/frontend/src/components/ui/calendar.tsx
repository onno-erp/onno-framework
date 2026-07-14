import * as React from "react";
import {
  Calendar as AriaCalendar,
  CalendarCell,
  CalendarGrid,
  CalendarGridBody,
  CalendarGridHeader,
  CalendarHeaderCell,
  CalendarStateContext,
  RangeCalendarStateContext,
  RangeCalendar as AriaRangeCalendar,
  Button as AriaButton,
} from "react-aria-components";
import type {
  CalendarProps as AriaCalendarProps,
  RangeCalendarProps as AriaRangeCalendarProps,
  DateValue,
} from "react-aria-components";
import { Check, ChevronDown, ChevronLeft, ChevronRight } from "lucide-react";
import { cn } from "@/lib/utils";
import { useAppLocale, useMessages } from "@/providers/messages-provider";

const navBtnCls =
  "inline-flex h-7 w-7 items-center justify-center rounded-md text-muted-foreground transition-colors hover:bg-accent hover:text-accent-foreground data-[disabled]:opacity-50 data-[disabled]:cursor-not-allowed";

function monthNames(locale?: string): string[] {
  return Array.from({ length: 12 }, (_, i) =>
    new Intl.DateTimeFormat(locale || undefined, { month: "long" }).format(new Date(2026, i, 1))
  );
}

const baseCellCls = cn(
  "relative inline-flex h-9 w-9 cursor-pointer items-center justify-center text-sm rounded-lg outline-none transition-colors",
  "data-[outside-month]:text-muted-foreground/50",
  "data-[hovered]:bg-accent data-[hovered]:text-accent-foreground",
  "data-[focus-visible]:ring-2 data-[focus-visible]:ring-ring/50",
  "data-[unavailable]:line-through data-[unavailable]:opacity-60",
  "data-[disabled]:opacity-40 data-[disabled]:cursor-not-allowed"
);

const singleCellCls = cn(
  baseCellCls,
  "data-[selected]:bg-primary data-[selected]:text-primary-foreground"
);

const rangeCellCls = cn(
  baseCellCls,
  // Range middle: light tint, joined edges
  "[&[data-selected]:not([data-selection-start]):not([data-selection-end])]:bg-primary/15",
  "[&[data-selected]:not([data-selection-start]):not([data-selection-end])]:text-foreground",
  "[&[data-selected]:not([data-selection-start]):not([data-selection-end])]:rounded-none",
  // Edges
  "data-[selection-start]:bg-primary data-[selection-start]:text-primary-foreground data-[selection-start]:rounded-r-none",
  "data-[selection-end]:bg-primary data-[selection-end]:text-primary-foreground data-[selection-end]:rounded-l-none",
  // Same-day range
  "[&[data-selection-start][data-selection-end]]:rounded-md"
);

interface CalendarHeaderProps {
  className?: string;
}

/**
 * A styled month/year dropdown for the calendar header — replaces the native {@code <select>},
 * whose OS-drawn popup clashed with the themed popover around it. Deliberately NOT a portalled
 * (Radix) menu: the calendar usually lives inside a Popover, and a portalled child would count as
 * an "outside interaction" and dismiss it. A plain absolutely-positioned listbox stays inside the
 * popover's DOM, so clicks never bubble out; outside clicks are caught with a pointerdown listener.
 */
function HeaderDropdown({
  ariaLabel,
  value,
  options,
  onPick,
}: {
  ariaLabel: string;
  value: string | number;
  options: { value: number; label: string }[];
  onPick: (v: number) => void;
}) {
  const [open, setOpen] = React.useState(false);
  const rootRef = React.useRef<HTMLDivElement>(null);
  const listRef = React.useRef<HTMLDivElement>(null);

  React.useEffect(() => {
    if (!open) return;
    const onDown = (e: PointerEvent) => {
      if (!rootRef.current?.contains(e.target as Node)) setOpen(false);
    };
    const onKey = (e: KeyboardEvent) => {
      if (e.key === "Escape") {
        e.stopPropagation(); // close only this dropdown, not the popover around the calendar
        setOpen(false);
      }
    };
    document.addEventListener("pointerdown", onDown);
    document.addEventListener("keydown", onKey, true);
    return () => {
      document.removeEventListener("pointerdown", onDown);
      document.removeEventListener("keydown", onKey, true);
    };
  }, [open]);

  // Open with the current value in view, not the top of a 130-year list.
  React.useEffect(() => {
    if (open) {
      listRef.current
        ?.querySelector('[data-on="true"]')
        ?.scrollIntoView({ block: "center" });
    }
  }, [open]);

  const current = options.find((o) => o.value === Number(value));
  return (
    <div ref={rootRef} className="relative">
      <button
        type="button"
        aria-label={ariaLabel}
        aria-expanded={open}
        onClick={() => setOpen((v) => !v)}
        className="inline-flex h-7 cursor-pointer items-center gap-1 rounded-md px-2 text-sm font-medium text-foreground outline-none transition-colors hover:bg-accent focus-visible:ring-1 focus-visible:ring-ring"
      >
        {current?.label ?? value}
        <ChevronDown className={cn("h-3.5 w-3.5 text-muted-foreground transition-transform", open && "rotate-180")} />
      </button>
      {open ? (
        <div
          ref={listRef}
          role="listbox"
          aria-label={ariaLabel}
          className="absolute left-1/2 top-full z-10 mt-1 max-h-64 w-max min-w-32 -translate-x-1/2 overflow-y-auto rounded-card border bg-popover p-1 shadow-md"
        >
          {options.map((o) => {
            const on = o.value === Number(value);
            return (
              <button
                key={o.value}
                type="button"
                role="option"
                aria-selected={on}
                data-on={on}
                onClick={() => {
                  onPick(o.value);
                  setOpen(false);
                }}
                className={cn(
                  "flex w-full items-center justify-between gap-2 rounded-field px-2.5 py-1.5 text-left text-sm transition-colors hover:bg-accent",
                  on ? "font-medium text-foreground" : "text-foreground/80"
                )}
              >
                {o.label}
                {on ? <Check className="h-3.5 w-3.5 text-primary" /> : null}
              </button>
            );
          })}
        </div>
      ) : null}
    </div>
  );
}

// A calendar header with month + year dropdowns (plus prev/next arrows) so you can
// jump straight to any month/year instead of clicking through one month at a time.
// Reads the live calendar state via context (works for both single and range calendars)
// and moves the focused date on change.
function CalendarTopBar({ className }: CalendarHeaderProps) {
  const locale = useAppLocale();
  const t = useMessages();
  const single = React.useContext(CalendarStateContext);
  const range = React.useContext(RangeCalendarStateContext);
  const state = single ?? range;
  const focused = state?.focusedDate;

  const years = React.useMemo(() => {
    const current = new Date().getFullYear();
    const min = Math.min(focused?.year ?? current, current - 120);
    const max = Math.max(focused?.year ?? current, current + 15);
    const out: { value: number; label: string }[] = [];
    for (let y = max; y >= min; y--) out.push({ value: y, label: String(y) });
    return out;
  }, [focused?.year]);

  const go = (fields: { month?: number; year?: number }) => {
    if (state && focused) state.setFocusedDate(focused.set({ day: 1, ...fields }));
  };
  const months = React.useMemo(() => monthNames(locale), [locale]);

  return (
    <header className={cn("mb-2 flex items-center gap-1.5", className)}>
      <AriaButton slot="previous" className={navBtnCls} aria-label={t("calendar.previous")}>
        <ChevronLeft className="h-4 w-4" />
      </AriaButton>
      <div className="flex flex-1 items-center justify-center gap-0.5">
        <HeaderDropdown
          ariaLabel={t("calendar.monthPicker")}
          value={focused?.month ?? 1}
          options={months.map((m, i) => ({ value: i + 1, label: m }))}
          onPick={(month) => go({ month })}
        />
        <HeaderDropdown
          ariaLabel={t("calendar.yearPicker")}
          value={focused?.year ?? new Date().getFullYear()}
          options={years}
          onPick={(year) => go({ year })}
        />
      </div>
      <AriaButton slot="next" className={navBtnCls} aria-label={t("calendar.next")}>
        <ChevronRight className="h-4 w-4" />
      </AriaButton>
    </header>
  );
}

const gridCls = "border-collapse [&_td]:p-0 [&_th]:p-0";

export interface CalendarProps<T extends DateValue>
  extends Omit<AriaCalendarProps<T>, "children"> {
  className?: string;
}

export function Calendar<T extends DateValue>({
  className,
  ...props
}: CalendarProps<T>) {
  return (
    <AriaCalendar
      {...props}
      className={cn("select-none p-3", className)}
    >
      <CalendarTopBar />
      <CalendarGrid className={gridCls}>
        <CalendarGridHeader>
          {(day) => (
            <CalendarHeaderCell className="text-[10px] font-medium uppercase tracking-wide text-muted-foreground">
              {day}
            </CalendarHeaderCell>
          )}
        </CalendarGridHeader>
        <CalendarGridBody>
          {(date) => <CalendarCell date={date} className={singleCellCls} />}
        </CalendarGridBody>
      </CalendarGrid>
    </AriaCalendar>
  );
}

export interface RangeCalendarProps<T extends DateValue>
  extends Omit<AriaRangeCalendarProps<T>, "children"> {
  className?: string;
  numberOfMonths?: 1 | 2;
  /** Finger-sized cells (44px targets) for touch layouts — the mobile/tablet date-range sheet. */
  touch?: boolean;
}

export function RangeCalendar<T extends DateValue>({
  className,
  numberOfMonths = 2,
  touch = false,
  ...props
}: RangeCalendarProps<T>) {
  // twMerge (via cn) lets the touch sizes win over the base h-9 w-9 text-sm.
  const cellCls = touch ? cn(rangeCellCls, "h-11 w-11 text-[15px]") : rangeCellCls;
  return (
    <AriaRangeCalendar
      {...props}
      visibleDuration={{ months: numberOfMonths }}
      className={cn("select-none p-3", className)}
    >
      <CalendarTopBar />
      <div className={cn("flex gap-4", touch && "justify-center")}>
        {Array.from({ length: numberOfMonths }, (_, i) => (
          <CalendarGrid key={i} offset={{ months: i }} className={gridCls}>
            <CalendarGridHeader>
              {(day) => (
                <CalendarHeaderCell className="text-[10px] font-medium uppercase tracking-wide text-muted-foreground">
                  {day}
                </CalendarHeaderCell>
              )}
            </CalendarGridHeader>
            <CalendarGridBody>
              {(date) => <CalendarCell date={date} className={cellCls} />}
            </CalendarGridBody>
          </CalendarGrid>
        ))}
      </div>
    </AriaRangeCalendar>
  );
}

// Re-export low-level styling for date-input popover composition.
export { singleCellCls, rangeCellCls, gridCls, navBtnCls, CalendarTopBar };
