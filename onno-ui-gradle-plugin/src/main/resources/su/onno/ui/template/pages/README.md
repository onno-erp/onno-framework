Register React pages in main.tsx with defineApp({ pages: [...] }).
Use { path: "/ops", component: OpsPage } for an app page. How an entity's default list and form
present belongs in entities/<Entity>.ts (one EntityCustomization per entity, registered with
defineApp({ entities })); register { list: "Orders", component: OrdersPage } or
{ record: "Orders", component: OrderPage } only when the screen's layout differs from the default.
Import icon components from @onno/ui/icons and pass icon: ClipboardList.

```tsx
import { RecordPage, RecordFields, RecordLines, type RecordPageProps } from "@onno/ui";

export function OrderPage({ form }: RecordPageProps<"Orders">) {
  return <RecordPage form={form}>
    <RecordFields form={form} fields={["customer", "note"]} overrides={{ customer: { width: "half" } }} />
    <RecordLines form={form} section="items" />
  </RecordPage>;
}
```

RecordFields `fields` picks and orders header fields (default: the entity's form.fields);
`overrides` restyles them on this page only. RecordLines renders a tabular section. Custom React
controls read form.values and call form.set(field, value), sharing the standard save/post,
validation and dirty guard. RecordPage without children uses the full default layout, including
all sections.

LoginPage.tsx is the /login screen (`defineApp({ login })`); HomePage.tsx is the page at "/".
