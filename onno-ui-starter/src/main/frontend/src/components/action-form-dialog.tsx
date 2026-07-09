import { useEffect, useMemo, useState } from "react";
import { createPortal } from "react-dom";
import { Loader2, Plus, Trash2 } from "lucide-react";
import { cn } from "@/lib/utils";
import { Input } from "@/components/ui/input";
import { Textarea } from "@/components/ui/textarea";
import { RefSelect } from "@/components/ref-select";

/**
 * One scalar field of an action's form dialog — the client shape of a server-declared
 * {@code ActionSpec.ActionBuilder.form(...)} input (same wire shape as a toolbar input, plus
 * {@code required}).
 */
export type ActionFormField = {
  kind?: "field";
  key: string;
  label: string;
  type: "text" | "textarea" | "date" | "number" | "select" | "reference";
  placeholder?: string;
  options?: string[];
  value?: string;
  required?: boolean;
  /** For a {@code reference} field: the target catalog/document's logical name. */
  reference?: string;
  /** For a {@code reference} field: whether the target is a catalog or a document. */
  refKind?: "catalog" | "document";
};

/**
 * A repeatable row group — a transient tabular grid (add/remove rows) whose columns are scalar
 * fields ({@code ActionSpec}'s {@code InputSpec.group(...)}). {@code required} gates submit on at
 * least one row; a column's own {@code required} gates that cell in every kept row.
 */
export type ActionFormGroup = {
  kind: "group";
  key: string;
  label: string;
  required?: boolean;
  columns: ActionFormField[];
};

/** A form item is either a scalar field or a repeatable row group. */
export type ActionFormItem = ActionFormField | ActionFormGroup;

/** A collected row group's value: one {column → value} object per row. */
export type ActionRowValue = Record<string, string>;

/** Submitted form values: scalars are strings, row groups are arrays of {column → value} rows. */
export type ActionFormValues = Record<string, string | ActionRowValue[]>;

function isGroup(item: ActionFormItem): item is ActionFormGroup {
  return item.kind === "group";
}

/** A row is "empty" (dropped on submit) when every cell is blank. */
function rowIsBlank(row: ActionRowValue): boolean {
  return Object.values(row).every((v) => (v ?? "").trim() === "");
}

/** Whether a kept row satisfies every required column. */
function rowComplete(group: ActionFormGroup, row: ActionRowValue): boolean {
  return group.columns.every((c) => !c.required || (row[c.key] ?? "").trim() !== "");
}

/**
 * The modal an action with a declared form opens before it runs: collects the fields' values and
 * hands them to {@code onSubmit} (which POSTs them as the action's {@code inputs}). Scalar fields
 * submit as strings; repeatable row groups submit as arrays of row objects (read back server-side
 * via {@code ActionContext.inputRows(key)}). Used by the list island's toolbar/row/context-menu
 * actions and the detail header's action cluster alike. Esc / backdrop / Cancel dismiss (unless a
 * submit is in flight); submit is gated on every {@code required} field and row group. Rendered
 * into {@code document.body} so no DivKit wrapper can clip or swallow it.
 */
export function ActionFormDialog({
  title,
  fields,
  busy,
  onSubmit,
  onClose,
}: {
  title: string;
  fields: ActionFormItem[];
  /** True while the action POST runs — locks the dialog and spins the submit button. */
  busy?: boolean;
  onSubmit: (values: ActionFormValues) => void;
  onClose: () => void;
}) {
  const scalars = useMemo(() => fields.filter((f): f is ActionFormField => !isGroup(f)), [fields]);
  const groups = useMemo(() => fields.filter(isGroup), [fields]);

  const [values, setValues] = useState<Record<string, string>>(() =>
    Object.fromEntries(scalars.map((f) => [f.key, f.value ?? ""]))
  );
  // Each group starts with one blank row so the grid is visible and directly editable.
  const [rows, setRows] = useState<Record<string, ActionRowValue[]>>(() =>
    Object.fromEntries(groups.map((g) => [g.key, [blankRow(g)]]))
  );

  const canSubmit = useMemo(() => {
    const scalarsOk = scalars.every((f) => !f.required || (values[f.key] ?? "").trim() !== "");
    const groupsOk = groups.every((g) => {
      const kept = (rows[g.key] ?? []).filter((r) => !rowIsBlank(r));
      if (g.required && kept.length === 0) return false;
      return kept.every((r) => rowComplete(g, r));
    });
    return scalarsOk && groupsOk;
  }, [scalars, groups, values, rows]);

  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      if (e.key === "Escape" && !busy) onClose();
    };
    window.addEventListener("keydown", onKey);
    return () => window.removeEventListener("keydown", onKey);
  }, [busy, onClose]);

  const set = (key: string, value: string) => setValues((v) => ({ ...v, [key]: value }));

  const setCell = (groupKey: string, rowIndex: number, col: string, value: string) =>
    setRows((prev) => {
      const list = (prev[groupKey] ?? []).map((r, i) => (i === rowIndex ? { ...r, [col]: value } : r));
      return { ...prev, [groupKey]: list };
    });

  const addRow = (g: ActionFormGroup) =>
    setRows((prev) => ({ ...prev, [g.key]: [...(prev[g.key] ?? []), blankRow(g)] }));

  const removeRow = (groupKey: string, rowIndex: number) =>
    setRows((prev) => {
      const list = (prev[groupKey] ?? []).filter((_, i) => i !== rowIndex);
      // Keep at least one (blank) row so the grid never collapses to nothing to type into.
      return { ...prev, [groupKey]: list.length ? list : [{}] };
    });

  const submit = () => {
    if (!canSubmit || busy) return;
    const out: ActionFormValues = { ...values };
    for (const g of groups) out[g.key] = (rows[g.key] ?? []).filter((r) => !rowIsBlank(r));
    onSubmit(out);
  };

  const cellControl = (f: ActionFormField, value: string, onChange: (v: string) => void, autoFocus = false) => {
    const common = {
      value,
      placeholder: f.placeholder || undefined,
      disabled: busy,
      autoFocus,
    };
    if (f.type === "reference" && f.reference) {
      return (
        <RefSelect
          targetName={f.reference}
          refKind={f.refKind ?? "catalog"}
          value={value || undefined}
          onChange={onChange}
        />
      );
    }
    if (f.type === "textarea") {
      return <Textarea {...common} rows={2} onChange={(e) => onChange(e.target.value)} />;
    }
    if (f.type === "select") {
      return (
        <select
          {...common}
          onChange={(e) => onChange(e.target.value)}
          className={cn(
            "h-9 w-full rounded-field border border-input bg-background px-3 text-sm text-foreground",
            "focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring disabled:opacity-50"
          )}
        >
          {/* An unset value renders a blank choice so a required select starts unanswered. */}
          {(value ?? "") === "" ? <option value="" /> : null}
          {(f.options ?? []).map((o) => (
            <option key={o} value={o}>
              {o}
            </option>
          ))}
        </select>
      );
    }
    return (
      <Input
        {...common}
        type={f.type === "date" ? "date" : f.type === "number" ? "number" : "text"}
        onChange={(e) => onChange(e.target.value)}
        onKeyDown={(e) => {
          if (e.key === "Enter" && f.type !== "textarea") submit();
        }}
      />
    );
  };

  const scalarField = (f: ActionFormField, index: number) => (
    <div key={f.key}>
      <label htmlFor={`action-form-${f.key}`} className="mb-1 block text-xs font-medium text-muted-foreground">
        {f.label}
        {f.required ? <span className="text-destructive"> *</span> : null}
      </label>
      {cellControl(f, values[f.key] ?? "", (v) => set(f.key, v), index === 0)}
    </div>
  );

  // Mirrors the entity form's TabularSectionEditor so an action's row group looks and behaves like a
  // document's @TabularSection editor: a titled card, a header row of column labels, one compact flex
  // line per row with the same cell controls, a hover-fade remove, and a top-right "Add row".
  const groupGrid = (g: ActionFormGroup) => (
    <div key={g.key} className="rounded-card border border-border bg-card p-4 sm:p-5">
      <div className="mb-3 flex items-center justify-between">
        <h3 className="text-sm font-semibold text-foreground">
          {g.label}
          {g.required ? <span className="ml-0.5 text-destructive">*</span> : null}
        </h3>
        <button
          type="button"
          disabled={busy}
          onClick={() => addRow(g)}
          className="inline-flex items-center gap-1.5 rounded-control bg-secondary px-3.5 py-2 text-sm font-medium text-foreground transition-colors hover:bg-accent disabled:opacity-50"
        >
          <Plus className="size-4" aria-hidden="true" />
          Add row
        </button>
      </div>
      {(rows[g.key] ?? []).length === 0 ? (
        <p className="text-sm text-muted-foreground">No rows yet.</p>
      ) : (
        <div className="overflow-x-auto">
          <div className="min-w-[28rem]">
            {/* Column headers, aligned to the cells below via matching flex rules. */}
            <div className="flex items-end gap-3 px-2 pb-1.5">
              {g.columns.map((c) => (
                <div key={c.key} className="min-w-0 grow basis-44 text-xs font-medium text-muted-foreground">
                  {c.label}
                  {c.required ? <span className="ml-0.5 text-destructive">*</span> : null}
                </div>
              ))}
              <span className="w-8 shrink-0" aria-hidden="true" />
            </div>
            <div className="space-y-1">
              {(rows[g.key] ?? []).map((row, ri) => (
                <div
                  key={ri}
                  className="group flex items-center gap-3 rounded-control px-2 py-1 transition-colors hover:bg-muted/40"
                >
                  {g.columns.map((c) => (
                    <div key={c.key} className="min-w-0 grow basis-44">
                      {cellControl(c, row[c.key] ?? "", (v) => setCell(g.key, ri, c.key, v))}
                    </div>
                  ))}
                  <button
                    type="button"
                    disabled={busy}
                    onClick={() => removeRow(g.key, ri)}
                    aria-label={`Remove row ${ri + 1}`}
                    title="Remove row"
                    className="grid size-8 shrink-0 place-items-center rounded-control text-muted-foreground opacity-50 transition-colors hover:bg-accent hover:text-destructive group-hover:opacity-100 disabled:opacity-50"
                  >
                    <Trash2 className="size-4" aria-hidden="true" />
                  </button>
                </div>
              ))}
            </div>
          </div>
        </div>
      )}
    </div>
  );

  return createPortal(
    <div className="fixed inset-0 z-[60] flex items-center justify-center p-4" role="dialog" aria-modal="true">
      <div
        className="absolute inset-0 bg-black/50 backdrop-blur-[1px]"
        onClick={() => {
          if (!busy) onClose();
        }}
      />
      <div
        className={cn(
          "relative z-10 max-h-[85vh] w-full overflow-y-auto rounded-card border border-border bg-card p-5 shadow-2xl",
          groups.length ? "max-w-2xl" : "max-w-sm"
        )}
      >
        <h2 className="text-base font-semibold text-foreground">{title}</h2>
        <div className="mt-4 space-y-3">
          {scalars.map((f, i) => scalarField(f, i))}
          {groups.map((g) => groupGrid(g))}
        </div>
        <div className="mt-5 flex justify-end gap-2">
          <button
            type="button"
            disabled={busy}
            onClick={onClose}
            className="rounded-control border border-border px-3.5 py-2 text-sm font-medium text-foreground transition-colors hover:bg-muted disabled:opacity-60"
          >
            Cancel
          </button>
          <button
            type="button"
            disabled={!canSubmit || busy}
            onClick={submit}
            className="inline-flex items-center gap-1.5 rounded-control bg-primary px-3.5 py-2 text-sm font-medium text-primary-foreground transition-colors hover:bg-primary/90 disabled:cursor-not-allowed disabled:opacity-60"
          >
            {busy ? <Loader2 className="size-4 animate-spin" aria-hidden="true" /> : null}
            {title}
          </button>
        </div>
      </div>
    </div>,
    document.body
  );
}

/** A fresh row seeded from each column's default value. */
function blankRow(g: ActionFormGroup): ActionRowValue {
  return Object.fromEntries(g.columns.map((c) => [c.key, c.value ?? ""]));
}
