import { CommentThread } from "../collaboration/comments";
import { RecordHistory } from "../collaboration/record-history";
import { findEntity, useModel } from "../../lib/model-store";
import { closeRoute, openRoute } from "../../lib/navigation";
import { ExtensionSlot } from "../../lib/ui-extensions";
import type { ExtensionContext } from "../../lib/extension-types";
import { useCallback, useEffect, useMemo, useRef, useState, useId, type ReactNode, type ComponentType } from "react";
import { toast } from "../ui/toast";
import { Check, CircleCheck, Undo2, Plus, RefreshCw, Trash2, X, Copy, RotateCcw, List, CircleDot } from "lucide-react";
import type { AttributeMeta, EntityRecord, SystemColumnMeta, TabularSectionMeta } from "../../lib/types";
import { useEntityChanges } from "../../lib/query";
import type { WhereClause } from "../../lib/measure";
import { api, ApiError, type FormFeedback } from "../../lib/api";
import { applyForm, type EntityCustomization, type FieldCustomization, type RecordPanel, type RelationCustomization } from "../../lib/entity-customizations";
import {
  commandIcon,
  presentCommands,
  presentRecordActions,
  type CommandDescriptor,
  type CommandPresentation,
  type RecordActionDescriptor,
} from "../../lib/commands";
import { notifyError } from "../../lib/notify-error";
import { cn, enumPillStyle } from "../../lib/utils";
import { Button } from "../ui/button";
import { Input } from "../ui/input";
import { Label } from "../ui/label";
import { HintIcon } from "../ui/hint-icon";
import { Checkbox } from "../ui/checkbox";
import { Switch } from "../ui/switch";
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from "../ui/select";
import { RefSelect, type RefSelectProps } from "../fields/ref-select";
import { RestrictedRef, isRestrictedRef } from "../fields/restricted-ref";
import { DatePicker } from "../fields/date-picker";
import { DateTimePicker } from "../fields/date-time-picker";
import { TimePicker } from "../fields/time-picker";
import { InstantPicker } from "../fields/instant-picker";
import { MapEditor } from "../maps/map-editor";
import { ImagePicker, GalleryPicker } from "../fields/image-picker";
import { FilePicker } from "../fields/file-picker";
import { ColorPicker } from "../fields/color-picker";
import { Textarea } from "../ui/textarea";
import { useMessages } from "../../providers/messages-provider";
import type { Translate } from "../../lib/messages";
import { useQuickCreate } from "../../lib/quick-create";
import { useFormDirty } from "../../lib/dirty-forms";
import { RecordActions, type RecordAction } from "../actions/record-actions";
import { ErrorBoundary } from "../../lib/error-boundary";
import { useRecordRelations } from "./record-relations";

// The form's action pill: a compact dark pill, icon + label, rounded-control, text-sm/medium.
const actionBtn =
  "inline-flex items-center gap-1.5 rounded-control bg-secondary px-3.5 py-2 text-sm font-medium transition-colors hover:bg-accent disabled:opacity-50";

// Radix Select reserves the empty string for "no value", so an optional enum needs a stable
// non-empty item value that we normalize back to null before updating form state.
const NO_SELECTION_VALUE = "__onno_no_selection__";

// The portable form descriptor the server emits as the onno-form custom component.
export type FormDescriptor = {
  /** Components shown under the saved record's form (the app's record panels). */
  panels?: RecordPanel[];
  /** Many-to-many relations edited on the form (the entity's `relations` customization). */
  relations?: Record<string, RelationCustomization>;
  /** The header fields the default layout shows, in order (the entity's `form.fields` customization). */
  fieldOrder?: string[];
  kind: "documents" | "catalogs";
  name: string;
  id: string | null;
  /**
   * A "Duplicate" form: {@code id} identifies the source record (so the host closes the right
   * pane), but the form submits as a create into a brand-new record. {@code initial} carries the
   * source's attributes/line items minus its identity (see UiViewController#duplicateDraft).
   */
  duplicate?: boolean;
  /**
   * The combined record surface rendered for a viewer without write access (or with
   * onno.web.read-only on): every control disables and the Save/Post footer disappears.
   */
  readOnly?: boolean;
  /**
   * The record actions the viewer may perform now (unpost / duplicate / delete / open in list +
   * extension contributions), keyed and without presentation; the `commands` customization places
   * and icons them (see {@link presentRecordActions}).
   */
  actions?: RecordActionDescriptor[];
  /** A document: whether the viewer may post it (absent on catalogs). */
  canPost?: boolean;
  /**
   * The entity's record commands available on this record (each with the record's title and
   * enabled state), offered in the same header cluster.
   */
  commands?: CommandDescriptor[];
  /** How commands and built-ins are offered (the entity's `commands` customization). */
  commandPresentation?: Record<string, CommandPresentation>;
  /** A saved record surface: its values, header actions and commands can be re-read in place. */
  refreshable?: boolean;
  /**
   * The entity records its change history (`history = true`): the record page shows it. Absent,
   * the model's entity flag decides.
   */
  history?: boolean;
  meta: {
    name: string;
    /** The entity's display title (falls back to `name`). */
    title?: string;
    autoNumber?: boolean;
    /** Documents that implement Postable can be posted from the form (1C-style). */
    postable?: boolean;
    attributes: AttributeMeta[];
    /** Built-in system columns (code/description, number/date/posted) with resolved, localizable labels. */
    systemColumns?: SystemColumnMeta[];
    tabularSections?: TabularSectionMeta[];
    /** Application-authored dependency-aware, debounced advisory validators. */
    formValidations?: FormValidationMeta[];
  };
  initial: EntityRecord | null;
};

type FormValidationMeta = {
  key: string;
  dependencies: string[];
  debounceMillis: number;
};

function valueAtDependency(values: EntityRecord, path: string): unknown {
  const [head, ...tail] = path.split(".");
  const value = values[head];
  if (tail.length === 0) return value;
  if (Array.isArray(value)) {
    return value.map((row) =>
      row && typeof row === "object" ? valueAtDependency(row as EntityRecord, tail.join(".")) : null
    );
  }
  return value && typeof value === "object"
    ? valueAtDependency(value as EntityRecord, tail.join("."))
    : null;
}

// One editable field: either a catalog system field (code/description) or an attribute.
type Field =
  | { kind: "system"; key: string; label: string }
  | { kind: "attr"; key: string; label: string; attr: AttributeMeta };

function isNumeric(javaType: string): boolean {
  return ["BigDecimal", "Integer", "Long", "Double", "Float", "Short", "int", "long", "double"].includes(
    javaType
  );
}

/**
 * Canonical write representation for temporal values loaded from the read API. LocalDateTime is a
 * wall-clock value: strip a transport offset/Z without shifting the displayed local fields.
 */
function canonicalTemporalSeed(value: unknown, javaType: string): unknown {
  if (value == null || (javaType !== "LocalDate" && javaType !== "LocalDateTime")) return value;
  const normalized = String(value).trim().replace(" ", "T");
  if (javaType === "LocalDate") return normalized.slice(0, 10);
  const local = normalized.match(
    /^(\d{4}-\d{2}-\d{2}T\d{2}:\d{2}(?::\d{2}(?:\.\d{1,9})?)?)/
  );
  return local?.[1] ?? normalized;
}

// Seed the top-level field state from a loaded record. Secret fields are write-only — never seeded
// (see the form's save path). Logical entity responses already use the form's field names.
// Module-scope + record-parameterised so a live SSE refetch can re-seed from a fresh record with the
// exact same rules as the initial mount.
function seedDataFrom(record: EntityRecord | null, fields: Field[]): EntityRecord {
  const seed: EntityRecord = {};
  if (!record) return seed;
  for (const f of fields) {
    if (f.kind === "attr" && f.attr.secret) continue;
    if (record[f.key] != null) {
      const javaType = f.kind === "attr" ? f.attr.javaType : f.key === "date" ? "LocalDateTime" : "";
      seed[f.key] = canonicalTemporalSeed(record[f.key], javaType);
    }
  }
  return seed;
}

// Seed tabular-section rows from the same logical field vocabulary as the document header.
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
            if (r[attr.fieldName] != null) {
              row[attr.fieldName] = canonicalTemporalSeed(r[attr.fieldName], attr.javaType);
            }
          }
          return row;
        })
      : [];
  }
  return seed;
}

const EMAIL_RE = /^[^@\s]+@[^@\s]+\.[^@\s]+$/;
const HEX_COLOR_RE = /^#[0-9a-f]{6}$/i;
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

  if (attr.input === "color" && !HEX_COLOR_RE.test(String(value))) {
    return t("validation.hexColor", { field });
  }

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
 * A cascading picker's `where` from the form's current values: `{ supplier: <this form's supplier> }`
 * for `filterBy: { supplier: "supplier" }`. While a source field is still empty the picker stays
 * unfiltered — a half-cascade would match nothing and read as a broken picker.
 */
function cascadeWhere(filterBy: Record<string, string> | undefined, values: EntityRecord): WhereClause | undefined {
  if (!filterBy) return undefined;
  const where: WhereClause = {};
  for (const [target, source] of Object.entries(filterBy)) {
    const v = values[source];
    if (v == null || v === "") return undefined;
    where[target] = v;
  }
  return where;
}

/** Shared picker contract for built-in controls and SDK field bindings. */
function referenceProps(attr: AttributeMeta, value: unknown, onChange: (value: unknown) => void,
  values: EntityRecord = {}, optionContext?: RefSelectProps["optionContext"], excludedIds?: string[]): RefSelectProps {
  return {
    targetName: attr.refTarget!, refKind: attr.refKind, secondaryField: attr.refSecondary,
    where: cascadeWhere(attr.refFilterBy, values), optionContext, excludedIds,
    value: value as string | null | undefined, clearable: attr.refClearButton ?? !attr.required,
    openButton: attr.refOpenButton ?? true, fieldLabel: attr.displayName, onChange,
  };
}

// True when this attribute's picker cascades on `field` (one of its filterBy sources).
function cascadesOn(attr: AttributeMeta, field: string): boolean {
  return !!attr.refFilterBy && Object.values(attr.refFilterBy).includes(field);
}

export type RecordFieldBinding = {
  name: string;
  label: string;
  value: unknown;
  onChange: (value: unknown) => void;
  disabled: boolean;
  restricted: boolean;
  required: boolean;
  error?: string;
  feedback: FormFeedback[];
  attribute: AttributeMeta | null;
  section: TabularSectionMeta | null;
  reference: RefSelectProps | null;
  renderDefault: () => ReactNode;
};

export type RecordFormState = {
  id: string | null;
  kind: "catalogs" | "documents";
  name: string;
  title: string;
  meta: FormDescriptor["meta"];
  canPost: boolean;
  posted: boolean;
  dirty: boolean;
  stale: boolean;
  actions: RecordAction[];
  cancel: () => void;
  refresh: () => Promise<void>;
  reload: () => Promise<void>;
  bindField: (field: string, options?: FieldCustomization) => RecordFieldBinding;
  values: EntityRecord;
  errors: Record<string, string>;
  formErrors: string[];
  feedback: FormFeedback[];
  isNew: boolean;
  readOnly: boolean;
  saving: boolean;
  set: (field: string, value: unknown) => void;
  save: (post?: boolean) => Promise<void>;
  defaultLayout: ReactNode;
  renderPage: (children?: ReactNode, options?: RecordPageOptions) => ReactNode;
  renderFields: (fields?: string[], overrides?: Record<string, FieldCustomization>) => ReactNode;
  renderLines: (section: string, overrides?: Record<string, FieldCustomization>) => ReactNode;
};

type RecordPageOptions = {
  commands?: EntityCustomization["commands"];
  panels?: RecordPanel[];
  comments?: boolean;
  /** The record's change history under the form (default: when the entity records history). */
  history?: boolean;
};
export function RecordPage({ form, children, className, ...options }: { form: RecordFormState; children?: ReactNode; className?: string } & RecordPageOptions) {
  return <div className={cn("w-full p-4", className)}>{form.renderPage(children, options)}</div>;
}

/**
 * The record's header fields. `fields` picks which show, in order (default: the entity's
 * `form.fields`, else every visible field); `overrides` restyles fields on this page only, on top
 * of the entity's customization.
 */
export function RecordFields({ form, fields, overrides }: { form: RecordFormState; fields?: string[]; overrides?: Record<string, FieldCustomization> }) {
  return form.renderFields(fields, overrides);
}

/** One tabular section's editor; `overrides` restyles its columns (keyed by column) on this page only. */
export function RecordLines({ form, section, overrides }: { form: RecordFormState; section: string; overrides?: Record<string, FieldCustomization> }) {
  return form.renderLines(section, overrides);
}

/** The default host uses the same controller custom record hosts import from the SDK. */
export function RecordForm({ form, component: Component }: {
  form: FormDescriptor;
  component?: ComponentType<{ form: RecordFormState }>;
}) {
  const state = useRecordForm(form);
  return Component ? <Component form={state} /> : state.renderPage();
}

export function useRecordField(form: RecordFormState, field: string, options?: FieldCustomization): RecordFieldBinding {
  return form.bindField(field, options);
}

export function RecordField({ form, field, options, children }: {
  form: RecordFormState; field: string; options?: FieldCustomization;
  children?: (binding: RecordFieldBinding) => ReactNode;
}) {
  const binding = useRecordField(form, field, options);
  return children ? children(binding) : binding.renderDefault();
}

/** State, lifecycle and standard rendering primitives, independent of the chosen page component. */
export function useRecordForm(form: FormDescriptor): RecordFormState {
  // Render-local presentation: custom controls contribute picker options to this render's
  // event handlers. Nothing is registered globally or retained after the page unmounts.
  const presentation: Record<string, FieldCustomization> = {};
  const presentedMeta = () => applyForm(form, [{ fields: presentation }]).meta;
  const { kind, name, id, meta, initial, refreshable } = form;
  const model = useModel();
  const t = useMessages();
  // A duplicate carries the source id (for pane routing/close) but still creates a new record.
  const isDuplicate = form.duplicate === true;
  const isEdit = id != null && !isDuplicate;
  const [refreshedHeader, setRefreshedHeader] = useState<{
    actions: RecordActionDescriptor[];
    canPost?: boolean;
    commands: CommandDescriptor[];
    record: EntityRecord;
    readOnly: boolean;
  } | null>(null);
  const readOnly = refreshedHeader?.readOnly ?? (form.readOnly === true);
  const headerActions = useMemo(() => {
    const builtIns = presentRecordActions(refreshedHeader?.actions ?? form.actions, form.commandPresentation,
      (key) => t(`action.${key}`));
    if (!isEdit || !id) return builtIns;
    const commands = refreshedHeader?.commands ?? form.commands ?? [];
    return [...builtIns, ...commandItems(kind, name, id, commands, form.commandPresentation, refreshedHeader?.record ?? initial)];
  }, [refreshedHeader, form.actions, form.commands, form.commandPresentation, isEdit, kind, name, id, initial, t]);
  const formPath = `/${kind}/${name}/${isDuplicate ? `${id}/duplicate` : isEdit ? id : "new"}`;
  const dirty = useFormDirty(formPath);
  const quickCreate = useQuickCreate();
  // Many-to-many join rows edited on the form and saved with it (the `relations` customization).
  const relationState = useRecordRelations(form.relations, isEdit ? id : null, dirty.mark);

  // Build the ordered field list: catalogs lead with code (unless auto-numbered) +
  // description; both then list the visible-in-form attributes by their order hint.
  const fields = useMemo<Field[]>(() => {
    const out: Field[] = [];
    // System-column labels are localizable via .field(name).label("…"); the server ships the
    // resolved displayName in meta.systemColumns, so read it instead of hardcoding English (#154).
    const sysLabel = (fieldName: string, fallback: string) =>
      meta.systemColumns?.find((c) => c.fieldName === fieldName)?.displayName || fallback;
    // The entity's `fields` customization hides a system field too (`code: { hideInForm: true }`).
    const sysShown = (fieldName: string) =>
      meta.systemColumns?.find((c) => c.fieldName === fieldName)?.visibleInDetail !== false;
    if (kind === "catalogs") {
      if (!meta.autoNumber && sysShown("code")) {
        out.push({ kind: "system", key: "code", label: sysLabel("code", "Code") });
      }
      if (sysShown("description")) {
        out.push({
          kind: "system",
          key: "description",
          label: sysLabel("description", "Description"),
        });
      }
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

  const panels = form.panels ?? [];

  // 1C-style posting: a postable document offers Write (save, no posting) alongside
  // Post / Re-post (save then post). Already-posted documents re-post. Catalogs and
  // non-postable documents keep the single Save button.
  // The record the form is currently rendering. Starts from the server-provided `initial`; a live
  // SSE change to this record (another user/tab/widget) re-seeds it in place (see the effect below),
  // so the open form reflects external writes instead of going stale until reopened (#244).
  const [record, setRecord] = useState<EntityRecord | null>(initial);

  const postable = kind === "documents" && meta.postable === true;
  const wasPosted = Boolean(record?.posted);
  const title = formTitle(form, t, record);
  // The Post button: the viewer's posting right (#546), unless the app hides it (`commands.post`).
  const canPost = refreshedHeader?.canPost ?? form.canPost ?? true;
  const postPlacement = form.commandPresentation?.post?.placement;

  const [data, setData] = useState<EntityRecord>(() => seedDataFrom(initial, fields));

  // Rows per section are keyed by attribute fieldName. All attributes are seeded (not just the
  // visible ones) so hidden columns
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
  // Application-authored advisory feedback (#274), replaced per validator so correcting a
  // dependency clears obsolete messages without touching any form values.
  const [asyncFeedbackByKey, setAsyncFeedbackByKey] = useState<Record<string, FormFeedback[]>>({});
  // Only fields the user has actually visited show a live server error — the dry-run also reports
  // "required" on every field the user simply hasn't reached yet, and painting those red while the
  // form is being filled top-to-bottom would be noise.
  const [touched, setTouched] = useState<Set<string>>(() => new Set());
  // Monotonic guard so a slow validate response can't overwrite a newer one's verdict.
  const validateSeq = useRef(0);
  const asyncValidateSeq = useRef<Record<string, number>>({});

  const set = (key: string, value: unknown) => {
    dirty.mark();
    // A change to a field that other pickers cascade on (a source in their filterBy) invalidates
    // whatever those pickers hold — clear them so the user re-picks from the narrowed options.
    const dependentFields = presentedMeta().attributes
      .filter((a) => a.fieldName !== key && cascadesOn(a, key))
      .map((a) => a.fieldName);
    setData((prev) => {
      const next = { ...prev, [key]: value };
      for (const f of dependentFields) next[f] = null;
      return next;
    });
    const dependentCells = (presentedMeta().tabularSections ?? [])
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

  const refreshSeq = useRef(0);
  useEffect(() => () => {
    ++refreshSeq.current;
  }, [formPath]);

  // Re-seed the whole form from a freshly loaded record — the live-refresh equivalent of the initial
  // mount seed. Clears any pending errors, the stale banner, and the dirty flag (the on-screen values
  // now equal the stored record). Used both for a silent in-place refresh and the banner's Reload.
  const applyRecord = useCallback(
    (fresh: EntityRecord) => {
      ++refreshSeq.current;
      setRecord(fresh);
      setData(seedDataFrom(fresh, fields));
      setRowsBySection(seedRowsFrom(fresh, sections));
      setErrors({});
      setServerErrors({});
      setServerFormErrors([]);
      setAsyncFeedbackByKey({});
      setTouched(new Set());
      setStaleExternal(false);
      dirty.clear();
    },
    [fields, sections, dirty]
  );

  // Refresh the header and fields from the same saved row. A dirty form updates its actions
  // but keeps the draft until the user chooses Reload.
  const refetch = useCallback(async (preserveEdits = false) => {
    if (!isEdit || !id) return;
    if (preserveEdits && dirty.isDirty()) {
      setStaleExternal(true);
      if (!refreshable) return;
    }
    const seq = ++refreshSeq.current;
    try {
      const snapshot = refreshable ? await api.getRecordRefresh(kind, name, id) : null;
      const fresh = snapshot?.record ??
        (kind === "documents" ? await api.getDocument(name, id) : await api.getCatalogItem(name, id));
      if (seq !== refreshSeq.current) return;
      if (snapshot) {
        setRefreshedHeader({
          actions: snapshot.actions,
          canPost: snapshot.canPost,
          commands: snapshot.commands ?? [],
          record: snapshot.record,
          readOnly: snapshot.readOnly,
        });
      }
      // Typing can start after the request was sent. Background refreshes must not erase it.
      if (preserveEdits && dirty.isDirty()) setStaleExternal(true);
      else applyRecord(fresh);
    } catch {
      /* leave the current values in place */
    }
  }, [isEdit, id, kind, name, dirty, applyRecord, refreshable]);

  // Live refresh (#244): when *this* open record changes elsewhere, reflect it — with no unsaved
  // edits, refetch and update the fields in place; with unsaved edits, don't clobber: raise a
  // non-destructive banner offering a one-click reload. A change that names no records (a command's
  // refresh, a create) refreshes a clean form quietly and leaves a draft alone. Create/duplicate
  // forms have no id to reconcile.
  useEntityChanges(`${kind}/${name}`, (ids) => {
    if (!isEdit || !id) return;
    if (ids ? ids.includes(id) : !dirty.isDirty()) void refetch(true);
  });

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

  const formValidations = meta.formValidations ?? [];
  const asyncDependencyKey = JSON.stringify(
    formValidations.map((validation) => {
      const payload = buildPayload();
      return [
        validation.key,
        validation.dependencies.length
          ? validation.dependencies.map((path) => valueAtDependency(payload, path))
          : payload,
      ];
    })
  );

  // Each authored validator owns its debounce and stale-response sequence. A dependency change
  // clears that validator's previous messages immediately; unrelated values are excluded from the
  // fingerprint and therefore do not trigger a request.
  useEffect(() => {
    if (readOnly || formValidations.length === 0) return;
    const payload = buildPayload();
    const timers = formValidations.map((validation) => {
      const seq = (asyncValidateSeq.current[validation.key] ?? 0) + 1;
      asyncValidateSeq.current[validation.key] = seq;
      setAsyncFeedbackByKey((prev) =>
        prev[validation.key] ? { ...prev, [validation.key]: [] } : prev
      );
      return window.setTimeout(() => {
        api
          .runCheck(kind, name, validation.key, isEdit ? id : null, payload)
          .then((feedback) => {
            if (asyncValidateSeq.current[validation.key] !== seq) return;
            setAsyncFeedbackByKey((prev) => ({ ...prev, [validation.key]: feedback }));
          })
          .catch(() => {
            /* advisory endpoint; the authoritative save path remains unchanged */
          });
      }, Math.max(0, validation.debounceMillis ?? 250));
    });
    return () => timers.forEach(window.clearTimeout);
    // asyncDependencyKey intentionally represents only declared dependencies.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [asyncDependencyKey, readOnly, kind, name, isEdit, id]);

  const asyncFeedback = Object.values(asyncFeedbackByKey).flat();
  const feedbackFor = (field: string) => asyncFeedback.filter((item) => item.field === field);
  const asyncFormFeedback = asyncFeedback.filter((item) => !item.field);

  // Live server validation: after a pause in editing, dry-run the full write lifecycle server-side
  // (constraints + hooks + Validated business rules — e.g. a Java conflict check on a time slot)
  // and paint the verdict inline, without waiting for Save. Only fires once the user has actually
  // edited something; a stale (out-of-order) response is dropped via validateSeq. Errors are
  // swallowed — this is advisory; the save path re-checks authoritatively.
  useEffect(() => {
    if (readOnly || !dirty.isDirty()) return;
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

  const replaceRows = (section: string, rows: EntityRecord[]) => {
    dirty.mark();
    setRowsBySection((prev) => ({ ...prev, [section]: rows }));
  };
  const setCell = (section: string, idx: number, key: string, value: unknown) => {
    dirty.mark();
    // Same-row cascade: a cell other cells' filterBy reads clears them in this row only.
    const dependentCols = (presentedMeta().tabularSections?.find((ts) => ts.name === section)?.attributes ?? [])
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
    if (readOnly || saving || (thenPost && (!postable || !canPost))) return;
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
      const related = relationState.payload();
      if (related.length) payload["@relations"] = related;
      // Silent: the form owns a failed save's feedback — inline field errors, or one
      // "Couldn't save" toast in the catch below (#505).
      let saved: EntityRecord;
      if (kind === "documents") {
        saved = isEdit
          ? await api.updateDocument(name, id!, payload, { silent: true })
          : await api.createDocument(name, payload, { silent: true });
      } else {
        saved = isEdit
          ? await api.updateCatalogItem(name, id!, payload, { silent: true })
          : await api.createCatalogItem(name, payload, { silent: true });
      }
      const savedId = String(isEdit ? id : saved.id);
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
      dirty.clear(); // saved — nothing left for the discard guard to protect
      relationState.reset();
      if (isEdit) {
        // The record route is the form: keep it open and re-seed from the authoritative saved row.
        applyRecord(saved);
        if (refreshable || (thenPost && kind === "documents")) await refetch(true);
        toast.success(t("toast.saved"));
        setSaving(false);
        return;
      }
      // A create that a ref picker is waiting on ("+ New" quick-create) hands the id back to
      // the picker and closes quietly — the user stays on the form they were filling in.
      // Otherwise open the saved record. Either way close the form pane (lists refresh over SSE).
      const pickedUp = quickCreate.consume(kind, name, savedId);
      if (!pickedUp) openRoute(`onno://${kind}/${name}/${savedId}`);
      closeRoute(formPath);
    } catch (e) {
      // A server-side validation error carries per-field messages — map them onto the inputs.
      // Anything else gets one "Couldn't save" toast; the save calls are silent, so the form
      // owns it.
      // A relation row's errors name the join entity's fields, not this form's: toast them.
      const formKeys = new Set([...fields.map((f) => f.key), ...sections.map((ts) => ts.name)]);
      if (e instanceof ApiError && e.fieldErrors && Object.keys(e.fieldErrors).some((field) => formKeys.has(field))) {
        const mapped: Record<string, string> = {};
        for (const [field, messages] of Object.entries(e.fieldErrors)) {
          mapped[field] = Array.isArray(messages) ? messages[0] : String(messages);
        }
        setErrors(mapped);
      } else {
        notifyError(e, { format: (error) => t("form.saveError", { error }) });
      }
      setSaving(false);
    }
  };

  const cancel = () => {
    // Cancel is an explicit discard — clear the dirty flag first so nothing asks "discard?".
    dirty.clear();
    // Cancelling a create also cancels any ref-picker quick-create waiting on it.
    if (!isEdit) quickCreate.cancel(kind, name);
    closeRoute(formPath);
  };

  // Editing context under the title: the record's code/number + description, so the user
  // always knows *which* supplier/order they're deep inside of. A posted document shows its
  // status pill — re-posting an already-posted document is a different mental act than posting.
  const subtitleParts = isEdit
    ? [record?.code, record?.number, record?.description].filter(
        (v): v is string =>
          typeof v === "string" &&
          v.trim() !== "" &&
          // The title often already carries the number/name ("Orders SO-42"); only surface
          // identity the title doesn't show.
          !title.includes(v)
      )
    : [];

  // A field spans the full row unless customized `width: "half"`, letting short fields (dates,
  // amounts, refs) sit side by side on wide screens.
  const isHalf = (f: Field) => f.kind === "attr" && f.attr.width === "half";

  // The form's entity, naming the reference fields' owner so the server applies their PickerOptions.
  const owner: PickerOwner = { ownerKind: kind === "documents" ? "document" : "catalog", ownerName: meta.name };
  const extensionContext: ExtensionContext = {surface:"entity-form",kind,name,recordId:isEdit ? id : undefined,record:record ?? undefined,permissions:{canWrite:!readOnly},openRecord:(kind,name,id)=>openRoute(`onno://${kind}/${name}/${id}`)};
  const renderFieldRow = (field: Field) => <FormFieldRow
    field={field}
    value={Object.prototype.hasOwnProperty.call(data, field.key) ? data[field.key] : record?.[field.key]}
    error={errors[field.key] ?? (touched.has(field.key) ? serverErrors[field.key] : undefined)}
    feedback={feedbackFor(field.key)}
    filterValues={data}
    documentId={kind === "documents" && isEdit ? id ?? undefined : undefined}
    owner={owner}
    restricted={isRestrictedRef(record, field.key) && data[field.key] == null}
    onChange={(value) => { if (!readOnly) set(field.key, value); }}
  />;
  const renderFields = (selection?: string[], overrides?: Record<string, FieldCustomization>) => {
    if (overrides) Object.assign(presentation, overrides);
    const customized = presentedMeta();
    const selected = selection ?? form.fieldOrder ?? null;
    const visible = fields.filter((f) => !selected || selected.includes(f.key)).map((f): Field => {
      if (f.kind === "system") return { ...f, label: presentation[f.key]?.label ?? f.label };
      const attr = customized.attributes.find((a) => a.fieldName === f.key) ?? f.attr;
      return { ...f, attr, label: attr.displayName };
    }).filter((f) => f.kind === "system" ? !presentation[f.key]?.hidden && !presentation[f.key]?.hideInForm : f.attr.visibleInForm !== false);
    if (selected) visible.sort((a, b) => selected.indexOf(a.key) - selected.indexOf(b.key));
    const grouped = new Map<string, Field[]>();
    for (const field of visible) {
      const title = field.kind === "attr" ? (field.attr.group ?? "").trim() : "";
      if (!grouped.has(title)) grouped.set(title, []);
      grouped.get(title)!.push(field);
    }
    const groups = Array.from(grouped, ([title, fields]) => ({ title, fields }));
    return <fieldset disabled={readOnly} className="contents">{groups.map((g, gi) => (
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
                {renderFieldRow(f)}
              </div>
            ))}
          </div>
        </div>
      ))}</fieldset>;
  };
  const renderLines = (section?: string, overrides?: Record<string, FieldCustomization>) => {
    if (section && overrides) for (const [key, value] of Object.entries(overrides)) presentation[`${section}.${key}`] = value;
    const selectedSections = (presentedMeta().tabularSections ?? []).filter((ts) => !section || ts.name === section);
    return <>{selectedSections.map((ts) => (
        <TabularSectionEditor
          key={ts.name}
          section={ts}
          rows={rowsBySection[ts.name] ?? []}
          readOnly={readOnly}
          headerValues={data}
          documentId={isEdit ? id ?? undefined : undefined}
          owner={owner}
          feedback={asyncFeedback}
          onRows={(rows) => replaceRows(ts.name, rows)}
          onCell={(idx, key, value) => setCell(ts.name, idx, key, value)}
        />
      ))}{relationState.relations.filter((r) => r.section && (!section || r.key === section)).map((r) => (
        <TabularSectionEditor
          key={`relation:${r.key}`}
          section={r.section!}
          rows={r.rows}
          readOnly={readOnly || r.readOnly || !r.loaded}
          feedback={[]}
          onRows={(rows) => relationState.setRows(r.key, rows)}
          onCell={(idx, key, value) => relationState.setRows(r.key,
            r.rows.map((row, i) => (i === idx ? { ...row, [key]: value } : row)))}
        />
      ))}</>;
  };
  const defaultLayout = <>{renderFields()}{renderLines()}</>;
  const grids = sections.length + relationState.relations.length;
  const renderPage = (children: ReactNode = defaultLayout, options: RecordPageOptions = {}) => {
    const actions = options.commands ? [
      ...presentRecordActions(refreshedHeader?.actions ?? form.actions, options.commands, (key) => t(`action.${key}`)),
      ...(isEdit && id ? commandItems(kind, name, id, refreshedHeader?.commands ?? form.commands ?? [], options.commands as Record<string, CommandPresentation>, refreshedHeader?.record ?? initial) : []),
    ] : headerActions;
    const pagePanels = options.panels ?? panels;
    return (
    <div className={cn("mx-auto flex w-full flex-wrap justify-center gap-5", grids === 0 && "max-w-5xl")}>
      <div className={cn("w-full min-w-0 flex-1", grids === 0 && "max-w-2xl")}>
      <div data-onno-page-header="" className="mb-5 flex items-start justify-between gap-3">
        <div className="min-w-0">
          <div className="flex items-center gap-2.5">
            <h1 className="truncate text-xl font-semibold text-foreground">{title}</h1>
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
        {/* Record-level actions (unpost / duplicate / delete / record commands) — the same
            cluster the old read-only detail header rendered, now pinned beside the form title. */}
        {actions?.length ? <RecordActions items={actions} /> : null}
        <ExtensionSlot name="entity.form.actions" context={extensionContext} className="flex items-center gap-2" />
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
      {children}
      </fieldset>
      {isEdit && id && record && pagePanels.length > 0 ? (
        <RecordPanels
          panels={pagePanels}
          kind={kind}
          name={name}
          id={id}
          record={record}
          readOnly={readOnly}
        />
      ) : null}
      {/* Cross-field rule failures from the live dry-run (e.g. "slot already booked") have no
          single input to attach to — surface them as a quiet form-level notice above the footer. */}
      {!readOnly && (serverFormErrors.length > 0 || asyncFormFeedback.length > 0) ? (
        <div
          className="mt-4 rounded-card border border-border bg-muted/30 px-4 py-2.5 text-sm"
          aria-live="polite"
          role={serverFormErrors.length > 0 || asyncFormFeedback.some((f) => f.severity === "ERROR") ? "alert" : "status"}
        >
          {serverFormErrors.map((msg, i) => (
            <p className="text-destructive" key={`server-${i}`}>{msg}</p>
          ))}
          <FeedbackMessages feedback={asyncFormFeedback} />
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
            {saving ? t("action.saving") : postable || isEdit ? t("action.save") : t("action.create")}
          </button>
          {postable && canPost && (options.commands?.post === "hidden" ? "hidden" : typeof options.commands?.post === "object" ? options.commands.post.placement : postPlacement) !== "hidden" ? (
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
      {(options.history ?? (form.history === true || findEntity(model, `${kind}/${name}`)?.history === true)) && isEdit && id ? <RecordHistory target={{ kind, name, id }} /> : null}
      {options.comments && model?.features.comments && isEdit && id ? <CommentThread target={{ kind, name, id }} /> : null}
    </div><ExtensionSlot name="entity.form.aside" context={extensionContext} className="w-72 max-w-full space-y-4" /></div>
  );
  };
  const setValue = (key: string, value: unknown) => {
    if (readOnly) return;
    if (sections.some((section) => section.name === key)) {
      if (Array.isArray(value)) replaceRows(key, value);
    } else set(key, value);
  };
  const values = { ...record, ...data, ...rowsBySection };
  const bindField = (key: string, options?: FieldCustomization): RecordFieldBinding => {
    if (options) presentation[key] = options;
    const customized = presentedMeta();
    const attribute = customized.attributes.find((attr) => attr.fieldName === key) ?? null;
    const section = customized.tabularSections?.find((section) => section.name === key) ?? null;
    const original = fields.find((field) => field.key === key);
    const label = options?.label ?? attribute?.displayName ?? section?.label ?? original?.label ?? key;
    const field: Field | undefined = attribute ? { kind: "attr", key, label, attr: attribute }
      : original?.kind === "system" ? { ...original, label } : undefined;
    const restricted = isRestrictedRef(record, key) && data[key] == null;
    return {
      name: key, label, value: values[key], onChange: (value) => { if (!restricted) setValue(key, value); },
      disabled: readOnly || restricted, restricted, required: attribute?.required === true,
      error: errors[key] ?? serverErrors[key], feedback: feedbackFor(key), attribute, section,
      reference: attribute?.isRef && attribute.refTarget && !restricted ? {
        ...referenceProps(attribute, values[key], (value) => setValue(key, value), data, {
          ...owner, fieldPath: key, formValues: data, documentId: kind === "documents" && isEdit ? id ?? undefined : undefined,
        }), disabled: readOnly,
      } : null,
      renderDefault: () => field ? <fieldset disabled={readOnly} className="contents">{renderFieldRow(field)}</fieldset>
        : section ? renderLines(key) : null,
    };
  };
  const actionIcons = { "circle-check": CircleCheck, "rotate-ccw": RotateCcw, copy: Copy, "trash-2": Trash2, list: List, "circle-dot": CircleDot };
  const resolvedActions = headerActions.map((action) => ({ ...action,
    icon: typeof action.icon === "string" ? actionIcons[action.icon as keyof typeof actionIcons] ?? CircleDot : action.icon,
  }));
  return {
    id: isEdit ? id : null, kind, name, title, meta, canPost: postable && canPost && !readOnly, posted: wasPosted,
    dirty: dirty.isDirty(), stale: staleExternal, actions: resolvedActions,
    cancel, refresh: () => refetch(true), reload: () => refetch(), bindField,
    values, errors: { ...serverErrors, ...errors }, formErrors: serverFormErrors, feedback: asyncFeedback, isNew: !isEdit,
    readOnly, saving, set: setValue, save, defaultLayout, renderPage, renderFields,
    renderLines: (section, options) => renderLines(section, options),
  };
}

/** The app's record panels under a saved record's form, each handed the record. */
function RecordPanels({
  panels,
  kind,
  name,
  id,
  record,
  readOnly,
}: {
  panels: RecordPanel[];
  kind: "catalogs" | "documents";
  name: string;
  id: string;
  record: EntityRecord;
  readOnly: boolean;
}) {
  const context = { kind, name, id, data: record, readOnly };
  return (
    <div className="mt-4 grid grid-cols-1 gap-4 sm:grid-cols-2">
      {panels.map(({ component: Panel, title, width }, index) => {
        const half = /^(half|1\/2|50%?)$/i.test((width ?? "").trim());
        return (
          <div key={`${title ?? "panel"}:${index}`} className={cn(!half && "sm:col-span-2")}>
            <ErrorBoundary label={title || "Panel"}>
              <Panel record={context} title={title} />
            </ErrorBoundary>
          </div>
        );
      })}
    </div>
  );
}

// An editable grid for one tabular section: add/remove rows, with each cell rendered by the
// same AttrControl the top-level fields use (so Ref pickers, enum selects, dates and typed
// inputs all behave identically). Only visible-in-form attributes get a column.
export function TabularSectionEditor({
  section, rows, readOnly, headerValues, documentId, owner, feedback, onRows, onCell,
}: {
  section: TabularSectionMeta;
  rows: EntityRecord[];
  readOnly?: boolean;
  headerValues?: EntityRecord;
  documentId?: string;
  /** The entity whose form holds this section (selects its fields' PickerOptions). */
  owner?: PickerOwner;
  feedback?: FormFeedback[];
  onRows: (rows: EntityRecord[]) => void;
  onCell: (idx: number, key: string, value: unknown) => void;
}) {
  const t = useMessages();
  const instanceId = useId();
  const root = useRef<HTMLDivElement>(null);
  const addButton = useRef<HTMLButtonElement>(null);
  const pendingFocus = useRef<{ row: number; column: number } | null>(null);
  // Keys belong to editor rows, never to their current index or persisted business values.
  const nextKey = useRef(0);
  const rowKeys = useRef<string[]>([]);
  while (rowKeys.current.length < rows.length) rowKeys.current.push(String(nextKey.current++));
  rowKeys.current.length = rows.length;
  const [removed, setRemoved] = useState<{ row: EntityRecord; index: number; key: string } | null>(null);
  const columns = useMemo(() => section.attributes
    .filter((a) => a.visibleInForm !== false)
    .sort((a, b) => (a.order ?? 0) - (b.order ?? 0)), [section]);
  const title = section.label || section.name.charAt(0).toUpperCase() + section.name.slice(1).replace(/_/g, " ");
  const controls = (cell: Element) => Array.from(cell.querySelectorAll<HTMLElement>(
    'input:not([type="hidden"]):not(:disabled), textarea:not(:disabled), button:not(:disabled), [tabindex="0"]'
  )).filter((el) => el.getAttribute("aria-disabled") !== "true");
  const focusCell = (row: number, column: number) => {
    const cell = root.current?.querySelector(`[data-row="${row}"][data-column="${column}"]`);
    const target = cell && controls(cell)[0];
    if (!target) return false;
    target.focus();
    if (target instanceof HTMLInputElement && /^(text|number|email|url|tel)$/.test(target.type)) target.select();
    return true;
  };
  useEffect(() => {
    const destination = pendingFocus.current;
    if (!destination) return;
    pendingFocus.current = null;
    if (!focusCell(destination.row, destination.column)) addButton.current?.focus();
  });
  const add = () => {
    pendingFocus.current = { row: rows.length, column: 0 };
    onRows([...rows, {}]);
  };
  const remove = (index: number) => {
    setRemoved({ row: rows[index], index, key: rowKeys.current[index] });
    rowKeys.current.splice(index, 1);
    pendingFocus.current = { row: Math.min(index, rows.length - 2), column: 0 };
    onRows(rows.filter((_, i) => i !== index));
  };
  const undo = () => {
    if (!removed) return;
    const index = Math.min(removed.index, rows.length);
    rowKeys.current.splice(index, 0, removed.key);
    pendingFocus.current = { row: index, column: 0 };
    onRows([...rows.slice(0, index), removed.row, ...rows.slice(index)]);
    setRemoved(null);
  };
  const columnWidth = (attr: AttributeMeta) => {
    if (/^(boolean|Boolean)$/.test(attr.javaType)) return 96;
    if (attr.isRef || attr.isEnum) return 240;
    if (/^(BigDecimal|Integer|Long|Double|int|long|double)$/.test(attr.javaType)) return 144;
    return 200;
  };

  return (
    <div ref={root} className="mt-4 min-w-0 rounded-panel border border-border bg-card">
      <div className="flex flex-wrap items-center justify-between gap-2 px-4 py-3">
        <h2 className="flex shrink-0 items-center gap-2 whitespace-nowrap text-sm font-medium">
          {title}<span className="rounded-pill bg-muted px-2 py-0.5 text-xs text-muted-foreground">{rows.length}</span>
        </h2>
        {!readOnly && <HintIcon text={t("form.tabularKeyboardHint")} size={16} />}
      </div>
      {rows.length === 0 ? (
        <div className="border-t border-border px-4 py-6 text-sm text-muted-foreground">
          {t(readOnly ? "empty.noRows" : "form.tabularEmptyHint")}
        </div>
      ) : (
        <div className="max-h-[32rem] overflow-auto border-y border-border">
          <table className="w-full table-fixed border-collapse text-sm"
            style={{ minWidth: columns.reduce((width, attr) => width + columnWidth(attr), readOnly ? 48 : 96) }}>
            <caption className="sr-only">{title}</caption>
            <colgroup>
              <col style={{ width: 48 }} />
              {columns.map((attr) => <col key={attr.fieldName} style={{ width: columnWidth(attr) }} />)}
              {!readOnly && <col style={{ width: 48 }} />}
            </colgroup>
            <thead className="sticky top-0 z-10 bg-muted">
              <tr>
                <th scope="col" className="px-2 py-3 text-xs font-medium text-muted-foreground">#</th>
                {columns.map((attr) => (
                  <th key={attr.fieldName} scope="col" className="px-2 py-3 text-left text-xs font-medium text-muted-foreground">
                    {attr.displayName}{attr.required && <span className="ml-0.5 text-destructive">*</span>}
                    <HintIcon text={attr.hint} size={13} />
                  </th>
                ))}
                {!readOnly && <th scope="col"><span className="sr-only">{t("form.rowActions")}</span></th>}
              </tr>
            </thead>
            <tbody>
              {rows.map((row, idx) => (
                <tr key={rowKeys.current[idx]} className="border-t border-border hover:bg-muted/30 focus-within:bg-muted/50">
                  <th scope="row" className="px-2 py-2 align-middle text-center text-xs font-normal tabular-nums text-muted-foreground">{idx + 1}</th>
                  {columns.map((attr, column) => {
                    const cellFeedback = feedback?.filter((item) => item.field === `${section.name}.${attr.fieldName}`) ?? [];
                    const feedbackId = `${instanceId}-${rowKeys.current[idx]}-${attr.fieldName}`;
                    return (
                      <td key={attr.fieldName} data-row={idx} data-column={column} className="px-2 py-2 align-top"
                        onKeyDown={(event) => {
                          // Portaled pickers, multiline text and IME keep their native key behavior.
                          if (readOnly || event.defaultPrevented || event.nativeEvent.isComposing || event.altKey || event.ctrlKey || event.metaKey) return;
                          if (!(event.target instanceof HTMLInputElement) || !event.currentTarget.contains(event.target)
                              || event.target.getAttribute("role") === "combobox" || event.key !== "Enter") return;
                          event.preventDefault();
                          const step = event.shiftKey ? -1 : 1;
                          const next = idx * columns.length + column + step;
                          if (next < 0) return;
                          if (next >= rows.length * columns.length) add();
                          else focusCell(Math.floor(next / columns.length), next % columns.length);
                        }}>
                        <fieldset disabled={readOnly} aria-label={t("form.rowField", { row: idx + 1, field: attr.displayName })}
                          aria-describedby={cellFeedback.length ? feedbackId : undefined} className="min-w-0">
                        <AttrControl
                          attr={attr}
                          value={row[attr.fieldName]}
                          compact
                          placeholder={attr.placeholder}
                          invalid={feedback?.some(
                            (item) => item.field === `${section.name}.${attr.fieldName}` && item.severity === "ERROR"
                          )}
                          // Row values win over header values, so a cell can cascade on either.
                          filterValues={{ ...headerValues, ...row }}
                          optionContext={{
                            ...owner,
                            fieldPath: `${section.name}.${attr.fieldName}`,
                            formValues: headerValues ?? {},
                            section: section.name,
                            rowIndex: idx,
                            rowValues: row,
                            documentId,
                          }}
                          excludedIds={
                            attr.uniqueWithinSection
                              ? rows
                                  .filter((_, rowIndex) => rowIndex !== idx)
                                  .map((sibling) => sibling[attr.fieldName])
                                  .filter((candidate): candidate is string => typeof candidate === "string" && candidate !== "")
                              : undefined
                          }
                          onChange={(v) => onCell(idx, attr.fieldName, v)}
                        />
                          {cellFeedback.length > 0 && <div id={feedbackId} aria-live="polite"><FeedbackMessages feedback={cellFeedback} /></div>}
                        </fieldset>
                      </td>
                    );
                  })}
                  {!readOnly && <td className="px-1 py-2 align-top">
                    <div className="flex">
                      <Button type="button" variant="ghost" size="icon" className="size-9 text-muted-foreground hover:text-destructive" onClick={() => remove(idx)}
                        aria-label={t("form.removeRow", { row: idx + 1 })} title={t("form.removeRow", { row: idx + 1 })}>
                        <Trash2 className="size-4" />
                      </Button>
                    </div>
                  </td>}
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
      {!readOnly && <div className="flex flex-wrap items-center gap-3 px-4 py-3">
        <Button ref={addButton} type="button" variant="subtle" size="toolbar" onClick={add}>
          <Plus className="size-4" />{t("action.addRow")}
        </Button>
        {removed && <div className="flex items-center gap-2 text-xs text-muted-foreground">
          <span role="status">{t("form.rowRemoved")}</span>
          <Button type="button" variant="ghost" size="toolbar" onClick={undo}><Undo2 className="size-4" />{t("form.undoRemoveRow")}</Button>
        </div>}
      </div>}
    </div>
  );
}

function FormFieldRow({
  field,
  value,
  error,
  feedback = [],
  filterValues,
  documentId,
  owner,
  restricted,
  onChange,
}: {
  field: Field;
  value: unknown;
  error?: string;
  feedback?: FormFeedback[];
  /** The form's current values, for a ref field's cascading `filterBy`. */
  filterValues?: EntityRecord;
  documentId?: string;
  owner?: PickerOwner;
  /** A ref whose target the viewer may not read: rendered as a locked chip, never submitted. */
  restricted?: boolean;
  onChange: (value: unknown) => void;
}) {
  const required = field.kind === "attr" && field.attr.required;
  const placeholder = field.kind === "attr" ? field.attr.placeholder : undefined;
  const hint = field.kind === "attr" ? field.attr.hint : undefined;
  const invalid = !!error || feedback.some((item) => item.severity === "ERROR");
  const feedbackId = `feedback-${field.key}`;
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
        restricted={restricted}
        invalid={invalid}
        placeholder={placeholder}
        filterValues={filterValues}
        optionContext={
          field.kind === "attr"
            ? {
                ...owner,
                fieldPath: field.attr.fieldName,
                formValues: filterValues ?? {},
                documentId,
              }
            : undefined
        }
        onChange={onChange}
      />
    );

  // Booleans: a settings-style switch (label left, toggle right) with `input: "switch"`,
  // otherwise an inline checkbox that owns its label.
  if (field.kind === "attr" && /^(boolean|Boolean)$/.test(field.attr.javaType)) {
    if (field.attr.input === "switch") {
      return (
        <div role="group" aria-describedby={feedbackId}>
          <div className="flex items-center justify-between gap-4">
            <div className="flex items-center gap-1.5">
              <Label htmlFor={field.key}>{field.label}</Label>
              <HintIcon text={hint} size={13} />
            </div>
            <Switch id={field.key} checked={!!value} onCheckedChange={(v) => onChange(v === true)} />
          </div>
          <div id={feedbackId} aria-live="polite">
            {error ? <p className="mt-1 text-xs text-destructive">{error}</p> : null}
            <FeedbackMessages feedback={feedback.filter((item) => item.message !== error)} />
          </div>
        </div>
      );
    }
    return (
      <div role="group" aria-describedby={feedbackId}>
        <div className="flex items-center gap-2">
          <Checkbox
            id={field.key}
            checked={!!value}
            onCheckedChange={(v) => onChange(v === true)}
          />
          <Label htmlFor={field.key}>{field.label}</Label>
          <HintIcon text={hint} size={13} />
        </div>
        <div id={feedbackId} aria-live="polite">
          {error ? <p className="mt-1 text-xs text-destructive">{error}</p> : null}
          <FeedbackMessages feedback={feedback.filter((item) => item.message !== error)} />
        </div>
      </div>
    );
  }

  return (
    <div className="grid gap-1.5" role="group" aria-describedby={feedbackId}>
      <div className="flex items-center gap-1.5">
        <Label htmlFor={field.key}>
          {field.label}
          {required ? <span className="ml-1 text-destructive">*</span> : null}
        </Label>
        <HintIcon text={hint} size={13} />
      </div>
      {control}
      <div id={feedbackId} aria-live="polite">
        {error ? <p className="text-xs text-destructive">{error}</p> : null}
        <FeedbackMessages feedback={feedback.filter((item) => item.message !== error)} />
      </div>
    </div>
  );
}

function FeedbackMessages({ feedback }: { feedback: FormFeedback[] }) {
  return (
    <>
      {feedback.map((item, index) => (
        <p
          key={`${item.severity}-${item.field}-${index}`}
          className={cn(
            "mt-1 text-xs",
            item.severity === "ERROR" && "text-destructive",
            item.severity === "WARNING" && "text-amber-700 dark:text-amber-300",
            item.severity === "INFO" && "text-sky-700 dark:text-sky-300"
          )}
        >
          {item.message}
        </p>
      ))}
    </>
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
  optionContext,
  excludedIds,
  restricted,
}: {
  attr: AttributeMeta;
  value: unknown;
  /** A ref the viewer may not read (see RestrictedRef). */
  restricted?: boolean;
  onChange: (value: unknown) => void;
  invalid?: boolean;
  placeholder?: string;
  /** Grid-cell rendering (tabular sections): keep multi-line controls to a single-row height. */
  compact?: boolean;
  /** Current form/row values, for attr.refFilterBy's sources. */
  filterValues?: EntityRecord;
  /** The field's owner and live form/row state, for the field's server-side PickerOptions. */
  optionContext?: Partial<PickerOwner> & {
    fieldPath: string;
    formValues: EntityRecord;
    section?: string;
    rowIndex?: number;
    rowValues?: EntityRecord;
    documentId?: string;
  };
  excludedIds?: string[];
}) {
  const t = useMessages();
  const invalidCls = invalid ? "border-destructive focus-visible:ring-destructive" : undefined;
  if (/^(boolean|Boolean)$/.test(attr.javaType)) {
    const isSwitch = attr.input === "switch";
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

  // The map input wins over the type-based control. The editor stores a GeoJSON FeatureCollection
  // containing points, paths, or areas in a String attribute.
  if (attr.input === "map") {
    return <MapEditor value={value as string | undefined} onChange={onChange} />;
  }

  // Media inputs stream the chosen file to POST /api/media and store only the returned reference
  // URL — so a plain String attribute holds it (no base64-sized TEXT). "avatar" is a small round
  // image variant; "images" stores several URLs newline-joined; "file" takes any type.
  if (attr.input === "images") {
    return <GalleryPicker value={value as string | undefined} onChange={onChange} />;
  }
  if (attr.input === "image") {
    return <ImagePicker value={value as string | undefined} onChange={onChange} />;
  }
  if (attr.input === "avatar") {
    return <ImagePicker variant="avatar" value={value as string | undefined} onChange={onChange} />;
  }
  if (attr.input === "file") {
    return <FilePicker value={value as string | undefined} onChange={onChange} />;
  }
  if (attr.input === "color") {
    return (
      <ColorPicker
        value={(value as string | undefined) ?? ""}
        onChange={onChange}
        invalid={invalid}
        placeholder={placeholder}
      />
    );
  }

  if (restricted && (attr.isRef || attr.isPolymorphicRef)) {
    return (
      <div className="flex h-9 items-center">
        <RestrictedRef />
      </div>
    );
  }

  if (attr.isPolymorphicRef && attr.refTargets?.length) {
    return (
      <PolymorphicRefInput
        attr={attr}
        value={value as string | undefined}
        onChange={onChange}
      />
    );
  }

  if (attr.isRef && attr.refTarget) {
    return (
      <RefSelect {...referenceProps(attr, value, onChange, filterValues, optionContext, excludedIds)} />
    );
  }

  if (attr.isEnum && attr.enumValues) {
    return (
      <Select
        value={(value as string | null | undefined) ?? ""}
        onValueChange={(next) => onChange(next === NO_SELECTION_VALUE ? null : next)}
      >
        <SelectTrigger>
          <SelectValue placeholder={t("form.select", { name: attr.displayName })} />
        </SelectTrigger>
        <SelectContent>
          {!attr.required ? (
            <SelectItem value={NO_SELECTION_VALUE}>{t("form.noSelection")}</SelectItem>
          ) : null}
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

  if (attr.javaType === "LocalTime") {
    return (
      <TimePicker
        value={(value as string) ?? ""}
        onChange={onChange}
        aria-label={attr.displayName}
        isInvalid={invalid}
      />
    );
  }

  if (attr.javaType === "Instant") {
    return (
      <InstantPicker
        value={(value as string) ?? ""}
        onChange={onChange}
        aria-label={attr.displayName}
        isInvalid={invalid}
      />
    );
  }

  if (attr.javaType === "LocalDate" || attr.javaType === "LocalDateTime") {
    const Picker = attr.javaType === "LocalDateTime" ? DateTimePicker : DatePicker;
    return (
      <Picker
        value={(value as string) ?? ""}
        onChange={onChange}
        aria-label={attr.displayName}
        isInvalid={invalid}
      />
    );
  }

  // Multi-line text: `input: "textarea"`, or any String long enough (length > 1000, or unbounded)
  // that a single-line input clearly wasn't meant — notes, addresses, comments.
  const longText =
    attr.javaType === "String" && !attr.input && (attr.length <= 0 || attr.length > 1000);
  if (attr.input === "textarea" || longText) {
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

function PolymorphicRefInput({
  attr,
  value,
  onChange,
}: {
  attr: AttributeMeta;
  value?: string;
  onChange: (value: unknown) => void;
}) {
  const t = useMessages();
  const targets = attr.refTargets ?? [];
  const separator = value?.lastIndexOf("|") ?? -1;
  const encodedType = separator > 0 ? value!.slice(0, separator) : "";
  const encodedId = separator > 0 ? value!.slice(separator + 1) : undefined;
  const [targetType, setTargetType] = useState(
    targets.some((target) => target.javaType === encodedType)
      ? encodedType
      : targets[0]?.javaType ?? ""
  );

  useEffect(() => {
    if (encodedType && encodedType !== targetType) {
      setTargetType(encodedType);
    }
  }, [encodedType, targetType]);

  const target = targets.find((candidate) => candidate.javaType === targetType) ?? targets[0];
  if (!target) return null;

  return (
    <div className="grid min-w-0 grid-cols-[minmax(8rem,0.75fr)_minmax(0,1.25fr)] gap-2">
      <Select
        value={target.javaType}
        onValueChange={(next) => {
          setTargetType(next);
          onChange(null);
        }}
      >
        <SelectTrigger>
          <SelectValue placeholder={t("form.select", { name: attr.displayName })} />
        </SelectTrigger>
        <SelectContent>
          {targets.map((candidate) => (
            <SelectItem key={candidate.javaType} value={candidate.javaType}>
              {candidate.title || candidate.name}
            </SelectItem>
          ))}
        </SelectContent>
      </Select>
      <RefSelect
        key={target.javaType}
        targetName={target.name}
        refKind={target.kind}
        value={encodedType === target.javaType ? encodedId : undefined}
        clearable={!attr.required}
        showAll={false}
        onChange={(id) => onChange(id ? `${target.javaType}|${id}` : null)}
      />
    </div>
  );
}

/**
 * The record commands as header items: hidden ones dropped, `primary` ones as prominent inline
 * buttons, the rest (`menu`, `row`) in the overflow menu. A per-record icon reads the saved record.
 */
/** The entity whose form holds a reference field (see RefOptionSearch). */
type PickerOwner = { ownerKind: "catalog" | "document"; ownerName: string };

function commandItems(
  kind: string,
  name: string,
  id: string,
  commands: CommandDescriptor[],
  presentation: Record<string, CommandPresentation> | undefined,
  record: EntityRecord | null,
): RecordAction[] {
  return presentCommands(commands, presentation)
    .filter((c) => c.target !== "collection" && c.placement !== "hidden")
    .map((c) => ({
      label: c.title,
      icon: commandIcon(c, record),
      url: `onno://command/${kind}/${name}/${c.key}/${id}`,
      tone: c.placement === "primary" ? "accent" : "normal",
      placement: c.placement === "primary" ? "primary" : "menu",
      disabled: c.enabled === false,
      description: c.description,
      form: c.form,
      dynamicForm: c.dynamicForm,
      group: c.group,
    }));
}

/**
 * A record form's heading, from the model's entity title and the record: "New Orders",
 * "Duplicate Orders", a catalog item's description (else its code), a document's "Orders SO-42".
 */
export function formTitle(form: FormDescriptor, t: Translate, values: EntityRecord | null = form.initial): string {
  const entity = form.meta.title || form.meta.name;
  if (form.duplicate) return t("form.duplicateTitle", { name: entity });
  if (!form.id) return t("form.newTitle", { name: entity });
  const text = (v: unknown) => (typeof v === "string" ? v.trim() : v == null ? "" : String(v));
  if (form.kind === "catalogs") return text(values?.description) || text(values?.code) || entity;
  return [entity, text(values?.number)].filter(Boolean).join(" ");
}
