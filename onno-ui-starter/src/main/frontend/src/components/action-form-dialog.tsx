import { useEffect, useMemo, useState } from "react";
import { createPortal } from "react-dom";
import { Loader2 } from "lucide-react";
import { cn } from "@/lib/utils";
import { Input } from "@/components/ui/input";
import { Textarea } from "@/components/ui/textarea";

/**
 * One field of an action's form dialog — the client shape of a server-declared
 * {@code ActionSpec.ActionBuilder.form(...)} input (same wire shape as a toolbar input, plus
 * {@code required}).
 */
export type ActionFormField = {
  key: string;
  label: string;
  type: "text" | "textarea" | "date" | "number" | "select";
  placeholder?: string;
  options?: string[];
  value?: string;
  required?: boolean;
};

/**
 * The modal an action with a declared form opens before it runs: collects the fields' values and
 * hands them to {@code onSubmit} (which POSTs them as the action's {@code inputs}). Used by the
 * list island's toolbar/row/context-menu actions and the detail header's action cluster alike.
 * Esc / backdrop / Cancel dismiss (unless a submit is in flight); submit is gated on every
 * {@code required} field being non-blank. Rendered into {@code document.body} so no DivKit
 * wrapper can clip or swallow it.
 */
export function ActionFormDialog({
  title,
  fields,
  busy,
  onSubmit,
  onClose,
}: {
  title: string;
  fields: ActionFormField[];
  /** True while the action POST runs — locks the dialog and spins the submit button. */
  busy?: boolean;
  onSubmit: (values: Record<string, string>) => void;
  onClose: () => void;
}) {
  const [values, setValues] = useState<Record<string, string>>(() =>
    Object.fromEntries(fields.map((f) => [f.key, f.value ?? ""]))
  );

  const canSubmit = useMemo(
    () => fields.every((f) => !f.required || (values[f.key] ?? "").trim() !== ""),
    [fields, values]
  );

  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      if (e.key === "Escape" && !busy) onClose();
    };
    window.addEventListener("keydown", onKey);
    return () => window.removeEventListener("keydown", onKey);
  }, [busy, onClose]);

  const set = (key: string, value: string) => setValues((v) => ({ ...v, [key]: value }));

  const submit = () => {
    if (canSubmit && !busy) onSubmit(values);
  };

  const fieldControl = (f: ActionFormField, index: number) => {
    const common = {
      id: `action-form-${f.key}`,
      value: values[f.key] ?? "",
      placeholder: f.placeholder || undefined,
      disabled: busy,
      autoFocus: index === 0,
    };
    if (f.type === "textarea") {
      return (
        <Textarea
          {...common}
          rows={3}
          onChange={(e) => set(f.key, e.target.value)}
        />
      );
    }
    if (f.type === "select") {
      return (
        <select
          {...common}
          onChange={(e) => set(f.key, e.target.value)}
          className={cn(
            "h-9 w-full rounded-field border border-input bg-background px-3 text-sm text-foreground",
            "focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring disabled:opacity-50"
          )}
        >
          {/* An unset value renders a blank choice so a required select starts unanswered. */}
          {(values[f.key] ?? "") === "" ? <option value="" /> : null}
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
        onChange={(e) => set(f.key, e.target.value)}
        onKeyDown={(e) => {
          if (e.key === "Enter") submit();
        }}
      />
    );
  };

  return createPortal(
    <div className="fixed inset-0 z-[60] flex items-center justify-center p-4" role="dialog" aria-modal="true">
      <div
        className="absolute inset-0 bg-black/50 backdrop-blur-[1px]"
        onClick={() => {
          if (!busy) onClose();
        }}
      />
      <div className="relative z-10 w-full max-w-sm rounded-card border border-border bg-card p-5 shadow-2xl">
        <h2 className="text-base font-semibold text-foreground">{title}</h2>
        <div className="mt-4 space-y-3">
          {fields.map((f, i) => (
            <div key={f.key}>
              <label
                htmlFor={`action-form-${f.key}`}
                className="mb-1 block text-xs font-medium text-muted-foreground"
              >
                {f.label}
                {f.required ? <span className="text-destructive"> *</span> : null}
              </label>
              {fieldControl(f, i)}
            </div>
          ))}
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
