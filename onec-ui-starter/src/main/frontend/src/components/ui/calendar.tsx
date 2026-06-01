import * as React from "react";
import {
  Calendar as AriaCalendar,
  CalendarCell,
  CalendarGrid,
  CalendarGridBody,
  CalendarGridHeader,
  CalendarHeaderCell,
  Heading,
  RangeCalendar as AriaRangeCalendar,
  Button as AriaButton,
} from "react-aria-components";
import type {
  CalendarProps as AriaCalendarProps,
  RangeCalendarProps as AriaRangeCalendarProps,
  DateValue,
} from "react-aria-components";
import { ChevronLeft, ChevronRight } from "lucide-react";
import { cn } from "@/lib/utils";

const navBtnCls =
  "inline-flex h-7 w-7 items-center justify-center rounded-md text-muted-foreground transition-colors hover:bg-accent hover:text-accent-foreground data-[disabled]:opacity-50 data-[disabled]:cursor-not-allowed";

const baseCellCls = cn(
  "relative inline-flex h-8 w-8 cursor-pointer items-center justify-center text-sm rounded-md outline-none",
  "data-[outside-month]:text-muted-foreground/50",
  "data-[hovered]:bg-accent data-[hovered]:text-accent-foreground",
  "data-[focus-visible]:ring-1 data-[focus-visible]:ring-ring",
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

function CalendarTopBar({ className }: CalendarHeaderProps) {
  return (
    <header className={cn("mb-2 flex items-center justify-between gap-2", className)}>
      <AriaButton slot="previous" className={navBtnCls} aria-label="Previous">
        <ChevronLeft className="h-4 w-4" />
      </AriaButton>
      <Heading className="flex-1 text-center text-sm font-medium" />
      <AriaButton slot="next" className={navBtnCls} aria-label="Next">
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
}

export function RangeCalendar<T extends DateValue>({
  className,
  numberOfMonths = 2,
  ...props
}: RangeCalendarProps<T>) {
  return (
    <AriaRangeCalendar
      {...props}
      visibleDuration={{ months: numberOfMonths }}
      className={cn("select-none p-3", className)}
    >
      <CalendarTopBar />
      <div className="flex gap-4">
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
              {(date) => <CalendarCell date={date} className={rangeCellCls} />}
            </CalendarGridBody>
          </CalendarGrid>
        ))}
      </div>
    </AriaRangeCalendar>
  );
}

// Re-export low-level styling for date-input popover composition.
export { singleCellCls, rangeCellCls, gridCls, navBtnCls, CalendarTopBar };
