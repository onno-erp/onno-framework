---
name: onno-documents-lines
description: >-
  Create or modify onno-framework documents and tabular sections. Use when modeling business events
  with date/number/lifecycle, document headers, line-item rows, totals, default numbers/dates,
  document statuses, tabular section field hints, order/invoice/receipt/shipment/payment workflows,
  document repositories, or deciding whether something should be a document rather than a catalog or
  register.
---

# onno Documents And Lines

Use a document for a business event that moves work forward: order, invoice, receipt, shipment,
payment, timesheet, work order, adjustment, or check-in.

## Workflow

1. Identify the event, actor, date/number, status, and lifecycle.
2. Put scalar header fields on the document.
3. Put repeated owned rows in `@TabularSection List<Row>`.
4. Extend `DocumentObject`; row classes extend `TabularSectionRow`.
5. Seed defaults with `OnFillingHandler`.
6. Compute totals in `BeforeWriteHandler`.
7. Validate with `Validated`.
8. If the event affects registers, implement `Postable` or use `onno-posting`.
9. Add `EntityView` field hints for both header fields and `section.field` line fields.

## Read The Examples

Read [references/examples.md](references/examples.md) when writing code. It includes:

- a complete sales order document with rows
- line amount calculation and document totals
- idempotent `onFilling`
- status enum usage
- document repository usage
- common modeling mistakes

For posting-specific examples, use `onno-posting`.
