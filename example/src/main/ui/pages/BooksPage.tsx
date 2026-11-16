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
