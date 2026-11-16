import { Textarea, useRecordField, type RecordPageProps } from "@onno/ui";

/** A replacement editor: the SDK supplies its value, validation and write policy. */
export function ReceiptNotesEditor({
  form,
}: RecordPageProps<"Stock Receipts">) {
  const note = useRecordField(form, "note", { label: "Receiving notes" });
  return (
    <label className="space-y-2 text-sm font-medium">
      {note.label}
      <Textarea
        value={note.value ?? ""}
        onChange={(event) => note.onChange(event.target.value)}
        disabled={note.disabled}
        aria-invalid={!!note.error}
        placeholder="Condition, discrepancies, or follow-up…"
      />
      {note.error ? (
        <span role="alert" className="text-destructive">
          {note.error}
        </span>
      ) : null}
      {note.feedback.map((item, index) => (
        <span role="status" key={index}>
          {item.message}
        </span>
      ))}
    </label>
  );
}
