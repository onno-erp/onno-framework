import * as React from "react";
import {
  DatePicker as AriaDatePicker,
  DateRangePicker as AriaDateRangePicker,
  DateInput as AriaDateInput,
  DateSegment,
  Dialog,
  Group,
  Popover,
  Button as AriaButton,
} from "react-aria-components";
import type {
  DateValue,
  DatePickerProps as AriaDatePickerProps,
  DateRangePickerProps as AriaDateRangePickerProps,
} from "react-aria-components";
import { CalendarIcon } from "lucide-react";
import { cn } from "@/lib/utils";
import { Calendar, RangeCalendar } from "@/components/ui/calendar";

const inputCls =
  "flex h-9 w-full items-center gap-1 rounded-md border border-input bg-muted px-3 py-1 text-sm shadow-sm transition-colors data-[focus-within=true]:border-ring data-[focus-within=true]:ring-1 data-[focus-within=true]:ring-ring data-[disabled=true]:opacity-50";
const segmentCls =
  "rounded-sm px-0.5 py-0 tabular-nums caret-transparent outline-none data-[focused=true]:bg-accent data-[focused=true]:text-accent-foreground data-[type=literal]:px-0 data-[placeholder=true]:text-muted-foreground data-[disabled=true]:opacity-50";
const calendarBtnCls =
  "ml-auto inline-flex h-7 w-7 items-center justify-center rounded-md text-muted-foreground transition-colors hover:bg-accent hover:text-accent-foreground data-[disabled=true]:opacity-50";
const popoverCls =
  "z-50 rounded-md border bg-popover p-0 text-popover-foreground shadow-md outline-none data-[entering]:animate-in data-[exiting]:animate-out data-[entering]:fade-in-0 data-[exiting]:fade-out-0";

interface DateInputProps<T extends DateValue>
  extends Omit<AriaDatePickerProps<T>, "children"> {
  className?: string;
  withCalendar?: boolean;
}

export function DateInput<T extends DateValue>({
  className,
  withCalendar = true,
  ...props
}: DateInputProps<T>) {
  return (
    <AriaDatePicker {...props} className={cn("w-full", className)}>
      <Group className={inputCls}>
        <AriaDateInput className="flex flex-1 items-center">
          {(segment) => <DateSegment segment={segment} className={segmentCls} />}
        </AriaDateInput>
        {withCalendar && (
          <AriaButton className={calendarBtnCls} aria-label="Open calendar">
            <CalendarIcon className="h-4 w-4" />
          </AriaButton>
        )}
      </Group>
      {withCalendar && (
        <Popover className={popoverCls}>
          <Dialog className="outline-none">
            <Calendar />
          </Dialog>
        </Popover>
      )}
    </AriaDatePicker>
  );
}

interface DateRangeInputProps<T extends DateValue>
  extends Omit<AriaDateRangePickerProps<T>, "children"> {
  className?: string;
  numberOfMonths?: 1 | 2;
}

export function DateRangeInput<T extends DateValue>({
  className,
  numberOfMonths = 2,
  ...props
}: DateRangeInputProps<T>) {
  return (
    <AriaDateRangePicker {...props} className={cn("w-full", className)}>
      <Group className={inputCls}>
        <AriaDateInput slot="start" className="flex items-center">
          {(segment) => <DateSegment segment={segment} className={segmentCls} />}
        </AriaDateInput>
        <span aria-hidden="true" className="mx-1 text-muted-foreground">
          –
        </span>
        <AriaDateInput slot="end" className="flex flex-1 items-center">
          {(segment) => <DateSegment segment={segment} className={segmentCls} />}
        </AriaDateInput>
        <AriaButton className={calendarBtnCls} aria-label="Open calendar">
          <CalendarIcon className="h-4 w-4" />
        </AriaButton>
      </Group>
      <Popover className={popoverCls}>
        <Dialog className="outline-none">
          <RangeCalendar numberOfMonths={numberOfMonths} />
        </Dialog>
      </Popover>
    </AriaDateRangePicker>
  );
}
