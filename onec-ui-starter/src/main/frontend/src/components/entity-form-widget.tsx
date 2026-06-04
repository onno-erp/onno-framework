import { useMemo, useState } from "react";
import { toast } from "sonner";
import { Check, CircleCheck, Plus, Trash2, X } from "lucide-react";
import type { AttributeMeta, EntityRecord, TabularSectionMeta } from "@/lib/types";
import { api, ApiError } from "@/lib/api";
import { cn } from "@/lib/utils";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { Checkbox } from "@/components/ui/checkbox";
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from "@/components/ui/select";
import { RefSelect } from "@/components/ref-select";
import { DatePicker } from "@/components/date-picker";
import { GeoPicker } from "@/components/geo-picker";
import { ImagePicker, GalleryPicker } from "@/components/image-picker";

// Matches the DivKit action pills (Edit/Delete/New): a compact dark pill, icon + label,
// rounded-lg, text-sm/medium, with the same vertical/horizontal rhythm.
const actionBtn =
  "inline-flex items-center gap-1.5 rounded-lg bg-secondary px-3.5 py-2 text-sm font-medium transition-colors hover:bg-accent disabled:opacity-50";

// The portable form descriptor the server emits as the onec-form custom component.
export type FormDescriptor = {
  kind: "documents" | "catalogs";
  name: string;
  id: string | null;
  title: string;
  submitLabel: string;
  /**
   * A "Duplicate" form: {@code id} identifies the source record (so the host closes the right
   * pane), but the form submits as a create into a brand-new record. {@code initial} carries the
   * source's attributes/line items minus its identity (see DivKitController#duplicateDraft).
   */
  duplicate?: boolean;
  meta: {
    name: string;
    autoNumber?: boolean;
    /** Documents that implement Postable can be posted from the form (1C-style). */
    postable?: boolean;
    attributes: AttributeMeta[];
    tabularSections?: TabularSectionMeta[];
  };
  initial: EntityRecord | null;
};

// One editable field: either a catalog system field (code/description) or an attribute.
type Field =
  | { kind: "system"; key: string; label: string; column: string }
  | { kind: "attr"; key: string; label: string; attr: AttributeMeta };

function isNumeric(javaType: string): boolean {
  return ["BigDecimal", "Integer", "Long", "Double", "Float", "Short", "int", "long", "double"].includes(
    javaType
  );
}

const EMAIL_RE = /^[^@\s]+@[^@\s]+\.[^@\s]+$/;
const MAX_VARCHAR = 65535;

function fmtNum(n: number): string {
  return Number.isInteger(n) ? String(n) : String(n);
}

/**
 * Client-side mirror of the server's AttributeValidator (required, length, min/max, pattern,
 * email) for instant inline feedback. The server re-checks authoritatively before write — this
 * just avoids a round-trip for the common cases. Returns a message, or null when the value is ok.
 */
function validateField(attr: AttributeMeta, value: unknown): string | null {
  const empty =
    value == null || value === "" || (typeof value === "string" && value.trim() === "");
  if (attr.required && empty) return `${attr.displayName} is required`;
  if (empty) return null;

  if (typeof value === "string") {
    if (attr.length > 0 && attr.length <= MAX_VARCHAR && value.length > attr.length) {
      return `${attr.displayName} must be at most ${attr.length} characters`;
    }
    if (attr.minLength && value.length < attr.minLength) {
      return `${attr.displayName} must be at least ${attr.minLength} characters`;
    }
    if (attr.pattern) {
      // Anchor to a full match, matching the server's Pattern.matches semantics.
      let ok = true;
      try {
        ok = new RegExp(`^(?:${attr.pattern})$`).test(value);
      } catch {
        ok = true; // a malformed pattern shouldn't block the user; the server is authoritative
      }
      if (!ok) return `${attr.displayName} is not in the expected format`;
    }
    if (attr.email && !EMAIL_RE.test(value)) {
      return `${attr.displayName} must be a valid email address`;
    }
  }

  if (isNumeric(attr.javaType)) {
    const n = typeof value === "number" ? value : Number(value);
    if (!Number.isNaN(n)) {
      if (attr.min != null && n < attr.min) return `${attr.displayName} must be at least ${fmtNum(attr.min)}`;
      if (attr.max != null && n > attr.max) return `${attr.displayName} must be at most ${fmtNum(attr.max)}`;
    }
  }
  return null;
}

// Fire an onec:// action / pane-close through the host (divkit-view) — same routing the
// DivKit surfaces use, so a form opened in an island behaves like any other navigation.
function dispatchAction(url: string) {
  window.dispatchEvent(new CustomEvent("onec:action", { detail: url }));
}
function dispatchClose(path: string) {
  window.dispatchEvent(new CustomEvent("onec:closepath", { detail: path }));
}

export function EntityFormWidget({ form }: { form: FormDescriptor }) {
  const { kind, name, id, meta, initial } = form;
  // A duplicate carries the source id (for pane routing/close) but still creates a new record.
  const isDuplicate = form.duplicate === true;
  const isEdit = id != null && !isDuplicate;
  const formPath = `/${kind}/${name}/${
    isDuplicate ? `${id}/duplicate` : isEdit ? `${id}/edit` : "new"
  }`;

  // Build the ordered field list: catalogs lead with code (unless auto-numbered) +
  // description; both then list the visible-in-form attributes by their order hint.
  const fields = useMemo<Field[]>(() => {
    const out: Field[] = [];
    if (kind === "catalogs") {
      if (!meta.autoNumber) {
        out.push({ kind: "system", key: "code", label: "Code", column: "_code" });
      }
      out.push({ kind: "system", key: "description", label: "Description", column: "_description" });
    }
    const attrs = meta.attributes
      .filter((a) => a.visibleInForm !== false)
      .sort((a, b) => (a.order ?? 0) - (b.order ?? 0));
    for (const attr of attrs) {
      out.push({ kind: "attr", key: attr.fieldName, label: attr.displayName, attr });
    }
    return out;
  }, [kind, meta]);

  // Tabular sections (document child collections). The metadata already ships them and the
  // REST layer round-trips rows; this is the form's editable grid for each one.
  const sections = useMemo<TabularSectionMeta[]>(() => meta.tabularSections ?? [], [meta]);

  // 1C-style posting: a postable document offers Write (save, no posting) alongside
  // Post / Re-post (save then post). Already-posted documents re-post. Catalogs and
  // non-postable documents keep the single Save button.
  const postable = kind === "documents" && meta.postable === true;
  const wasPosted = Boolean(initial?._posted);

  const [data, setData] = useState<EntityRecord>(() => {
    const seed: EntityRecord = {};
    if (!initial) return seed;
    for (const f of fields) {
      // Secret fields are write-only: never seed the control from the loaded value (the
      // server only ever sends a "set" sentinel anyway). Leaving it blank means an unchanged
      // save omits the field, so the stored secret is preserved.
      if (f.kind === "attr" && f.attr.secret) continue;
      const col = f.kind === "system" ? f.column : f.attr.columnName;
      if (initial[col] != null) seed[f.key] = initial[col];
    }
    return seed;
  });

  // Rows per section, keyed by attribute fieldName. Loaded rows arrive keyed by column name
  // (with resolved *_display labels), so seed each cell from initial[section][columnName] —
  // the same column→field asymmetry the top-level fields handle above. All attributes are
  // seeded (not just the visible ones) so hidden columns survive the delete-and-reinsert.
  const [rowsBySection, setRowsBySection] = useState<Record<string, EntityRecord[]>>(() => {
    const seed: Record<string, EntityRecord[]> = {};
    for (const ts of sections) {
      const raw = initial?.[ts.name];
      seed[ts.name] = Array.isArray(raw)
        ? (raw as EntityRecord[]).map((r) => {
            const row: EntityRecord = {};
            for (const attr of ts.attributes) {
              if (attr.secret) continue; // write-only — see top-level seed
              if (r[attr.columnName] != null) row[attr.fieldName] = r[attr.columnName];
            }
            return row;
          })
        : [];
    }
    return seed;
  });

  const [saving, setSaving] = useState(false);
  // Inline validation messages, keyed by field key (attr fieldName / system "code"/"description").
  const [errors, setErrors] = useState<Record<string, string>>({});

  const set = (key: string, value: unknown) => {
    setData((prev) => ({ ...prev, [key]: value }));
    // Clear a field's error as soon as the user edits it; it re-checks on the next save.
    setErrors((prev) => {
      if (!prev[key]) return prev;
      const next = { ...prev };
      delete next[key];
      return next;
    });
  };

  // Validate every attribute field against its constraints; returns key -> message.
  const validateAll = (): Record<string, string> => {
    const errs: Record<string, string> = {};
    for (const f of fields) {
      if (f.kind !== "attr") continue;
      const msg = validateField(f.attr, data[f.key]);
      if (msg) errs[f.key] = msg;
    }
    return errs;
  };

  const addRow = (section: string) =>
    setRowsBySection((prev) => ({ ...prev, [section]: [...(prev[section] ?? []), {}] }));
  const removeRow = (section: string, idx: number) =>
    setRowsBySection((prev) => ({
      ...prev,
      [section]: (prev[section] ?? []).filter((_, i) => i !== idx),
    }));
  const setCell = (section: string, idx: number, key: string, value: unknown) =>
    setRowsBySection((prev) => ({
      ...prev,
      [section]: (prev[section] ?? []).map((row, i) => (i === idx ? { ...row, [key]: value } : row)),
    }));

  const save = async (thenPost = false) => {
    // Instant client-side check first — don't round-trip a form we already know is invalid.
    const clientErrors = validateAll();
    if (Object.keys(clientErrors).length > 0) {
      setErrors(clientErrors);
      return;
    }
    setErrors({});
    setSaving(true);
    try {
      const payload = { ...data };
      // Attach each tabular section as rows keyed by fieldName (what insertTabularSections
      // reads server-side). Drop rows where every attribute is blank.
      for (const ts of sections) {
        const rows = (rowsBySection[ts.name] ?? [])
          .filter((row) =>
            ts.attributes.some((a) => {
              const v = row[a.fieldName];
              return v !== null && v !== undefined && v !== "";
            })
          )
          .map((row) => {
            const out: EntityRecord = {};
            for (const a of ts.attributes) {
              const v = row[a.fieldName];
              // Booleans map to primitive columns — always send true/false, never null.
              out[a.fieldName] = /^(boolean|Boolean)$/.test(a.javaType) ? v === true : v ?? null;
            }
            return out;
          });
        payload[ts.name] = rows;
      }
      let saved: EntityRecord;
      if (kind === "documents") {
        saved = isEdit ? await api.updateDocument(name, id!, payload) : await api.createDocument(name, payload);
      } else {
        saved = isEdit
          ? await api.updateCatalogItem(name, id!, payload)
          : await api.createCatalogItem(name, payload);
      }
      const savedId = String(isEdit ? id : saved._id);
      // Post after a successful save when requested. The document is already persisted, so
      // a posting failure (e.g. a validation/balance error) surfaces a toast but the saved
      // record still opens — the user can fix it and re-post. api.postDocument re-posts a
      // document that was already posted (the backend reverses old movements first).
      if (thenPost && kind === "documents") {
        try {
          await api.postDocument(name, savedId);
        } catch {
          // The error toast is already shown by the api layer; fall through to open the
          // saved (unposted) record so the user lands somewhere actionable.
        }
      }
      // Open the saved record and close the form pane (the list refreshes over SSE).
      dispatchAction(`onec://${kind}/${name}/${savedId}`);
      dispatchClose(formPath);
    } catch (e) {
      // A server-side validation 422 carries per-field messages — map them onto the inputs
      // (and surface any cross-field/form message as a toast). Anything else is a generic error.
      if (e instanceof ApiError && e.fieldErrors) {
        const mapped: Record<string, string> = {};
        for (const [field, messages] of Object.entries(e.fieldErrors)) {
          mapped[field] = Array.isArray(messages) ? messages[0] : String(messages);
        }
        setErrors(mapped);
      } else {
        toast.error(`Couldn't save: ${e instanceof Error ? e.message : String(e)}`);
      }
      setSaving(false);
    }
  };

  const cancel = () => {
    if (isEdit) dispatchAction(`onec://${kind}/${name}/${id}`);
    dispatchClose(formPath);
  };

  return (
    <div className="mx-auto w-full max-w-2xl">
      <h1 className="mb-5 text-xl font-semibold text-foreground">{form.title}</h1>
      <div className="space-y-4 rounded-2xl border border-border bg-card p-5">
        {fields.map((f) => (
          <FormFieldRow
            key={f.key}
            field={f}
            value={data[f.key]}
            error={errors[f.key]}
            onChange={(v) => set(f.key, v)}
          />
        ))}
      </div>
      {sections.map((ts) => (
        <TabularSectionEditor
          key={ts.name}
          section={ts}
          rows={rowsBySection[ts.name] ?? []}
          onAdd={() => addRow(ts.name)}
          onRemove={(idx) => removeRow(ts.name, idx)}
          onCell={(idx, key, value) => setCell(ts.name, idx, key, value)}
        />
      ))}
      <div className="mt-5 flex justify-end gap-2">
        <button
          type="button"
          className={cn(actionBtn, "text-muted-foreground hover:text-foreground")}
          onClick={cancel}
          disabled={saving}
        >
          <X className="size-4" aria-hidden="true" />
          Cancel
        </button>
        <button
          type="button"
          className={cn(actionBtn, "text-foreground")}
          onClick={() => save(false)}
          disabled={saving}
        >
          <Check className="size-4" aria-hidden="true" />
          {saving ? "Saving…" : postable ? "Write" : form.submitLabel}
        </button>
        {postable ? (
          <button
            type="button"
            className={cn(
              actionBtn,
              "bg-emerald-600 text-white hover:bg-emerald-600/90 hover:text-white"
            )}
            onClick={() => save(true)}
            disabled={saving}
          >
            <CircleCheck className="size-4" aria-hidden="true" />
            {wasPosted ? "Re-post" : "Post"}
          </button>
        ) : null}
      </div>
    </div>
  );
}

// An editable grid for one tabular section: add/remove rows, with each cell rendered by the
// same AttrControl the top-level fields use (so Ref pickers, enum selects, dates and typed
// inputs all behave identically). Only visible-in-form attributes get a column.
function TabularSectionEditor({
  section,
  rows,
  onAdd,
  onRemove,
  onCell,
}: {
  section: TabularSectionMeta;
  rows: EntityRecord[];
  onAdd: () => void;
  onRemove: (idx: number) => void;
  onCell: (idx: number, key: string, value: unknown) => void;
}) {
  const columns = useMemo<AttributeMeta[]>(
    () =>
      section.attributes
        .filter((a) => a.visibleInForm !== false)
        .sort((a, b) => (a.order ?? 0) - (b.order ?? 0)),
    [section]
  );
  const title = section.name.charAt(0).toUpperCase() + section.name.slice(1);

  return (
    <div className="mt-4 rounded-2xl border border-border bg-card p-5">
      <div className="mb-3 flex items-center justify-between">
        <h2 className="text-sm font-semibold text-foreground">{title}</h2>
        <button type="button" className={cn(actionBtn, "text-foreground")} onClick={onAdd}>
          <Plus className="size-4" aria-hidden="true" />
          Add row
        </button>
      </div>
      {rows.length === 0 ? (
        <p className="text-sm text-muted-foreground">No rows yet.</p>
      ) : (
        <div className="space-y-3">
          {rows.map((row, idx) => (
            <div key={idx} className="rounded-xl border border-border bg-background p-4">
              <div className="mb-2 flex items-center justify-between">
                <span className="text-xs font-medium text-muted-foreground">Row {idx + 1}</span>
                <button
                  type="button"
                  className="inline-flex items-center gap-1 rounded-md px-2 py-1 text-xs text-muted-foreground transition-colors hover:bg-accent hover:text-destructive"
                  onClick={() => onRemove(idx)}
                  aria-label={`Remove row ${idx + 1}`}
                >
                  <Trash2 className="size-3.5" aria-hidden="true" />
                  Remove
                </button>
              </div>
              <div className="grid gap-3 sm:grid-cols-2">
                {columns.map((attr) => (
                  <div key={attr.fieldName} className="grid gap-1.5">
                    <Label htmlFor={`${section.name}-${idx}-${attr.fieldName}`}>
                      {attr.displayName}
                      {attr.required ? <span className="ml-1 text-destructive">*</span> : null}
                    </Label>
                    <AttrControl
                      attr={attr}
                      value={row[attr.fieldName]}
                      onChange={(v) => onCell(idx, attr.fieldName, v)}
                    />
                  </div>
                ))}
              </div>
            </div>
          ))}
        </div>
      )}
    </div>
  );
}

function FormFieldRow({
  field,
  value,
  error,
  onChange,
}: {
  field: Field;
  value: unknown;
  error?: string;
  onChange: (value: unknown) => void;
}) {
  const required = field.kind === "attr" && field.attr.required;
  const placeholder = field.kind === "attr" ? field.attr.placeholder : undefined;
  const invalid = !!error;
  const control =
    field.kind === "system" ? (
      <Input
        aria-invalid={invalid}
        className={cn(invalid && "border-destructive focus-visible:ring-destructive")}
        value={(value as string) ?? ""}
        onChange={(e) => onChange(e.target.value)}
      />
    ) : (
      <AttrControl
        attr={field.attr}
        value={value}
        invalid={invalid}
        placeholder={placeholder}
        onChange={onChange}
      />
    );

  // Checkboxes own their label inline.
  if (field.kind === "attr" && /^(boolean|Boolean)$/.test(field.attr.javaType)) {
    return (
      <div>
        <div className="flex items-center gap-2">
          <Checkbox
            id={field.key}
            checked={!!value}
            onCheckedChange={(v) => onChange(v === true)}
          />
          <Label htmlFor={field.key}>{field.label}</Label>
        </div>
        {error ? <p className="mt-1 text-xs text-destructive">{error}</p> : null}
      </div>
    );
  }

  return (
    <div className="grid gap-1.5">
      <Label htmlFor={field.key}>
        {field.label}
        {required ? <span className="ml-1 text-destructive">*</span> : null}
      </Label>
      {control}
      {error ? <p className="text-xs text-destructive">{error}</p> : null}
    </div>
  );
}

function AttrControl({
  attr,
  value,
  onChange,
  invalid,
  placeholder,
}: {
  attr: AttributeMeta;
  value: unknown;
  onChange: (value: unknown) => void;
  invalid?: boolean;
  placeholder?: string;
}) {
  const invalidCls = invalid ? "border-destructive focus-visible:ring-destructive" : undefined;
  if (/^(boolean|Boolean)$/.test(attr.javaType)) {
    return (
      <div className="flex h-9 items-center">
        <Checkbox checked={!!value} onCheckedChange={(v) => onChange(v === true)} />
      </div>
    );
  }

  // A custom widget hint (.field(...).widget("map")) wins over the type-based control.
  // Geolocation stores a plain "lat,lng" string, so it lives on a String attribute.
  if (/^(map|geo|geolocation)$/i.test(attr.widget ?? "")) {
    return <GeoPicker value={value as string | undefined} onChange={onChange} />;
  }

  // Image widgets store a base64 data URL string (use a large-length / TEXT attribute). "avatar"
  // is a small round variant — it also lights up the existing .widget("avatar") hint. "images"/
  // "gallery" stores several, newline-joined.
  if (/^(images|gallery|photos)$/i.test(attr.widget ?? "")) {
    return <GalleryPicker value={value as string | undefined} onChange={onChange} />;
  }
  if (/^(image|photo)$/i.test(attr.widget ?? "")) {
    return <ImagePicker value={value as string | undefined} onChange={onChange} />;
  }
  if (/^avatar$/i.test(attr.widget ?? "")) {
    return <ImagePicker variant="avatar" value={value as string | undefined} onChange={onChange} />;
  }

  if (attr.isRef && attr.refTarget) {
    return (
      <RefSelect
        targetName={attr.refTarget}
        refKind={attr.refKind}
        value={value as string | undefined}
        onChange={onChange}
      />
    );
  }

  if (attr.isEnum && attr.enumValues) {
    return (
      <Select value={(value as string) ?? ""} onValueChange={onChange}>
        <SelectTrigger>
          <SelectValue placeholder={`Select ${attr.displayName}…`} />
        </SelectTrigger>
        <SelectContent>
          {attr.enumValues.map((ev) => (
            <SelectItem key={ev.id} value={ev.id}>
              {ev.name}
            </SelectItem>
          ))}
        </SelectContent>
      </Select>
    );
  }

  if (attr.secret) {
    // Write-only password control. Never seeded from the loaded value; leaving it blank on
    // edit keeps the stored secret unchanged (the field is simply omitted from the save).
    return (
      <Input
        type="password"
        autoComplete="new-password"
        aria-invalid={invalid}
        className={invalidCls}
        maxLength={attr.length > 0 ? attr.length : undefined}
        placeholder={placeholder || "Enter to set — leave blank to keep current"}
        value={(value as string) ?? ""}
        onChange={(e) => onChange(e.target.value)}
      />
    );
  }

  if (attr.javaType === "LocalDate" || attr.javaType === "LocalDateTime") {
    return (
      <DatePicker
        value={(value as string) ?? ""}
        onChange={onChange}
        includeTime={attr.javaType === "LocalDateTime"}
      />
    );
  }

  const numeric = isNumeric(attr.javaType);
  return (
    <Input
      type={numeric ? "number" : "text"}
      aria-invalid={invalid}
      className={invalidCls}
      placeholder={placeholder || undefined}
      step={numeric && attr.scale > 0 ? Math.pow(10, -attr.scale).toString() : undefined}
      min={numeric && attr.min != null ? attr.min : undefined}
      max={numeric && attr.max != null ? attr.max : undefined}
      maxLength={attr.length > 0 && attr.length <= MAX_VARCHAR && !numeric ? attr.length : undefined}
      value={(value as string) ?? ""}
      onChange={(e) => onChange(numeric ? (e.target.value === "" ? "" : Number(e.target.value)) : e.target.value)}
    />
  );
}
