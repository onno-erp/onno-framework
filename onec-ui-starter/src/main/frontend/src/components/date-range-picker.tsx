import {
  CalendarDate,
  CalendarDateTime,
  parseDate,
  parseDateTime,
} from "@internationalized/date";
import { DateRangeInput } from "@/components/ui/date-input";

export interface DateRangeValue {
  from?: string;
  to?: string;
}

interface DateRangePickerProps {
  value?: DateRangeValue;
  onChange: (val: DateRangeValue) => void;
  includeTime?: boolean;
  className?: string;
  numberOfMonths?: 1 | 2;
}

function parseOne(
  v: string | undefined,
  includeTime: boolean
): CalendarDate | CalendarDateTime | null {
  if (!v) return null;
  try {
    if (includeTime) {
      return v.includes("T") ? parseDateTime(v.slice(0, 16)) : parseDateTime(`${v}T00:00`);
    }
    return parseDate(v.slice(0, 10));
  } catch {
    return null;
  }
}

function formatOne(
  v: CalendarDate | CalendarDateTime | null | undefined,
  includeTime: boolean
): string | undefined {
  if (!v) return undefined;
  return includeTime ? v.toString().slice(0, 16) : v.toString();
}

export function DateRangePicker({
  value,
  onChange,
  includeTime = false,
  className,
  numberOfMonths = 2,
}: DateRangePickerProps) {
  const start = parseOne(value?.from, includeTime);
  const end = parseOne(value?.to, includeTime);
  const ariaValue = start && end ? { start, end } : null;

  return (
    <DateRangeInput
      value={ariaValue as never}
      onChange={(next) => {
        if (!next) {
          onChange({});
          return;
        }
        onChange({
          from: formatOne(next.start as CalendarDate | CalendarDateTime, includeTime),
          to: formatOne(next.end as CalendarDate | CalendarDateTime, includeTime),
        });
      }}
      granularity={includeTime ? "minute" : "day"}
      hourCycle={24}
      shouldForceLeadingZeros
      numberOfMonths={numberOfMonths}
      className={className}
      placeholderValue={
        includeTime
          ? new CalendarDateTime(2026, 1, 1, 9, 0)
          : new CalendarDate(2026, 1, 1)
      }
    />
  );
}
