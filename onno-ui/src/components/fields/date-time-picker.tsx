import { DatePicker, type DatePickerProps } from "./date-picker";

export type DateTimePickerProps = Omit<DatePickerProps, "includeTime">;

/** Local date and 24-hour time; emits YYYY-MM-DDTHH:mm, or "" when cleared. */
export function DateTimePicker(props: DateTimePickerProps) {
  return <DatePicker {...props} includeTime />;
}
