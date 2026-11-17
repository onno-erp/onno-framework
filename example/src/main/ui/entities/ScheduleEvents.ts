import { Calendar } from "@onno/ui/icons";
import type { EntityCustomization } from "@onno/ui";

export const scheduleEvents = {
  icon: Calendar,
  list: {
    columns: ["number", "subject", "startsAt", "endsAt"],
    sort: "startsAt",
    // The same records as a week calendar and as a day scheduler with one lane per organizer.
    // Both read the list feed (search and filters apply); a MANAGER can drag an event to
    // reschedule it or into another organizer's lane — the save runs the "valid-interval" rule.
    calendar: { start: "startsAt", end: "endsAt", title: "subject", mode: "week" },
    timeline: {
      start: "startsAt", end: "endsAt", lane: "organizer", laneEntity: "Employees",
      title: "subject", slotMinutes: 30, startHour: 7, endHour: 22,
    },
  },
  fields: {
    startsAt: { width: "half", label: "Starts at" },
    endsAt: { width: "half", label: "Ends at" },
    organizer: { label: "Organizer" },
    showUnavailable: { input: "switch", label: "Show unavailable employees" },
    // Each candidate is badged available/unavailable for the chosen window by the server's
    // EmployeeAvailability (PickerOptions bound to this field); one employee per event.
    "participants.employee": { label: "Employee", refSecondary: "email", uniqueWithinSection: true },
  },
} satisfies EntityCustomization<"Schedule Events">;
