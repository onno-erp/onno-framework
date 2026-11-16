import { TimeField, DateInput as AriaDateInput, DateSegment } from "react-aria-components";
import { parseTime, type Time } from "@internationalized/date";
import { cn } from "../../lib/utils";
import { dateInputCls, dateSegmentCls } from "../ui/date-input";

export interface TimePickerProps {
  /** "HH:mm" or "HH:mm:ss"; "" / undefined when empty. */
  value?: string;
  onChange: (val: string) => void;
  "aria-label"?: string;
  "aria-labelledby"?: string;
  isDisabled?: boolean;
  isReadOnly?: boolean;
  isRequired?: boolean;
  isInvalid?: boolean;
  className?: string;
}

function parseValue(value: string | undefined): Time | null {
  if (!value) return null;
  try {
    // Drop a fractional second ("09:30:15.250"): the field edits minute precision.
    return parseTime(value.split(".")[0]);
  } catch {
    return null;
  }
}

/** A 24-hour time-of-day field for `LocalTime` attributes; emits "HH:mm", or "" when cleared. */
export function TimePicker({ value, onChange, className, ...props }: TimePickerProps) {
  return (
    <TimeField
      {...props}
      className={cn("w-full", className)}
      value={parseValue(value)}
      onChange={(next) => onChange(next ? next.toString().slice(0, 5) : "")}
      granularity="minute"
      hourCycle={24}
      shouldForceLeadingZeros
    >
      <AriaDateInput className={dateInputCls}>
        {(segment) => <DateSegment segment={segment} className={dateSegmentCls} />}
      </AriaDateInput>
    </TimeField>
  );
}
