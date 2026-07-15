import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import { toast } from "sonner";
import { Check, CircleCheck, Plus, RefreshCw, Trash2, X } from "lucide-react";
import type { AttributeMeta, EntityRecord, RelatedListMeta, SystemColumnMeta, TabularSectionMeta, UiEvent } from "@/lib/types";
import { api, ApiError } from "@/lib/api";
import { cn, enumPillStyle } from "@/lib/utils";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { HintIcon } from "@/components/ui/hint-icon";
import { Checkbox } from "@/components/ui/checkbox";
import { Switch } from "@/components/ui/switch";
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
import { MapEditor } from "@/components/map-editor";
import { ImagePicker, GalleryPicker } from "@/components/image-picker";
import { FilePicker } from "@/components/file-picker";
import { RelatedListPanel } from "@/components/related-list-panel";
import { Textarea } from "@/components/ui/textarea";
import { useMessages } from "@/providers/messages-provider";
import type { Translate } from "@/lib/messages";
import { cancelQuickCreate, consumeQuickCreate } from "@/lib/quick-create";
import { clearFormDirty, isFormDirty, markFormDirty } from "@/lib/dirty-forms";
import { ActionsCluster, type ActionItem } from "@/lib/actions-menu-bridge";

// Matches the DivKit action pills (Edit/Delete/New): a compact dark pill, icon + label,
// rounded-control, text-sm/medium, with the same vertical/horizontal rhythm.
const actionBtn =
  "inline-flex items-center gap-1.5 rounded-control bg-secondary px-3.5 py-2 text-sm font-medium transition-colors hover:bg-accent disabled:opacity-50";

// The portable form descriptor the server emits as the onno-form custom component.
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
  /**
   * The combined record surface rendered for a viewer without write access (or with
   * onno.ui.read-only on): every control disables and the Save/Post footer disappears.
   */
  readOnly?: boolean;
  /**
   * Record-level header actions (unpost / duplicate / delete + custom DETAIL actions) — the
   * same items the old detail header's onno-actions-menu cluster carried.
   */
  actions?: ActionItem[];
  meta: {
    name: string;
    autoNumber?: boolean;
    /** Documents that implement Postable can be posted from the form (1C-style). */
    postable?: boolean;
    attributes: AttributeMeta[];
    /** Built-in system columns (code/description, number/date/posted) with resolved, localizable labels. */
    systemColumns?: SystemColumnMeta[];
    tabularSections?: TabularSectionMeta[];
    /** Inline related-list (junction) panels — on catalogs and documents alike. */
    relatedLists?: RelatedListMeta[];
    /** Per-action placement overrides (f.action("post").inMenu()/hidden()) — "post" hides the Post button. */
    actions?: Record<string, string>;
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

// Seed the top-level field state from a loaded record. Secret fields are write-only — never seeded
// (see the form's save path). Records arrive keyed by column name; the form keys by field name.
// Module-scope + record-parameterised so a live SSE refetch can re-seed from a fresh record with the
// exact same rules as the initial mount.
function seedDataFrom(record: EntityRecord | null, fields: Field[]): EntityRecord {
  const seed: EntityRecord = {};
  if (!record) return seed;
  for (const f of fields) {
    if (f.kind === "attr" && f.attr.secret) continue;
    const col = f.kind === "system" ? f.column : f.attr.columnName;
    if (record[col] != null) seed[f.key] = record[col];
  }
  return seed;
}

// Seed the tabular-section rows from a loaded record (same column→field asymmetry as seedDataFrom).
function seedRowsFrom(
  record: EntityRecord | null,
  sections: TabularSectionMeta[]
): Record<string, EntityRecord[]> {
  const seed: Record<string, EntityRecord[]> = {};
  for (const ts of sections) {
    const raw = record?.[ts.name];
    seed[ts.name] = Array.isArray(raw)
      ? (raw as EntityRecord[]).map((r) => {
          const row: EntityRecord = {};
          for (const attr of ts.attributes) {
            if (attr.secret) continue; // write-only — see seedDataFrom
            if (r[attr.columnName] != null) row[attr.fieldName] = r[attr.columnName];
          }
          return row;
        })
      : [];
  }
  return seed;
}

// Mirror of the server's snake-casing so an SSE event's entity name matches the form's route name.
function toSnake(name: string): string {
  return name.replace(/ /g, "").replace(/([a-z0-9])([A-Z])/g, "$1_$2").toLowerCase();
}

// True when a live SSE event is an in-place change to *this* open record (same kind, name, and id).
// Only updated/posted/unposted/changed count — create/delete/register/presence events don't apply to
// an already-open record's fields. (Mirrors entity-list-widget's eventMatches, narrowed to one id.)
function recordEventMatches(event: UiEvent, kind: string, name: string, id: string): boolean {
  if (!event || event.entityName === "*" || event.id !== id) return false;
  if (!["updated", "posted", "unposted", "changed"].includes(event.type)) return false;
  const singular = kind === "catalogs" ? "catalog" : "document";
  return event.entityType === singular && toSnake(event.entityName ?? "") === name;
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
function validateField(attr: AttributeMeta, value: unknown, t: Translate): string | null {
  const field = attr.displayName;
  const empty =
    value == null || value === "" || (typeof value === "string" && value.trim() === "");
  if (attr.required && empty) return t("validation.required", { field });
  if (empty) return null;

  if (typeof value === "string") {
    if (attr.length > 0 && attr.length <= MAX_VARCHAR && value.length > attr.length) {
      return t("validation.maxLength", { field, n: attr.length });
    }
    if (attr.minLength && value.length < attr.minLength) {
      return t("validation.minLength", { field, n: attr.minLength });
    }
    if (attr.pattern) {
      // Anchor to a full match, matching the server's Pattern.matches semantics.
      let ok = true;
      try {
        ok = new RegExp(`^(?:${attr.pattern})$`).test(value);
      } catch {
        ok = true; // a malformed pattern shouldn't block the user; the server is authoritative
      }
      if (!ok) return t("validation.pattern", { field });
    }
    if (attr.email && !EMAIL_RE.test(value)) {
      return t("validation.email", { field });
    }
  }

  if (isNumeric(attr.javaType)) {
    const n = typeof value === "number" ? value : Number(value);
    if (!Number.isNaN(n)) {
      if (attr.min != null && n < attr.min) return t("validation.min", { field, n: fmtNum(attr.min) });
      if (attr.max != null && n > attr.max) return t("validation.max", { field, n: fmtNum(attr.max) });
    }
  }
  return null;
}

/**
 * Substitute a refFilter template's ${field} placeholders with the form's current values, producing
 * the predicate the picker's typeahead sends as ?filter=. A placeholder whose field is still empty
 * disables the filter entirely (unfiltered picker) — a half-cascade like "supplier = " would match
 * nothing and read as a broken picker. A template with no placeholders passes through (static
 * narrowing, e.g. "active = true").
 */
function resolveRefFilter(template: string | undefined, values: EntityRecord): string | undefined {
  if (!template) return undefined;
  let incomplete = false;
  const resolved = template.replace(/\$\{([A-Za-z_][A-Za-z0-9_]*)\}/g, (_, field: string) => {
    const v = values[field];
    if (v == null || v === "") {
      incomplete = true;
      return "";
    }
    return String(v);
  });
  return incomplete ? undefined : resolved;
}

// True when this attribute's refFilter cascades on `field` (its template references ${field}).
function cascadesOn(attr: AttributeMeta, field: string): boolean {
  return !!attr.refFilter && attr.refFilter.includes("${" + field + "}");
}

// Fire an onno:// action / pane-close through the host (divkit-view) — same routing the
// DivKit surfaces use, so a form opened in an island behaves like any other navigation.
function dispatchAction(url: string) {
  window.dispatchEvent(new CustomEvent("onno:action", { detail: url }));
}
function dispatchClose(path: string) {
  window.dispatchEvent(new CustomEvent("onno:closepath", { detail: path }));
}

export function EntityFormWidget({ form }: { form: FormDescriptor }) {
  const { kind, name, id, meta, initial } = form;
  const t = useMessages();
  // A duplicate carries the source id (for pane routing/close) but still creates a new record.
  const isDuplicate = form.duplicate === true;
  const isEdit = id != null && !isDuplicate;
  const readOnly = form.readOnly === true;
  const recordPath = isEdit && id ? `/${kind}/${name}/${id}` : null;
  const formPath = `/${kind}/${name}/${isDuplicate ? `${id}/duplicate` : isEdit ? `${id}/edit` : "new"}`;

  // Build the ordered field list: catalogs lead with code (unless auto-numbered) +
  // description; both then list the visible-in-form attributes by their order hint.
  const fields = useMemo<Field[]>(() => {
    const out: Field[] = [];
    // System-column labels are localizable via .field(name).label("…"); the server ships the
    // resolved displayName in meta.systemColumns, so read it instead of hardcoding English (#154).
    const sysLabel = (fieldName: string, fallback: string) =>
      meta.systemColumns?.find((c) => c.fieldName === fieldName)?.displayName || fallback;
    if (kind === "catalogs") {
      if (!meta.autoNumber) {
        out.push({ kind: "system", key: "code", label: sysLabel("code", "Code"), column: "_code" });
      }
      out.push({
        kind: "system",
        key: "description",
        label: sysLabel("description", "Description"),
        column: "_description",
      });
    }
    const attrs = meta.attributes
      .filter((a) => a.visibleInForm !== false)
      .sort((a, b) => (a.order ?? 0) - (b.order ?? 0));
    for (const attr of attrs) {
      out.push({ kind: "attr", key: attr.fieldName, label: attr.displayName, attr });
    }
    return out;
  }, [kind, meta]);

  // Fields bucketed by their .group(...) hint, in first-appearance order. Ungrouped fields
  // (and the system code/description) form the leading unlabeled card; each named group gets
  // its own card with a heading — so a long form reads as sections, not one endless column.
  const fieldGroups = useMemo<{ title: string; fields: Field[] }[]>(() => {
    const order: string[] = [];
    const byGroup = new Map<string, Field[]>();
    for (const f of fields) {
      const g = f.kind === "attr" ? (f.attr.group ?? "").trim() : "";
      if (!byGroup.has(g)) {
        byGroup.set(g, []);
        order.push(g);
      }
      byGroup.get(g)!.push(f);
    }
    return order.map((g) => ({ title: g, fields: byGroup.get(g)! }));
  }, [fields]);

  // Tabular sections (document child collections). The metadata already ships them and the
  // REST layer round-trips rows; this is the form's editable grid for each one.
  const sections = useMemo<TabularSectionMeta[]>(() => meta.tabularSections ?? [], [meta]);

  // Related-list panels (catalogs and documents alike). They read a junction live, scoped to this
  // record — so they need its id and only render for a saved record (not create/duplicate). A
  // catalog junction is editable here; a register junction is read-only (see RelatedListPanel).
  const relatedLists = useMemo<RelatedListMeta[]>(() => meta.relatedLists ?? [], [meta]);

  // 1C-style posting: a postable document offers Write (save, no posting) alongside
  // Post / Re-post (save then post). Already-posted documents re-post. Catalogs and
  // non-postable documents keep the single Save button.
  // The record the form is currently rendering. Starts from the server-provided `initial`; a live
  // SSE change to this record (another user/tab/widget) re-seeds it in place (see the effect below),
  // so the open form reflects external writes instead of going stale until reopened (#244).
  const [record, setRecord] = useState<EntityRecord | null>(initial);

  const postable = kind === "documents" && meta.postable === true;
  const wasPosted = Boolean(record?._posted);

  const [data, setData] = useState<EntityRecord>(() => seedDataFrom(initial, fields));

  // Rows per section, keyed by attribute fieldName. Loaded rows arrive keyed by column name
  // (with resolved *_display labels); seedRowsFrom handles the same column→field asymmetry the
  // top-level fields do. All attributes are seeded (not just the visible ones) so hidden columns
  // survive the delete-and-reinsert.
  const [rowsBySection, setRowsBySection] = useState<Record<string, EntityRecord[]>>(() =>
    seedRowsFrom(initial, sections)
  );

  const [saving, setSaving] = useState(false);
  // Set when the open record changed elsewhere while the user has unsaved edits: we don't clobber
  // their input, we surface a non-destructive "record changed — reload?" banner instead (#244).
  const [staleExternal, setStaleExternal] = useState(false);
  // Inline validation messages, keyed by field key (attr fieldName / system "code"/"description").
  const [errors, setErrors] = useState<Record<string, string>>({});
  // Live (as-you-type) server validation: the dry-run's per-field messages and form-level messages.
  // Kept apart from `errors` (the save attempt's verdict) so a background check never wipes or
  // fights the authoritative one; display merges them, save-set errors first.
  const [serverErrors, setServerErrors] = useState<Record<string, string>>({});
  const [serverFormErrors, setServerFormErrors] = useState<string[]>([]);
  // Only fields the user has actually visited show a live server error — the dry-run also reports
  // "required" on every field the user simply hasn't reached yet, and painting those red while the
  // form is being filled top-to-bottom would be noise.
  const [touched, setTouched] = useState<Set<string>>(() => new Set());
  // Monotonic guard so a slow validate response can't overwrite a newer one's verdict.
  const validateSeq = useRef(0);

  const set = (key: string, value: unknown) => {
    markFormDirty(formPath);
    // A change to a field that other pickers cascade on (${key} in their refFilter) invalidates
    // whatever those pickers hold — clear them so the user re-picks from the narrowed options.
    const dependentFields = meta.attributes
      .filter((a) => a.fieldName !== key && cascadesOn(a, key))
      .map((a) => a.fieldName);
    setData((prev) => {
      const next = { ...prev, [key]: value };
      for (const f of dependentFields) delete next[f];
      return next;
    });
    const dependentCells = sections
      .map((ts) => ({
        name: ts.name,
        cols: ts.attributes.filter((a) => cascadesOn(a, key)).map((a) => a.fieldName),
      }))
      .filter((s) => s.cols.length > 0);
    if (dependentCells.length > 0) {
      setRowsBySection((prev) => {
        const next = { ...prev };
        for (const { name: section, cols } of dependentCells) {
          next[section] = (next[section] ?? []).map((row) => {
            const r = { ...row };
            for (const c of cols) delete r[c];
            return r;
          });
        }
        return next;
      });
    }
    setTouched((prev) => (prev.has(key) ? prev : new Set(prev).add(key)));
    // Clear a field's error as soon as the user edits it; it re-checks on the next save (and the
    // live dry-run repaints it a debounce later if the value is still bad).
    setErrors((prev) => {
      if (!prev[key]) return prev;
      const next = { ...prev };
      delete next[key];
      return next;
    });
    setServerErrors((prev) => {
      if (!prev[key]) return prev;
      const next = { ...prev };
      delete next[key];
      return next;
    });
  };

  // The dirty flag's lifetime is this instance's: save/cancel clear it explicitly, and unmount
  // clears it too — a remounted island starts from the stored record again, so nothing is at risk.
  useEffect(() => () => clearFormDirty(formPath), [formPath]);

  // Re-seed the whole form from a freshly loaded record — the live-refresh equivalent of the initial
  // mount seed. Clears any pending errors, the stale banner, and the dirty flag (the on-screen values
  // now equal the stored record). Used both for a silent in-place refresh and the banner's Reload.
  const applyRecord = useCallback(
    (fresh: EntityRecord) => {
      setRecord(fresh);
      setData(seedDataFrom(fresh, fields));
      setRowsBySection(seedRowsFrom(fresh, sections));
      setErrors({});
      setServerErrors({});
      setServerFormErrors([]);
      setTouched(new Set());
      setStaleExternal(false);
      clearFormDirty(formPath);
    },
    [fields, sections, formPath]
  );

  // Fetch the current record and re-seed. Read-only failures are swallowed — the form keeps showing
  // what it had rather than blanking on a transient error.
  const refetch = useCallback(async () => {
    if (!isEdit || !id) return;
    try {
      const fresh =
        kind === "documents" ? await api.getDocument(name, id) : await api.getCatalogItem(name, id);
      applyRecord(fresh);
    } catch {
      /* leave the current values in place */
    }
  }, [isEdit, id, kind, name, applyRecord]);

  // Live refresh (#244): divkit-view fans every SSE row change out as an "onno:dataevent" window
  // event (one shared stream — see entity-list-widget). When *this* open record changes elsewhere,
  // reflect it: with no unsaved edits, refetch and update the fields in place; with unsaved edits,
  // don't clobber — raise a non-destructive banner offering a one-click reload. Create/duplicate
  // forms have no id to reconcile, so they never subscribe.
  useEffect(() => {
    if (!isEdit || !id) return;
    const onData = (e: Event) => {
      const event = (e as CustomEvent).detail as UiEvent;
      if (!recordEventMatches(event, kind, name, id)) return;
      if (isFormDirty(formPath)) setStaleExternal(true);
      else void refetch();
    };
    window.addEventListener("onno:dataevent", onData);
    return () => window.removeEventListener("onno:dataevent", onData);
  }, [isEdit, id, kind, name, formPath, refetch]);

  // The request body a save (or a dry-run validate) sends: the field state plus each tabular
  // section as rows keyed by fieldName (what insertTabularSections reads server-side). Rows where
  // every attribute is blank are dropped.
  const buildPayload = useCallback((): EntityRecord => {
    const payload = { ...data };
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
    return payload;
  }, [data, sections, rowsBySection]);

  // Live server validation: after a pause in editing, dry-run the full write lifecycle server-side
  // (constraints + hooks + Validated business rules — e.g. a Java conflict check on a time slot)
  // and paint the verdict inline, without waiting for Save. Only fires once the user has actually
  // edited something; a stale (out-of-order) response is dropped via validateSeq. Errors are
  // swallowed — this is advisory; the save path re-checks authoritatively.
  useEffect(() => {
    if (readOnly || !isFormDirty(formPath)) return;
    const timer = setTimeout(() => {
      const seq = ++validateSeq.current;
      api
        .validateRecord(kind, name, isEdit ? id : null, buildPayload())
        .then((report) => {
          if (seq !== validateSeq.current) return;
          const mapped: Record<string, string> = {};
          for (const [field, messages] of Object.entries(report.fieldErrors ?? {})) {
            mapped[field] = Array.isArray(messages) ? messages[0] : String(messages);
          }
          setServerErrors(mapped);
          setServerFormErrors(report.formErrors ?? []);
        })
        .catch(() => {
          /* background check; Save re-validates for real */
        });
    }, 500);
    return () => clearTimeout(timer);
  }, [data, rowsBySection, readOnly, formPath, kind, name, isEdit, id, buildPayload]);

  // Validate every attribute field against its constraints; returns key -> message.
  const validateAll = (): Record<string, string> => {
    const errs: Record<string, string> = {};
    for (const f of fields) {
      if (f.kind !== "attr") continue;
      const msg = validateField(f.attr, data[f.key], t);
      if (msg) errs[f.key] = msg;
    }
    return errs;
  };

  const addRow = (section: string) => {
    markFormDirty(formPath);
    setRowsBySection((prev) => ({ ...prev, [section]: [...(prev[section] ?? []), {}] }));
  };
  const removeRow = (section: string, idx: number) => {
    markFormDirty(formPath);
    setRowsBySection((prev) => ({
      ...prev,
      [section]: (prev[section] ?? []).filter((_, i) => i !== idx),
    }));
  };
  const setCell = (section: string, idx: number, key: string, value: unknown) => {
    markFormDirty(formPath);
    // Same-row cascade: a cell other cells' refFilters reference clears them in this row only.
    const dependentCols = (sections.find((ts) => ts.name === section)?.attributes ?? [])
      .filter((a) => a.fieldName !== key && cascadesOn(a, key))
      .map((a) => a.fieldName);
    setRowsBySection((prev) => ({
      ...prev,
      [section]: (prev[section] ?? []).map((row, i) => {
        if (i !== idx) return row;
        const r = { ...row, [key]: value };
        for (const c of dependentCols) delete r[c];
        return r;
      }),
    }));
  };

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
      const payload = buildPayload();
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
      clearFormDirty(formPath); // saved — nothing left for the discard guard to protect
      if (isEdit) {
        // Editing is a separate stage: after saving, return to the read-only detail surface.
        toast.success(t("toast.saved"));
        if (recordPath) dispatchAction(`onno://${kind}/${name}/${id}`);
        dispatchClose(formPath);
        return;
      }
      // A create that a ref picker is waiting on ("+ New" quick-create) hands the id back to
      // the picker and closes quietly — the user stays on the form they were filling in.
      // Otherwise open the saved record. Either way close the form pane (lists refresh over SSE).
      const pickedUp = consumeQuickCreate(kind, name, savedId);
      if (!pickedUp) dispatchAction(`onno://${kind}/${name}/${savedId}`);
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
        toast.error(t("form.saveError", { error: e instanceof Error ? e.message : String(e) }));
      }
      setSaving(false);
    }
  };

  const cancel = () => {
    // Cancel is an explicit discard — clear the dirty flag first so nothing asks "discard?".
    clearFormDirty(formPath);
    if (isEdit) {
      if (recordPath) dispatchAction(`onno://${kind}/${name}/${id}`);
      dispatchClose(formPath);
      return;
    }
    // Cancelling a create also cancels any ref-picker quick-create waiting on it.
    cancelQuickCreate(kind, name);
    dispatchClose(formPath);
  };

  // Editing context under the title: the record's code/number + description, so the user
  // always knows *which* supplier/order they're deep inside of. A posted document shows its
  // status pill — re-posting an already-posted document is a different mental act than posting.
  const subtitleParts = isEdit
    ? [record?._code, record?._number, record?._description].filter(
        (v): v is string =>
          typeof v === "string" &&
          v.trim() !== "" &&
          // The server-built title often already carries the number/name ("Edit Orders SO-42");
          // only surface identity the title doesn't show.
          !form.title.includes(v)
      )
    : [];

  // A field spans the full row unless hinted narrower (.width("half") / "1/2"), letting short
  // fields (dates, amounts, refs) sit side by side on wide screens.
  const isHalf = (f: Field) =>
    f.kind === "attr" && /^(half|1\/2|50%?)$/i.test((f.attr.widthHint ?? "").trim());

  return (
    <div className="mx-auto w-full max-w-2xl">
      <div className="mb-5 flex items-start justify-between gap-3">
        <div className="min-w-0">
          <div className="flex items-center gap-2.5">
            <h1 className="truncate text-xl font-semibold text-foreground">{form.title}</h1>
            {postable && wasPosted ? (
              <span className="inline-flex items-center gap-1 rounded-full bg-emerald-600/15 px-2 py-0.5 text-xs font-medium text-emerald-700 dark:text-emerald-400">
                <CircleCheck className="size-3" aria-hidden="true" />
                {t("status.posted")}
              </span>
            ) : null}
          </div>
          {subtitleParts.length > 0 ? (
            <p className="mt-0.5 truncate text-sm text-muted-foreground">{subtitleParts.join(" · ")}</p>
          ) : null}
        </div>
        {/* Record-level actions (unpost / duplicate / delete / custom DETAIL actions) — the same
            cluster the old read-only detail header rendered, now pinned beside the form title. */}
        {form.actions?.length ? <ActionsCluster items={form.actions} /> : null}
      </div>
      {/* The record changed elsewhere while the user has unsaved edits — offer a non-destructive
          reload rather than silently overwriting their input (#244). Reload re-seeds from the
          stored record, discarding the in-progress edits the user chose to abandon. */}
      {staleExternal ? (
        <div className="mb-4 flex items-center justify-between gap-3 rounded-card border border-amber-500/30 bg-amber-500/10 px-4 py-2.5 text-sm">
          <span className="flex min-w-0 items-center gap-2 text-amber-800 dark:text-amber-300">
            <RefreshCw className="size-4 shrink-0" aria-hidden="true" />
            <span className="truncate">{t("form.staleChanged")}</span>
          </span>
          <button
            type="button"
            className="inline-flex shrink-0 items-center gap-1.5 rounded-control bg-amber-600 px-3 py-1.5 text-sm font-medium text-white transition-colors hover:bg-amber-600/90"
            onClick={() => void refetch()}
          >
            {t("form.reload")}
          </button>
        </div>
      ) : null}
      <fieldset disabled={readOnly} className="contents">
      {fieldGroups.map((g, gi) => (
        <div
          key={g.title || "__default"}
          className={cn("rounded-card border border-border bg-card p-5", gi > 0 && "mt-4")}
        >
          {g.title ? (
            <h2 className="mb-4 text-sm font-semibold text-foreground">{g.title}</h2>
          ) : null}
          <div className="grid grid-cols-1 gap-4 sm:grid-cols-2">
            {g.fields.map((f) => (
              <div key={f.key} className={cn(!isHalf(f) && "sm:col-span-2")}>
                <FormFieldRow
                  field={f}
                  value={data[f.key]}
                  // The save attempt's verdict wins; behind it, the live dry-run's message — but
                  // only on fields the user has visited (see the `touched` note above).
                  error={errors[f.key] ?? (touched.has(f.key) ? serverErrors[f.key] : undefined)}
                  filterValues={data}
                  onChange={(v) => set(f.key, v)}
                />
              </div>
            ))}
          </div>
        </div>
      ))}
      {sections.map((ts) => (
        <TabularSectionEditor
          key={ts.name}
          section={ts}
          rows={rowsBySection[ts.name] ?? []}
          readOnly={readOnly}
          headerValues={data}
          onAdd={() => addRow(ts.name)}
          onRemove={(idx) => removeRow(ts.name, idx)}
          onCell={(idx, key, value) => setCell(ts.name, idx, key, value)}
        />
      ))}
      </fieldset>
      {/* Related-list panels read/write live join-catalog rows, so they're keyed to the saved
          record's id rather than to form state. On a create/duplicate form (no real id yet) the
          panel shows a "save first" hint. */}
      {relatedLists.map((rl) => (
        <RelatedListPanel
          key={rl.name}
          parentKind={kind}
          parentName={name}
          parentId={isEdit ? id : null}
          readOnly={readOnly}
          meta={rl}
        />
      ))}
      {/* Cross-field rule failures from the live dry-run (e.g. "slot already booked") have no
          single input to attach to — surface them as a quiet form-level notice above the footer. */}
      {!readOnly && serverFormErrors.length > 0 ? (
        <div className="mt-4 rounded-card border border-destructive/30 bg-destructive/10 px-4 py-2.5 text-sm text-destructive">
          {serverFormErrors.map((msg, i) => (
            <p key={i}>{msg}</p>
          ))}
        </div>
      ) : null}
      {readOnly ? null : (
        <div className="mt-5 flex justify-end gap-2">
          <button
            type="button"
            className={cn(actionBtn, "text-muted-foreground hover:text-foreground")}
            onClick={cancel}
            disabled={saving}
          >
            <X className="size-4" aria-hidden="true" />
            {t("action.cancel")}
          </button>
          <button
            type="button"
            // The form's primary affirmative action carries the brand (falls back to the neutral
            // near-black --primary when unbranded). Cancel/secondary actions stay quiet.
            className={cn(actionBtn, "bg-primary text-primary-foreground hover:bg-primary/90 hover:text-primary-foreground")}
            onClick={() => save(false)}
            disabled={saving}
          >
            <Check className="size-4" aria-hidden="true" />
            {saving ? t("action.saving") : postable ? t("action.save") : form.submitLabel}
          </button>
          {postable && meta.actions?.post !== "hidden" ? (
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
              {wasPosted ? t("action.repost") : t("action.post")}
            </button>
          ) : null}
        </div>
      )}
    </div>
  );
}

// An editable grid for one tabular section: add/remove rows, with each cell rendered by the
// same AttrControl the top-level fields use (so Ref pickers, enum selects, dates and typed
// inputs all behave identically). Only visible-in-form attributes get a column.
function TabularSectionEditor({
  section,
  rows,
  readOnly,
  headerValues,
  onAdd,
  onRemove,
  onCell,
}: {
  section: TabularSectionMeta;
  rows: EntityRecord[];
  /** Viewer without write access: cells disable via the enclosing fieldset; hide add/remove too. */
  readOnly?: boolean;
  /** The form's top-level values, so a cell's refFilter can cascade on a header field. */
  headerValues?: EntityRecord;
  onAdd: () => void;
  onRemove: (idx: number) => void;
  onCell: (idx: number, key: string, value: unknown) => void;
}) {
  const t = useMessages();
  const columns = useMemo<AttributeMeta[]>(
    () =>
      section.attributes
        .filter((a) => a.visibleInForm !== false)
        .sort((a, b) => (a.order ?? 0) - (b.order ?? 0)),
    [section]
  );
  const title = section.name.charAt(0).toUpperCase() + section.name.slice(1);

  // Booleans get a narrow, centered column; everything else flexes to share the width — so each
  // row reads as a single compact line (like a spreadsheet) instead of a stacked card.
  const isBoolCol = (a: AttributeMeta) => /^(boolean|Boolean)$/.test(a.javaType);
  const colClass = (a: AttributeMeta) =>
    isBoolCol(a) ? "shrink-0 basis-20" : "min-w-0 grow basis-44";

  // The add-row control sits under the last row — where the new row will appear — so adding
  // reads as "continue the grid downwards", not a jump back up to the header.
  const addRowBtn = readOnly ? null : (
    <button
      type="button"
      onClick={onAdd}
      className="mt-1 flex w-full items-center gap-1.5 rounded-control border border-dashed border-border px-2 py-1.5 text-sm font-medium text-muted-foreground transition-colors hover:bg-accent hover:text-foreground"
    >
      <Plus className="size-4" aria-hidden="true" />
      {t("action.addRow")}
    </button>
  );

  return (
    <div className="mt-4 rounded-card border border-border bg-card p-4 sm:p-5">
      <div className="mb-3 flex items-center justify-between">
        <h2 className="text-sm font-semibold text-foreground">{title}</h2>
      </div>
      {rows.length === 0 ? (
        <>
          <p className="text-sm text-muted-foreground">{t("empty.noRows")}</p>
          {addRowBtn}
        </>
      ) : (
        <div className="overflow-x-auto">
          <div className="min-w-[28rem]">
            {/* Column headers, shown once — aligned with the cells below via matching flex rules. */}
            <div className="flex items-end gap-3 px-2 pb-1.5">
              {columns.map((attr) => (
                <div
                  key={attr.fieldName}
                  className={cn(colClass(attr), "text-xs font-medium text-muted-foreground", isBoolCol(attr) && "text-center")}
                >
                  {attr.displayName}
                  {attr.required ? <span className="ml-0.5 text-destructive">*</span> : null}
                </div>
              ))}
              <span className="w-8 shrink-0" aria-hidden="true" />
            </div>
            {/* One compact line per row; the remove control fades in on hover. */}
            <div className="space-y-1">
              {rows.map((row, idx) => (
                <div
                  key={idx}
                  className="group flex items-center gap-3 rounded-control px-2 py-1 transition-colors hover:bg-muted/40"
                >
                  {columns.map((attr) => (
                    <div
                      key={attr.fieldName}
                      className={cn(colClass(attr), isBoolCol(attr) && "flex justify-center")}
                    >
                      <AttrControl
                        attr={attr}
                        value={row[attr.fieldName]}
                        compact
                        // Row values win over header values, so a cell can cascade on either.
                        filterValues={{ ...headerValues, ...row }}
                        onChange={(v) => onCell(idx, attr.fieldName, v)}
                      />
                    </div>
                  ))}
                  {readOnly ? (
                    <span className="w-8 shrink-0" aria-hidden="true" />
                  ) : (
                    <button
                      type="button"
                      onClick={() => onRemove(idx)}
                      aria-label={`Remove row ${idx + 1}`}
                      title="Remove row"
                      className="grid size-8 shrink-0 place-items-center rounded-control text-muted-foreground opacity-50 transition-colors hover:bg-accent hover:text-destructive group-hover:opacity-100"
                    >
                      <Trash2 className="size-4" aria-hidden="true" />
                    </button>
                  )}
                </div>
              ))}
            </div>
            {addRowBtn}
          </div>
        </div>
      )}
    </div>
  );
}

function FormFieldRow({
  field,
  value,
  error,
  filterValues,
  onChange,
}: {
  field: Field;
  value: unknown;
  error?: string;
  /** The form's current values, for resolving a ref field's cascading refFilter. */
  filterValues?: EntityRecord;
  onChange: (value: unknown) => void;
}) {
  const required = field.kind === "attr" && field.attr.required;
  const placeholder = field.kind === "attr" ? field.attr.placeholder : undefined;
  const hint = field.kind === "attr" ? field.attr.hint : undefined;
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
        filterValues={filterValues}
        onChange={onChange}
      />
    );

  // Booleans: a settings-style switch (label left, toggle right) when hinted with .widget("switch"),
  // otherwise an inline checkbox that owns its label.
  if (field.kind === "attr" && /^(boolean|Boolean)$/.test(field.attr.javaType)) {
    if (/^(switch|toggle)$/i.test(field.attr.widget ?? "")) {
      return (
        <div>
          <div className="flex items-center justify-between gap-4">
            <div className="flex items-center gap-1.5">
              <Label htmlFor={field.key}>{field.label}</Label>
              <HintIcon text={hint} size={13} />
            </div>
            <Switch id={field.key} checked={!!value} onCheckedChange={(v) => onChange(v === true)} />
          </div>
          {error ? <p className="mt-1 text-xs text-destructive">{error}</p> : null}
        </div>
      );
    }
    return (
      <div>
        <div className="flex items-center gap-2">
          <Checkbox
            id={field.key}
            checked={!!value}
            onCheckedChange={(v) => onChange(v === true)}
          />
          <Label htmlFor={field.key}>{field.label}</Label>
          <HintIcon text={hint} size={13} />
        </div>
        {error ? <p className="mt-1 text-xs text-destructive">{error}</p> : null}
      </div>
    );
  }

  return (
    <div className="grid gap-1.5">
      <div className="flex items-center gap-1.5">
        <Label htmlFor={field.key}>
          {field.label}
          {required ? <span className="ml-1 text-destructive">*</span> : null}
        </Label>
        <HintIcon text={hint} size={13} />
      </div>
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
  compact,
  filterValues,
}: {
  attr: AttributeMeta;
  value: unknown;
  onChange: (value: unknown) => void;
  invalid?: boolean;
  placeholder?: string;
  /** Grid-cell rendering (tabular sections): keep multi-line controls to a single-row height. */
  compact?: boolean;
  /** Current form/row values for resolving attr.refFilter's ${...} placeholders. */
  filterValues?: EntityRecord;
}) {
  const t = useMessages();
  const invalidCls = invalid ? "border-destructive focus-visible:ring-destructive" : undefined;
  if (/^(boolean|Boolean)$/.test(attr.javaType)) {
    const isSwitch = /^(switch|toggle)$/i.test(attr.widget ?? "");
    return (
      <div className="flex h-9 items-center">
        {isSwitch ? (
          <Switch checked={!!value} onCheckedChange={(v) => onChange(v === true)} />
        ) : (
          <Checkbox checked={!!value} onCheckedChange={(v) => onChange(v === true)} />
        )}
      </div>
    );
  }

  // A custom widget hint (.field(...).widget("map"|"geojson")) wins over the type-based control.
  // "map"/"geo" is a single point stored as a "lat,lng" string; "geojson" is the full geometry
  // editor (points/paths/areas) stored as a GeoJSON string. Both live on a String attribute.
  if (/^geojson$/i.test(attr.widget ?? "")) {
    return <MapEditor value={value as string | undefined} onChange={onChange} />;
  }
  if (/^(map|geo|geolocation)$/i.test(attr.widget ?? "")) {
    return <GeoPicker value={value as string | undefined} onChange={onChange} />;
  }

  // Media widgets stream the chosen file to POST /api/media and store only the returned reference
  // URL — so a plain String attribute holds it (no base64-sized TEXT). "avatar" is a small round
  // image variant; "images"/"gallery" stores several URLs newline-joined; "file" takes any type.
  if (/^(images|gallery|photos)$/i.test(attr.widget ?? "")) {
    return <GalleryPicker value={value as string | undefined} onChange={onChange} />;
  }
  if (/^(image|photo)$/i.test(attr.widget ?? "")) {
    return <ImagePicker value={value as string | undefined} onChange={onChange} />;
  }
  if (/^avatar$/i.test(attr.widget ?? "")) {
    return <ImagePicker variant="avatar" value={value as string | undefined} onChange={onChange} />;
  }
  if (/^(file|upload|attachment)$/i.test(attr.widget ?? "")) {
    return <FilePicker value={value as string | undefined} onChange={onChange} />;
  }

  if (attr.isRef && attr.refTarget) {
    return (
      <RefSelect
        targetName={attr.refTarget}
        refKind={attr.refKind}
        secondaryField={attr.refSecondary}
        filter={resolveRefFilter(attr.refFilter, filterValues ?? {})}
        value={value as string | undefined}
        onChange={onChange}
      />
    );
  }

  if (attr.isEnum && attr.enumValues) {
    return (
      <Select value={(value as string) ?? ""} onValueChange={onChange}>
        <SelectTrigger>
          <SelectValue placeholder={t("form.select", { name: attr.displayName })} />
        </SelectTrigger>
        <SelectContent>
          {attr.enumValues.map((ev) => {
            // A coloured value (from @EnumLabel(color = …)) gets a dot matching its list pill, shown
            // in the option and — since Radix mirrors the chosen item — in the closed trigger too.
            const pill = enumPillStyle(ev.color);
            return (
              <SelectItem key={ev.id} value={ev.id}>
                {pill ? (
                  <span className="flex items-center gap-2">
                    <span
                      className="size-2.5 shrink-0 rounded-full"
                      style={{ backgroundColor: pill.backgroundColor }}
                    />
                    {ev.label ?? ev.name}
                  </span>
                ) : (
                  (ev.label ?? ev.name)
                )}
              </SelectItem>
            );
          })}
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

  // Multi-line text: explicit .widget("textarea"), or any String long enough (length > 1000,
  // or unbounded) that a single-line input clearly wasn't meant — notes, addresses, comments.
  const longText =
    attr.javaType === "String" && !attr.widget && (attr.length <= 0 || attr.length > 1000);
  if (/^(textarea|multiline)$/i.test(attr.widget ?? "") || longText) {
    return (
      <Textarea
        rows={compact ? 1 : 4}
        aria-invalid={invalid}
        className={cn(invalidCls, compact && "min-h-9 resize-y")}
        placeholder={placeholder || undefined}
        maxLength={attr.length > 0 && attr.length <= MAX_VARCHAR ? attr.length : undefined}
        value={(value as string) ?? ""}
        onChange={(e) => onChange(e.target.value)}
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
