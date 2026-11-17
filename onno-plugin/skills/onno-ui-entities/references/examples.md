# Entity customizations

How an entity presents is one `EntityCustomization<E>` per entity in `src/main/ui/entities/`,
registered with `defineApp({ entities })`. These examples come from `example/src/main/ui/`.

```tsx
// main.tsx
import { entities } from "./entities"; // { Books: books, "Stock Receipts": stockReceipts, … }
import { StockReceiptPage } from "./pages/StockReceiptPage";

defineApp({
  entities,
  pages: [
    // Only screens whose layout differs from the default are registered.
    { record: "Stock Receipts", component: StockReceiptPage },
  ],
});
```

## Books: list presentation, default record form

```ts
// entities/Books.ts
import { BookOpen } from "@onno/ui/icons";
import type { EntityCustomization } from "@onno/ui";
import { BookTiles } from "../components/BookTiles";

export const books = {
  icon: BookOpen,
  list: {
    columns: ["description", "author", "category", "price", "supplier"],
    sort: "description",
    groupable: ["category", "supplier"],
    aggregates: [{ avg: "price", label: "Avg price", format: { money: "USD" } }],
    filters: [
      { field: "author", label: "Author" },
      { field: "isbn", label: "ISBN", type: "startsWith" },
    ],
    custom: { component: BookTiles, label: "Shelf", defaultView: true },
  },
  fields: {
    description: { label: "Title" },
    // Once filed, a book is re-filed rather than left uncategorized.
    category: { clearButton: false },
    supplier: { refSecondary: "email" },
    price: { format: { money: "USD" } },
    coverUrl: { label: "Cover", input: "image", hint: "Upload a cover image." },
  },
} satisfies EntityCustomization<"Books">;
```

`fields` is presentation only: `author` and `isbn` need no entry to show. Which fields show, and in
what order, is `list.columns` (and `form: { fields: [...] }` for the record form).

## Stock receipts: a custom record layout with an ordinary React editor

```ts
// entities/StockReceipts.ts
import { Receipt } from "@onno/ui/icons";
import type { EntityCustomization } from "@onno/ui";

export const stockReceipts = {
  icon: Receipt,
  list: {
    columns: ["number", "date", "supplier", "posted"],
    sort: "-date",
    groupable: ["supplier", { date: "month" }],
    filters: [
      { field: "date", label: "Received" },
      { field: "note", label: "Note" },
    ],
  },
  fields: {
    date: { format: { date: "medium" } },
    note: { input: "textarea" },
    // Once the supplier is picked, a line's book picker only offers that supplier's titles.
    "lines.book": { filterBy: { supplier: "supplier" } },
  },
} satisfies EntityCustomization<"Stock Receipts">;
```

```tsx
// pages/StockReceiptPage.tsx — the page only chooses the layout
import { RecordPage, RecordFields, RecordLines, Textarea, Row, type RecordPageProps } from "@onno/ui";

export function StockReceiptPage({ form }: RecordPageProps<"Stock Receipts">) {
  return (
    <RecordPage form={form}>
      <Row widths={[2, 1]}>
        <div className="space-y-4">
          <RecordFields form={form} fields={["supplier"]} />
          <RecordLines form={form} section="lines" />
        </div>
        <label className="space-y-2 text-sm font-medium">
          Receiving notes
          <Textarea
            value={form.values.note ?? ""}
            onChange={(event) => form.set("note", event.target.value)}
            aria-invalid={!!form.errors.note}
            placeholder="Condition, discrepancies, or follow-up…"
          />
          {form.errors.note ? (
            <span role="alert" className="text-destructive">
              {form.errors.note}
            </span>
          ) : null}
        </label>
      </Row>
    </RecordPage>
  );
}
```

`RecordFields fields` picks and orders the header fields on this page (default: the entity's
`form.fields`). To restyle a field on one page only, pass `overrides` to `RecordFields`,
`RecordLines` or `EntityList`; everything else comes from the entity's customization.

Command presentation is the entity's `commands` customization and applies to the list and the
record alike: import `ArrowRight` and `Ban` from `@onno/ui/icons`, then
`commands: { advance: { placement: "row", icon: ArrowRight }, cancel: { icon: Ban } }`. See
`entities/Orders.ts` for the complete example, including row tints, print actions and
`comments: true`; `entities/Customers.ts` adds saved-record `recordPanels`.
