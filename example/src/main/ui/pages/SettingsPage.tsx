import { Page, Row, Setting } from "@onno/ui";
import { PrintTemplates } from "@onno/print";

/** Admin-only (see `roles` in main.tsx). */
export function SettingsPage() {
  return (
    <Page title="Settings">
      <Row>
        <Setting title="Store name" constant="Store Name" hint="The trading name of the shop, shown across the app." />
        <div />
      </Row>
      <PrintTemplates title="Print templates" hint="Edit the Word and Excel files behind the Print menu." />
    </Page>
  );
}
