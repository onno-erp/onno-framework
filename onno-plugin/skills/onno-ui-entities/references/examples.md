# Entity pages

Presentation lives in TSX components registered in `defineApp({ pages })`. These examples come
from `example/src/main/ui/pages/`; no separate `entities/*.ts` object is needed.

```tsx
import { BookOpen, Receipt } from "@onno/ui/icons";
import { BooksPage, BookPage } from "./pages/BooksPage";
import { StockReceiptsPage, StockReceiptPage } from "./pages/StockReceiptsPage";

defineApp({ pages: [
  { list: "Books", component: BooksPage, icon: BookOpen },
  { record: "Books", component: BookPage },
  { list: "Stock Receipts", component: StockReceiptsPage, icon: Receipt },
  { record: "Stock Receipts", component: StockReceiptPage },
] });
```

## Books: list presentation and a standard record layout

```tsx
import {
  EntityList,
  RecordPage,
  RecordFields,
  type ListPageProps,
  type RecordPageProps,
} from "@onno/ui";
import { BookTiles } from "../components/BookTiles";

export function BooksPage({ list }: ListPageProps<"Books">) {
  return (
    <EntityList
      list={list}
      columns={["description", "author", "category", "price", "supplier"]}
      labels={{ description: "Title" }}
      sort={"description"}
      groupable={["category", "supplier"]}
      aggregates={[
        { avg: "price", label: "Avg price", format: { money: "USD" } },
      ]}
      filters={[
        { field: "author", label: "Author" },
        { field: "isbn", label: "ISBN", type: "startsWith" },
      ]}
      custom={{ component: BookTiles, label: "Shelf", defaultView: true }}
      fields={{
        description: { label: "Title" },
        author: {},
        isbn: {},
        // Once filed, a book is re-filed rather than left uncategorized.
        category: { clearButton: false },
        supplier: { refSecondary: "email" },
        price: { format: { money: "USD" } },
        coverUrl: {
          label: "Cover",
          input: "image",
          hint: "Upload a cover image.",
        },
      }}
    />
  );
}

export function BookPage({ form }: RecordPageProps<"Books">) {
  return (
    <RecordPage form={form}>
      <RecordFields
        form={form}
        fields={{
          description: { label: "Title" },
          author: {},
          isbn: {},
          category: { clearButton: false },
          supplier: { refSecondary: "email" },
          price: { format: { money: "USD" } },
          coverUrl: {
            label: "Cover",
            input: "image",
            hint: "Upload a cover image.",
          },
        }}
      />
    </RecordPage>
  );
}
```

## Stock receipts: shared form state with an ordinary React editor

```tsx
import {
  EntityList,
  RecordPage,
  RecordFields,
  RecordLines,
  Textarea,
  Row,
  type ListPageProps,
  type RecordPageProps,
} from "@onno/ui";

export function StockReceiptsPage({ list }: ListPageProps<"Stock Receipts">) {
  return (
    <EntityList
      list={list}
      columns={["number", "date", "supplier", "posted"]}
      sort={"-date"}
      groupable={["supplier", { date: "month" }]}
      filters={[
        { field: "date", label: "Received" },
        { field: "note", label: "Note" },
      ]}
      fields={{
        supplier: {},
        date: { format: { date: "medium" } },
        note: { input: "textarea" },
        // Once the supplier is picked, a line's book picker only offers that supplier's titles.
        "lines.book": { filterBy: { supplier: "supplier" } },
      }}
    />
  );
}

export function StockReceiptPage({ form }: RecordPageProps<"Stock Receipts">) {
  return (
    <RecordPage form={form}>
      <Row widths={[2, 1]}>
        <div className="space-y-4">
          <RecordFields
            form={form}
            fields={{
              supplier: {},
              date: { format: { date: "medium" } },
            }}
          />
          <RecordLines
            form={form}
            section="lines"
            fields={{ book: { filterBy: { supplier: "supplier" } } }}
          />
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

List command presentation is a direct `commands` prop on `EntityList`; record command presentation
is a `commands` prop on `RecordPage`. For example, import `ArrowRight` and `Ban` from
`@onno/ui/icons`, then pass `commands={{ advance: { placement: "row", icon: ArrowRight },
cancel: { icon: Ban } }}`. See `pages/OrdersPage.tsx` for the complete example, including row tints
and print actions. `RecordPage` also accepts `comments` and saved-record `panels`.
