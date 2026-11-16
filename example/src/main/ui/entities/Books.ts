import { BookOpen } from "@onno/ui/icons";
import type { EntityCustomization } from "@onno/ui";
import { BookTiles } from "../components/BookTiles";

/** Books: the default list opens on the "Shelf" tiles; the form is the default one. */
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
