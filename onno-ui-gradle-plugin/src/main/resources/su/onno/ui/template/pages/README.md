Register React pages in main.tsx with defineApp({ pages: [...] }).
Use { list: "Orders", component: OrdersPage } and { record: "Orders", component: OrderPage }
for model-resolved entity screens, or { path: "/ops", component: OpsPage } for an app page.
Import icon components from @onno/ui/icons and pass icon: ClipboardList.

```tsx
import { EntityList, RecordPage, RecordFields, type ListPageProps, type RecordPageProps } from "@onno/ui";

export function OrdersPage({ list }: ListPageProps<"Orders">) {
  return <EntityList list={list} columns={["number", "date", "customer", "total"]} sort="-date" />;
}

export function OrderPage({ form }: RecordPageProps<"Orders">) {
  return <RecordPage form={form}>
    <RecordFields form={form} fields={{ customer: { width: "half" }, note: { input: "textarea" } }} />
  </RecordPage>;
}
```

RecordLines renders a tabular section. Custom React controls read form.values and call
form.set(field, value), sharing the standard save/post, validation and dirty guard.
RecordPage without children uses the full default layout, including all sections.

LoginPage.tsx is the /login screen (`defineApp({ login })`); HomePage.tsx is the page at "/".
