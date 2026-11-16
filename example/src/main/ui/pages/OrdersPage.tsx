import {
  ArrowRight,
  Ban,
  BadgeCheck,
  PackagePlus,
  MessageSquareWarning,
  Bell,
  Printer,
  FileDown,
  Sheet,
} from "@onno/ui/icons";
import {
  EntityList,
  RecordPage,
  RecordFields,
  RecordLines,
  type ListPageProps,
  type RecordPageProps,
} from "@onno/ui";
import { OrderStatus } from "../model.gen";

export function OrdersPage({ list }: ListPageProps<"Orders">) {
  return (
    <EntityList
      list={list}
      columns={["number", "date", "customer", "status", "total", "assignedTo"]}
      sort={"-date"}
      selectionCheckboxes={true}
      groupable={["status", "assignedTo", { date: "month" }]}
      aggregates={[{ sum: "total", label: "Total", format: { money: "USD" } }]}
      filters={[
        // The control follows each field's type: choices for an enum or reference, ranges for a date
        // or an amount, a substring for text.
        { field: "status", label: "Status" },
        { field: "assignedTo", label: "Assignee", optionsFrom: "Employees" },
        { field: "date", label: "Order date" },
        { field: "total", label: "Total" },
        { field: "note", label: "Note" },
      ]}
      rowStyle={(order) => {
        if (order.status === OrderStatus.NEW) return "warning";
        if (order.status === OrderStatus.CANCELLED) return "muted";
        return null;
      }}
      commands={{
        // Inline on every row (the server titles it per order, "→ Shipped"), plus the menus.
        advance: { placement: "row", icon: ArrowRight },
        cancel: { icon: Ban },
        requestApproval: { icon: BadgeCheck },
        bulkReceive: { icon: PackagePlus },
        feedbackDemo: { icon: MessageSquareWarning },
        toastDemo: { icon: Bell },
        // The print starter's record actions and selection downloads, keyed print-{form}[-pdf].
        "print-invoice": { icon: Printer },
        "print-invoice-pdf": { icon: FileDown },
        "print-pick-list": { icon: Sheet },
        "print-pick-list-pdf": { icon: FileDown },
      }}
      fields={{
        customer: { width: "half", hint: "Who's buying. Required." },
        // Right-click a status pill in the list for the "Change status" commands.
        status: {
          width: "half",
          commandGroup: "Change status",
          hint: "Lifecycle. Default «New»; advance it with the → button on the list.",
        },
        assignedTo: {
          width: "half",
          hint: "The bookseller handling this order.",
        },
        date: { width: "half", format: { date: "medium" } },
        total: {
          hideInForm: true,
          format: { money: "USD" },
          hint: "Auto-computed from the line amounts. Read-only.",
        },
        open: { hidden: true },
        note: { input: "textarea", hint: "Internal notes on the order." },
        "items.book": { label: "Book", openButton: false },
        "items.quantity": { label: "Qty" },
        "items.unitPrice": { label: "Unit price", format: { money: "USD" } },
        "items.amount": { label: "Amount", format: { money: "USD" } },
      }}
    />
  );
}

export function OrderPage({ form }: RecordPageProps<"Orders">) {
  return (
    <RecordPage
      form={form}
      comments
      commands={{
        // Inline on every row (the server titles it per order, "→ Shipped"), plus the menus.
        advance: { placement: "row", icon: ArrowRight },
        cancel: { icon: Ban },
        requestApproval: { icon: BadgeCheck },
        bulkReceive: { icon: PackagePlus },
        feedbackDemo: { icon: MessageSquareWarning },
        toastDemo: { icon: Bell },
        // The print starter's record actions and selection downloads, keyed print-{form}[-pdf].
        "print-invoice": { icon: Printer },
        "print-invoice-pdf": { icon: FileDown },
        "print-pick-list": { icon: Sheet },
        "print-pick-list-pdf": { icon: FileDown },
      }}
    >
      <RecordFields
        form={form}
        fields={{
          customer: { width: "half", hint: "Who's buying. Required." },
          status: {
            width: "half",
            commandGroup: "Change status",
            hint: "Lifecycle. Default «New»; advance it with the → button on the list.",
          },
          assignedTo: {
            width: "half",
            hint: "The bookseller handling this order.",
          },
          date: { width: "half", format: { date: "medium" } },
          total: {
            hideInForm: true,
            format: { money: "USD" },
            hint: "Auto-computed from the line amounts. Read-only.",
          },
          open: { hidden: true },
          note: { input: "textarea", hint: "Internal notes on the order." },
        }}
      />
      <RecordLines
        form={form}
        section="items"
        fields={{
          book: { label: "Book", openButton: false },
          quantity: { label: "Qty" },
          unitPrice: { label: "Unit price", format: { money: "USD" } },
          amount: { label: "Amount", format: { money: "USD" } },
        }}
      />
    </RecordPage>
  );
}
