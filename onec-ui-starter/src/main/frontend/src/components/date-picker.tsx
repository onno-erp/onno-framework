import {
  CalendarDate,
  CalendarDateTime,
  parseDate,
  parseDateTime,
} from "@internationalized/date";
import { DateInput } from "@/components/ui/date-input";

interface DatePickerProps {
  value?: string;
  onChange: (val: string) => void;
  includeTime?: boolean;
}

function parseValue(value: string | undefined, includeTime: boolean):
  | CalendarDate
  | CalendarDateTime
  | null {
  if (!value) return null;
  try {
    if (includeTime) {
      // Accept either "yyyy-MM-ddTHH:mm" or "yyyy-MM-dd"
      if (value.includes("T")) {
        return parseDateTime(value.slice(0, 16));
      }
      return parseDateTime(`${value}T00:00`);
    }
    // Date-only: take the first 10 chars in case caller passed a full ISO.
    return parseDate(value.slice(0, 10));
  } catch {
    return null;
  }
}

function formatValue(
  value: CalendarDate | CalendarDateTime | null,
  includeTime: boolean
): string {
  if (!value) return "";
  if (includeTime) {
    // CalendarDateTime#toString → "2026-05-09T14:30:00"
    return value.toString().slice(0, 16);
  }
  // CalendarDate#toString → "2026-05-09"
  return value.toString();
}

export function DatePicker({ value, onChange, includeTime = false }: DatePickerProps) {
  const parsed = parseValue(value, includeTime);
  return (
    <DateInput
      value={parsed}
      onChange={(next) => onChange(formatValue(next, includeTime))}
      granularity={includeTime ? "minute" : "day"}
      hourCycle={24}
      // Anchor times to the local zone when the caller asked for time.
      shouldForceLeadingZeros
      placeholderValue={
        includeTime
          ? new CalendarDateTime(2026, 1, 1, 9, 0)
          : new CalendarDate(2026, 1, 1)
      }
    />
  );
}
