import {
  EntityList,
  RecordPage,
  RecordFields,
  RecordLines,
  type ListPageProps,
  type RecordPageProps,
} from "@onno/ui";

export function ScheduleEventsPage({ list }: ListPageProps<"Schedule Events">) {
  return (
    <EntityList
      list={list}
      columns={["number", "subject", "startsAt", "endsAt"]}
      sort={"startsAt"}
      // The same records as a week calendar and as a day scheduler with one lane per organizer.
      // Both read the list feed (search and filters apply); a MANAGER can drag an event to
      // reschedule it or into another organizer's lane — the save runs the "valid-interval" rule.
      calendar={{ start: "startsAt", end: "endsAt", title: "subject", mode: "week" }}
      timeline={{ start: "startsAt", end: "endsAt", lane: "organizer", laneEntity: "Employees", title: "subject", slotMinutes: 30, startHour: 7, endHour: 22 }}
      fields={{
        date: { hideInForm: true },
        subject: {},
        startsAt: { width: "half", label: "Starts at" },
        endsAt: { width: "half", label: "Ends at" },
        organizer: { label: "Organizer" },
        showUnavailable: {
          input: "switch",
          label: "Show unavailable employees",
        },
        // Each candidate is badged available/unavailable for the chosen window by the server's
        // EmployeeAvailability (PickerOptions bound to this field); one employee per event.
        "participants.employee": {
          label: "Employee",
          refSecondary: "email",
          uniqueWithinSection: true,
        },
      }}
    />
  );
}

export function ScheduleEventPage({
  form,
}: RecordPageProps<"Schedule Events">) {
  return (
    <RecordPage form={form}>
      <RecordFields
        form={form}
        fields={{
          date: { hideInForm: true },
          subject: {},
          startsAt: { width: "half", label: "Starts at" },
          endsAt: { width: "half", label: "Ends at" },
          organizer: { label: "Organizer" },
          showUnavailable: {
            input: "switch",
            label: "Show unavailable employees",
          },
        }}
      />
      <RecordLines
        form={form}
        section="participants"
        fields={{
          employee: {
            label: "Employee",
            refSecondary: "email",
            uniqueWithinSection: true,
          },
        }}
      />
    </RecordPage>
  );
}
