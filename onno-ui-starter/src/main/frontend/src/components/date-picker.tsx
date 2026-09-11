import {
  CalendarDate,
  CalendarDateTime,
  getLocalTimeZone,
  parseDate,
  parseDateTime,
  today,
} from "@internationalized/date";
import { DateInput } from "@/components/ui/date-input";

export interface DatePickerProps {
  value?: string;
  onChange: (val: string) => void;
  /** @deprecated Use DateTimePicker for date-and-time fields. */
  includeTime?: boolean;
  "aria-label"?: string;
  "aria-labelledby"?: string;
  isDisabled?: boolean;
  isReadOnly?: boolean;
  isRequired?: boolean;
  isInvalid?: boolean;
  className?: string;
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

/**
 * Where an empty picker starts: today.
 *
 * react-aria derives both the calendar's opening month and the unfilled segments from
 * `value ?? placeholderValue`, so a literal date here is the month every empty field opens on,
 * forever — the fixed 1 January 2026 this replaced meant a user picking a wedding date in November
 * paged back ten months to reach the current one. Computed per render rather than hoisted to a
 * module constant, so a tab left open overnight rolls over with the day.
 */
function placeholderFor(includeTime: boolean): CalendarDate | CalendarDateTime {
  const now = today(getLocalTimeZone());
  // Only the date rolls forward. The time segments stay at the start of the working day: seeding
  // them with the current clock reads as a deliberate 14:37 rather than as an empty field.
  return includeTime
    ? new CalendarDateTime(now.year, now.month, now.day, 9, 0)
    : now;
}

export function DatePicker({ value, onChange, includeTime = false, ...props }: DatePickerProps) {
  const parsed = parseValue(value, includeTime);
  return (
    <DateInput
      {...props}
      value={parsed}
      onChange={(next) => onChange(formatValue(next, includeTime))}
      granularity={includeTime ? "minute" : "day"}
      hourCycle={24}
      shouldForceLeadingZeros
      placeholderValue={placeholderFor(includeTime)}
    />
  );
}
