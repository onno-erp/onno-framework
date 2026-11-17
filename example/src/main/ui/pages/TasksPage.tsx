import { Page, TaskInbox } from "@onno/ui";

/** The human work inbox, backed by durable business-process tasks. */
export function TasksPage() {
  return (
    <Page>
      <TaskInbox title="My tasks" hint="Tasks remain here across restarts until they are completed." />
    </Page>
  );
}
