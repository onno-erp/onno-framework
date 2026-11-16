/**
 * A small hand-written stand-in for an app's generated `model.gen.ts`: two entities, so the public
 * types can be checked against a typed model. Only `tsconfig.types.json` sees this augmentation.
 */
import type { CatalogRecord, DocumentRecord, RefInfo, TabularRow } from "@onno/ui";

export const OrderStatus = { NEW: "uuid-new", SHIPPED: "uuid-shipped" } as const;
export type OrderStatus = (typeof OrderStatus)[keyof typeof OrderStatus];

export type Customer = CatalogRecord & { email: string | null; latitude: number | null; longitude: number | null };
export type OrderLine = TabularRow & { book: string | null; quantity: number | null };
export type Order = DocumentRecord & {
  customer: string | null;
  customerRef?: RefInfo<"Customers">;
  status: OrderStatus | null;
  total: number | null;
  note: string | null;
  shippedAt: string | null;
  items: OrderLine[];
};

declare module "@onno/ui" {
  interface OnnoEntities {
    "Customers": { kind: "catalogs"; route: "/catalogs/customers"; record: Customer; field: "code" | "description" | "email" | "latitude" | "longitude"; dateField: never };
    "Orders": { kind: "documents"; route: "/documents/orders"; record: Order; field: "number" | "date" | "posted" | "customer" | "status" | "total" | "note" | "shippedAt" | "items" | "items.book" | "items.quantity"; dateField: "date" | "shippedAt" };
  }
}
