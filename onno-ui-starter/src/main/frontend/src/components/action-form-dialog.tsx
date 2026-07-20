import { useEffect, useMemo, useState } from "react";
import { Loader2, Plus, Trash2 } from "lucide-react";
import { api } from "@/lib/api";
import { actionFeedbackFromError } from "@/lib/action-feedback";
import { cn } from "@/lib/utils";
import { DialogShell, type DialogShellSize } from "@/components/ui/dialog-shell";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from "@/components/ui/select";
import { Textarea } from "@/components/ui/textarea";
import { DatePicker } from "@/components/date-picker";
import { RefSelect } from "@/components/ref-select";
import { useMessages } from "@/providers/messages-provider";
import type { ActionFeedback, ActionSeverity } from "@/lib/types";
import { DynamicLucide } from "@/lib/icon-bridge";

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

/** Server-authored presentation metadata for the canonical action-form dialog. */
export type ActionFormDialogConfig = {
  title?: string;
  description?: string;
  submitLabel?: string;
  cancelLabel?: string;
  icon?: string;
  tone?: ActionSeverity;
  size?: DialogShellSize;
};

// Radix reserves the empty string for the trigger placeholder, so optional selects use a stable
// non-empty option value that is normalized back to an empty action input.
const NO_SELECTION_VALUE = "__onno_no_selection__";

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
  defaultsSource,
  dialog,
  onSubmit,
  onClose,
}: {
  title: string;
  fields: ActionFormItem[];
  /** True while the action POST runs — locks the dialog and spins the submit button. */
  busy?: boolean;
  dialog?: ActionFormDialogConfig;
  /**
   * Set when the action declares server-computed opening values (descriptor dynamicForm: true):
   * the dialog fetches GET /api/actions/{kind}/{name}/{key}/form?id= on open and seeds the scalar
   * inputs / row groups from the response before becoming editable. A fetch failure falls back to
   * the static defaults — the dialog must never be blocked by a broken hook.
   */
  defaultsSource?: { kind: string; name: string; key: string; id?: string };
  onSubmit: (values: ActionFormValues) => Promise<unknown> | unknown;
  onClose: () => void;
}) {
  const t = useMessages();
  const scalars = useMemo(() => fields.filter((f): f is ActionFormField => !isGroup(f)), [fields]);
  const groups = useMemo(() => fields.filter(isGroup), [fields]);

  const [values, setValues] = useState<Record<string, string>>(() =>
    Object.fromEntries(scalars.map((f) => [f.key, f.value ?? ""]))
  );
  // Each group starts with one blank row so the grid is visible and directly editable.
  const [rows, setRows] = useState<Record<string, ActionRowValue[]>>(() =>
    Object.fromEntries(groups.map((g) => [g.key, [blankRow(g)]]))
  );
  // While the server-computed opening values load, the dialog shows a busy shell (no flash of
  // blank inputs that then repopulate under the user's cursor).
  const [seeding, setSeeding] = useState(!!defaultsSource);
  const [submitting, setSubmitting] = useState(false);
  const [feedback, setFeedback] = useState<ActionFeedback | null>(null);

  useEffect(() => {
    if (!defaultsSource) return;
    let cancelled = false;
    api
      .getActionFormDefaults(defaultsSource.kind, defaultsSource.name, defaultsSource.key, defaultsSource.id)
      .then((d) => {
        if (cancelled) return;
        if (d?.values && Object.keys(d.values).length) {
          setValues((prev) => ({ ...prev, ...d.values }));
        }
        if (d?.rows) {
          setRows((prev) => {
            const next = { ...prev };
            for (const g of groups) {
              const seeded = d.rows[g.key];
              if (Array.isArray(seeded) && seeded.length) {
                // Overlay each fetched row onto the column defaults so unmentioned cells keep them.
                next[g.key] = seeded.map((r) => ({ ...blankRow(g), ...r }));
              }
            }
            return next;
          });
        }
      })
      .catch(() => {
        /* fall back to static defaults */
      })
      .finally(() => {
        if (!cancelled) setSeeding(false);
      });
    return () => {
      cancelled = true;
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps -- one fetch per dialog open
  }, []);

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
      if (e.key === "Escape" && !busy && !submitting) onClose();
    };
    window.addEventListener("keydown", onKey);
    return () => window.removeEventListener("keydown", onKey);
  }, [busy, submitting, onClose]);

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

  const submit = async () => {
    if (!canSubmit || locked) return;
    const out: ActionFormValues = { ...values };
    for (const g of groups) out[g.key] = (rows[g.key] ?? []).filter((r) => !rowIsBlank(r));
    setFeedback(null);
    let pending = false;
    try {
      const result = onSubmit(out);
      if (result && typeof (result as PromiseLike<unknown>).then === "function") {
        pending = true;
        setSubmitting(true);
        await result;
      }
      onClose();
    } catch (error) {
      const rejected = actionFeedbackFromError(error, true);
      if (rejected) {
        setFeedback(rejected);
        if (rejected.keepFormOpen === false) onClose();
      }
    } finally {
      if (pending) setSubmitting(false);
    }
  };

  const locked = busy || submitting || seeding;

  const cellControl = (
    f: ActionFormField,
    value: string,
    onChange: (v: string) => void,
    autoFocus = false,
    id?: string
  ) => {
    const common = {
      id,
      value,
      placeholder: f.placeholder || undefined,
      disabled: locked,
      autoFocus,
    };
    if (f.type === "reference" && f.reference) {
      return (
        <RefSelect
          targetName={f.reference}
          refKind={f.refKind ?? "catalog"}
          value={value || undefined}
          clearable={!f.required}
          onChange={(id) => onChange(id ?? "")}
        />
      );
    }
    if (f.type === "textarea") {
      return <Textarea {...common} rows={2} onChange={(e) => onChange(e.target.value)} />;
    }
    if (f.type === "select") {
      return (
        <Select
          value={value}
          disabled={locked}
          onValueChange={(next) => onChange(next === NO_SELECTION_VALUE ? "" : next)}
        >
          <SelectTrigger id={id} autoFocus={autoFocus}>
            <SelectValue placeholder={f.placeholder || t("form.select", { name: f.label })} />
          </SelectTrigger>
          <SelectContent>
            {!f.required ? (
              <SelectItem value={NO_SELECTION_VALUE}>{t("form.noSelection")}</SelectItem>
            ) : null}
            {(f.options ?? []).map((o) => (
              <SelectItem key={o} value={o}>
                {o}
              </SelectItem>
            ))}
          </SelectContent>
        </Select>
      );
    }
    if (f.type === "date") {
      return <DatePicker value={value} onChange={onChange} />;
    }
    return (
      <Input
        {...common}
        type={f.type === "number" ? "number" : "text"}
        onChange={(e) => onChange(e.target.value)}
        onKeyDown={(e) => {
          if (e.key === "Enter" && f.type !== "textarea") submit();
        }}
      />
    );
  };

  const scalarField = (f: ActionFormField, index: number) => (
    <div key={f.key} className="space-y-1.5">
      <Label htmlFor={`action-form-${f.key}`} className="text-xs text-muted-foreground">
        {f.label}
        {f.required ? <span className="text-destructive"> *</span> : null}
      </Label>
      {cellControl(f, values[f.key] ?? "", (v) => set(f.key, v), index === 0, `action-form-${f.key}`)}
      {(feedback?.fieldErrors?.[f.key] ?? []).map((error) => (
        <p key={error} className="mt-1 text-xs text-destructive" role="alert">{error}</p>
      ))}
    </div>
  );

  // Mirrors the entity form's TabularSectionEditor so an action's row group looks and behaves like a
  // document's @TabularSection editor: a titled card, a header row of column labels, one compact flex
  // line per row with the same cell controls, a hover-fade remove, and "Add row" under the last row
  // (where the new row appears).
  const groupAddRowBtn = (g: ActionFormGroup) => (
    <Button
      type="button"
      variant="outline"
      size="sm"
      disabled={locked}
      onClick={() => addRow(g)}
      className="mt-1 w-full justify-start border-dashed text-muted-foreground"
    >
      <Plus className="size-4" aria-hidden="true" />
      {t("action.addRow")}
    </Button>
  );

  const groupGrid = (g: ActionFormGroup) => (
    <div key={g.key} className="rounded-card border border-border bg-card p-4 sm:p-5">
      <div className="mb-3 flex items-center justify-between">
        <h3 className="text-sm font-semibold text-foreground">
          {g.label}
          {g.required ? <span className="ml-0.5 text-destructive">*</span> : null}
        </h3>
      </div>
      {(rows[g.key] ?? []).length === 0 ? (
        <>
          <p className="text-sm text-muted-foreground">{t("empty.noRows")}</p>
          {groupAddRowBtn(g)}
        </>
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
                  className="group flex items-center gap-3 rounded-sm px-2 py-1 transition-colors hover:bg-muted/40"
                >
                  {g.columns.map((c) => (
                    <div key={c.key} className="min-w-0 grow basis-44">
                      {cellControl(c, row[c.key] ?? "", (v) => setCell(g.key, ri, c.key, v))}
                    </div>
                  ))}
                  <Button
                    type="button"
                    variant="ghost"
                    size="icon"
                    disabled={locked}
                    onClick={() => removeRow(g.key, ri)}
                    aria-label={`Remove row ${ri + 1}`}
                    title="Remove row"
                    className="size-8 shrink-0 text-muted-foreground opacity-50 hover:text-destructive group-hover:opacity-100"
                  >
                    <Trash2 className="size-4" aria-hidden="true" />
                  </Button>
                </div>
              ))}
            </div>
            {groupAddRowBtn(g)}
          </div>
        </div>
      )}
    </div>
  );

  const formErrors = feedback?.formErrors ?? [];
  const inlineMessage =
    feedback?.presentation === "inline" || formErrors.length || Object.keys(feedback?.fieldErrors ?? {}).length
      ? feedback?.message
      : null;
  return (
    <DialogShell
      title={dialog?.title || title}
      description={dialog?.description}
      tone={dialog?.tone || "info"}
      size={dialog?.size || (groups.length ? "lg" : "sm")}
      icon={dialog?.icon ? <DynamicLucide name={dialog.icon} size={20} /> : undefined}
      dismissable={!locked}
      onOpenChange={(open) => {
        if (!open && !locked) onClose();
      }}
      footer={
        <>
          <Button
            type="button"
            variant="outline"
            disabled={locked}
            onClick={onClose}
          >
            {dialog?.cancelLabel || t("action.cancel")}
          </Button>
          <Button
            type="button"
            disabled={!canSubmit || locked}
            onClick={() => void submit()}
          >
            {locked ? <Loader2 className="size-4 animate-spin" aria-hidden="true" /> : null}
            {dialog?.submitLabel || title}
          </Button>
        </>
      }
    >
      {seeding ? (
        <div className="flex min-h-24 items-center justify-center gap-2 text-sm text-muted-foreground">
          <Loader2 className="size-4 animate-spin" aria-hidden="true" />
          {t("loading.working")}
        </div>
      ) : (
        <fieldset disabled={locked} className="space-y-3">
          {inlineMessage || formErrors.length ? (
            <div className="rounded-field border border-destructive/30 bg-destructive/10 px-3 py-2 text-sm text-destructive" role="alert">
              {inlineMessage ? <p>{inlineMessage}</p> : null}
              {formErrors.length ? (
                <ul className={cn("list-disc pl-5", inlineMessage && "mt-1")}>
                  {formErrors.map((error) => <li key={error}>{error}</li>)}
                </ul>
              ) : null}
            </div>
          ) : null}
          {scalars.map((f, i) => scalarField(f, i))}
          {groups.map((g) => groupGrid(g))}
        </fieldset>
      )}
    </DialogShell>
  );
}

/** A fresh row seeded from each column's default value. */
function blankRow(g: ActionFormGroup): ActionRowValue {
  return Object.fromEntries(g.columns.map((c) => [c.key, c.value ?? ""]));
}
