import { Page, PeriodClose } from "@onno/ui";

/** Period-close administration: who may close and reopen periods is enforced by the server. */
export function PeriodClosePage() {
  return (
    <Page title="Period close">
      <PeriodClose />
    </Page>
  );
}
