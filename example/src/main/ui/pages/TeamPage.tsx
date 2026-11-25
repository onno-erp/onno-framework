import { Button, Calendar, EntityList, formatRecordNumber, Page, Row, openRecord, useHasRole } from "@onno/ui";

/** The team's event calendar beside the staff list. */
export function TeamPage() {
  const canCreate = useHasRole("MANAGER", "ADMIN");
  return (
    <Page
      title="Team"
      subtitle="Upcoming events and staff availability"
      actions={canCreate ? <Button size="sm" onClick={() => openRecord("Schedule Events")}>New team event</Button> : null}
    >
      <Row widths={[2, 1]}>
        <Calendar
          title="Upcoming team events"
          entity="Schedule Events"
          start="startsAt"
          end="endsAt"
          allDay={false}
          itemTitle={(event) => event.subject}
          subtitle={(event) => formatRecordNumber(event.number)}
          hint="Open an event to manage participants; drag it to reschedule."
        />
        <EntityList entity="Employees" />
      </Row>
    </Page>
  );
}
