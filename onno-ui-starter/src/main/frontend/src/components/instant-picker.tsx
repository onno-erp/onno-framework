import { DateTimePicker, type DateTimePickerProps } from "@/components/date-time-picker";
import { displayTimeZone, instantToWall, wallToInstant } from "@/lib/time-zone";

export interface InstantPickerProps extends DateTimePickerProps {
  /** IANA zone to edit in; defaults to the display zone (`onno.ui.time-zone`, else the browser's). */
  timeZone?: string;
}

/**
 * Date-and-time field for `Instant` attributes. The value is an absolute ISO UTC moment
 * ("2026-01-16T01:00:00Z"); the user sees and edits it as wall time in the display zone, and every
 * edit is converted back to UTC — so the stored moment never depends on the editor's machine when
 * the deployment pins a zone.
 */
export function InstantPicker({ value, onChange, timeZone, ...props }: InstantPickerProps) {
  const zone = timeZone ?? displayTimeZone();
  return (
    <div className="flex w-full items-center gap-2" title={zone}>
      <DateTimePicker
        {...props}
        value={instantToWall(value, zone)}
        onChange={(wall) => onChange(wallToInstant(wall, zone))}
      />
      <span className="shrink-0 text-xs text-muted-foreground" data-testid="instant-zone">
        {zoneLabel(zone, value)}
      </span>
    </div>
  );
}

/** A short zone name ("GMT+3", "CET") at the value's moment, falling back to the zone id. */
function zoneLabel(zone: string, iso: string | undefined): string {
  try {
    const at = iso ? new Date(iso) : new Date();
    const parts = new Intl.DateTimeFormat("en", { timeZone: zone, timeZoneName: "short" }).formatToParts(
      Number.isNaN(at.getTime()) ? new Date() : at
    );
    return parts.find((p) => p.type === "timeZoneName")?.value ?? zone;
  } catch {
    return zone;
  }
}
