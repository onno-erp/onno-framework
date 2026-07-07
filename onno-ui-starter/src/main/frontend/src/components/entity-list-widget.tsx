import { useCallback, useEffect, useLayoutEffect, useMemo, useRef, useState, useSyncExternalStore, type ComponentType, type ReactNode } from "react";
import { ArrowDown, ArrowUp, CalendarDays, Check, ChevronLeft, ChevronRight, ChevronsUpDown, Copy, ExternalLink, LayoutGrid, Link2, ListFilter, Loader2, Map as MapIcon, Plus, Rows3, Search, Table2, Trash2, X } from "lucide-react";
import { CalendarDate, getLocalTimeZone, parseDate, startOfMonth, startOfYear, today } from "@internationalized/date";
import { toast } from "sonner";
import { ListMapView, type ListMapConfig } from "@/components/list-map-view";
import { ActionFormDialog, type ActionFormField } from "@/components/action-form-dialog";
import { GroupedList } from "@/components/entity-list-grouped";
import { PresenceAvatars } from "@/components/presence-avatars";
import { useViewersById } from "@/lib/presence-store";
import { Input } from "@/components/ui/input";
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from "@/components/ui/select";
import { Popover, PopoverContent, PopoverTrigger } from "@/components/ui/popover";
import { FacetSheet, SheetClearButton, SheetDoneButton, useTouchLayout } from "@/components/ui/facet-sheet";
import { Tooltip, TooltipContent, TooltipProvider, TooltipTrigger } from "@/components/ui/tooltip";
import { Badge } from "@/components/ui/badge";
import { RangeCalendar } from "@/components/ui/calendar";
import { DatePicker } from "@/components/date-picker";
import { HintIcon } from "@/components/ui/hint-icon";
import { Segmented } from "@/components/ui/segmented";
import {
  ContextMenuContent,
  ContextMenuItem,
  ContextMenuLabel,
  ContextMenuSeparator,
  ContextMenuShortcut,
  ContextMenuSub,
} from "@/components/ui/context-menu";
import { DynamicLucide } from "@/lib/icon-bridge";
import { getRegistryVersion, resolveWidget, subscribeRegistry } from "@/lib/widget-bridge";
import { api } from "@/lib/api";
import { isInteractiveLayerOpen, matchesKey, shortcutLabel } from "@/lib/keybindings";
import { withBasePath } from "@/lib/base-path";
import { cn, copyToClipboard, enumPillStyle } from "@/lib/utils";
import { applyFormat, formatTimestampDefault, isImageWidget, isAvatarWidget, looksLikeImageUrl } from "@/lib/cell-format";
import type { EntityRecord, UiEvent } from "@/lib/types";
import { useMessages } from "@/providers/messages-provider";
import type { Translate } from "@/lib/messages";

/**
 * The {@code onno-list} React island: a virtualized, server-paged data grid. The DivKit list
 * surface emits only a descriptor (columns, sort, searchability, the open/New routes); this
 * island fetches windows of rows from {@code /api/list/...} as you scroll, sorts and searches
 * on the server, and renders only the visible rows — so a 10k-row entity stays smooth. Rows carry
 * {@code data-onno-row} so the existing right-click Open/Edit/Duplicate menu keeps working.
 */

export type ListColumn = {
  columnName: string;
  label: string;
  width: string;
  /** Display hint: "image"/"avatar" renders the cell value as a thumbnail. */
  widget?: string;
  /** Display format: a date pattern ("dd-MM-yy") or number spec ("currency:EUR", "integer", …). */
  format?: string;
  /** Optional help text; surfaced as a hoverable "?" next to the column header. */
  hint?: string;
};
/**
 * A custom action button declared by an EntityView. {@code scope} is "toolbar" (list-level) or
 * "row" (per-record); a {@code server} action POSTs to /api/actions and applies the returned
 * result, a navigation action routes its {@code url} ({@code {id}} filled with the row id).
 */
export type ListAction = {
  key: string;
  label: string;
  icon: string;
  /** Image URL/path shown instead of the lucide icon — e.g. a brand logo for "Connect with X". */
  logo?: string;
  scope: "toolbar" | "row";
  /**
   * Context-menu placement (from ActionSpec.menu("…")): the row action renders inside the row's
   * right-click menu under a submenu with this label, instead of as an inline row icon button.
   */
  menu?: string;
  server: boolean;
  url?: string;
  kind: string;
  name: string;
  /**
   * Form fields a server action collects in a modal dialog before it runs (ActionSpec.form).
   * The submitted values POST as the action's inputs, alongside the toolbar input values.
   */
  form?: ActionFormField[];
};
/**
 * Per-row override for a state-aware row action, computed server-side from the row's data and
 * carried on the row under {@code _actions} (keyed by action key). Lets one control vary by row:
 * a different {@code icon}/{@code label} (e.g. pause→play), or {@code visible}/{@code enabled} false
 * to hide/grey it on rows it doesn't apply to. Any field absent falls back to the static descriptor
 * (icon/label) or the default (visible/enabled = true).
 */
export type RowActionState = {
  visible?: boolean;
  enabled?: boolean;
  icon?: string;
  label?: string;
};
/**
 * A custom toolbar input declared by an EntityView. It doesn't filter the list — its current value
 * is sent with whatever server action button is clicked and reaches the handler via ActionContext.
 */
export type ListInput = {
  key: string;
  label: string;
  /** "textarea" only ever arrives on action-form fields; a toolbar renders it as a text field. */
  type: "text" | "textarea" | "date" | "number" | "select";
  placeholder: string;
  options: string[];
  value: string;
  /** Gates an action-form dialog's submit; toolbar inputs ignore it (they're ambient values). */
  required?: boolean;
};
/**
 * A declarative filter that drives the list query itself (unlike a {@link ListInput}, which feeds
 * action handlers). "options" renders a SELECT matched for equality on {@code column};
 * "multiOptions" renders a multi-select matched as {@code column IN (…)}; "contains"/"startsWith"
 * render a debounced typeahead matched case-insensitively (LIKE %v% / v%) — the high-cardinality
 * answer where enumerating every value is unusable; "dateRange" renders from/to date pickers driving
 * a {@code column >= from AND column <= to} range. The current value is sent to /api/list as
 * eq/in/like/prefix/ge/le params, and several filters narrow the list jointly (AND).
 */
/** One choice of a (multi-)options filter: {@code value} is matched on the column, {@code label} is
 *  shown, {@code color} (an @EnumLabel hex, when the field is an enumeration) tints the choice like
 *  the entity's status pills. */
export type FilterOption = { value: string; label: string; color?: string };
export type ListFilterControl = {
  key: string;
  label: string;
  column: string;
  type: "options" | "multiOptions" | "contains" | "startsWith" | "dateRange";
  options: FilterOption[];
};
/**
 * A custom list-body renderer (from {@code ListSpec.custom(type)}): the widget-registry key the
 * island resolves the component from, an optional toolbar-toggle {@code label} (else the
 * {@code list.customView} message), and whether the list opens on the custom view. An unregistered
 * type degrades to the default grid — the toggle simply doesn't appear.
 */
export type ListCustomConfig = { type: string; label?: string; defaultView?: boolean };
/**
 * The props a custom list-body renderer receives (registered via {@code registerListRenderer} from
 * {@code @onno/widget-sdk}, or {@code registerWidget} host-side). The framework keeps the toolbar
 * (search, filters, sort, group-by) and the feed (infinite/paged, live refresh) — the renderer only
 * draws the rows it's handed and opens a record through the callback.
 */
export type ListRendererProps = {
  /** The rows of the current window (all loaded rows in infinite mode; the page in paged mode). */
  rows: EntityRecord[];
  /** The list's descriptor slice: entity route, title, resolved columns (labels/widgets/formats), write access. */
  list: { kind: string; name: string; title: string; columns: ListColumn[]; canWrite: boolean };
  /** Open a record's detail pane (no-op for rows without an id, e.g. register rows). */
  open: (row: EntityRecord) => void;
  /** The record's {@code onno://} detail route, or null when it has none. */
  openUrl: (row: EntityRecord) => string | null;
};
/** A column the list can be grouped by; `date` columns bucket by a chosen day/month/year granularity. */
export type GroupableColumn = { columnName: string; label: string; date: boolean };
/** A per-group subtotal: an aggregate `fn` over `columnName`, shown with `format` and headed `label`. */
export type ListAggregate = { columnName: string; fn: string; label: string; format: string };
export type ListDescriptor = {
  kind: "catalogs" | "documents" | "registers";
  name: string;
  title: string;
  columns: ListColumn[];
  searchable: boolean;
  sort: { column: string | null; descending: boolean };
  newUrl: string | null;
  /**
   * The viewer's write access on the entity (server-stamped from RBAC). When false the island
   * hides its write affordances — row Edit/Duplicate/Delete, batch delete — and stamps rows
   * data-onno-row-writable="0" so the shell's fallback row menu and shortcuts hide theirs too.
   * Absent (old server) means unknown; treat as writable, REST enforces regardless.
   */
  canWrite?: boolean;
  actions?: ListAction[];
  inputs?: ListInput[];
  filters?: ListFilterControl[];
  /** When set, the toolbar offers a Table ⇄ Map toggle that plots the rows as markers. */
  map?: ListMapConfig;
  /** When set (and the type is registered), the toolbar offers a Table ⇄ custom-view toggle. */
  custom?: ListCustomConfig;
  /**
   * How the grid feeds rows. "infinite" (default) cursor-scrolls a keyset stream — it loads a
   * window, then more as you scroll, and never counts an exact total. "paged" shows discrete
   * numbered offset pages with a Prev/Next pager and an exact total. Absent → "infinite".
   */
  feedMode?: "infinite" | "paged";
  /** Columns offered in the "Group by ▾" picker; a `date` column buckets by day/month/year. */
  groupable?: GroupableColumn[];
  /** Per-group subtotals shown on each group header (and their display format). */
  aggregates?: ListAggregate[];
  pageSize: number;
  // Embedded in an authored page (PageBuilder.list) rather than rendered as its own route surface.
  // The page already pads its content, so the widget drops its horizontal gutter to align its table
  // with the page's other full-width components (constants editor, action sections).
  embedded?: boolean;
  // Override the data feed URL. Catalogs/documents page from /api/list/{kind}/{name}; a register
  // has no such route, so its descriptor points the island at /api/list/registers/{name}/movements
  // (or /balance). When unset, the default {kind}/{name} feed is used.
  feed?: string;
};

export const ROW_H = 40;
// Radix Select forbids an empty-string item value, so the "no selection" / placeholder choice
// uses this sentinel and is mapped back to "" in state.
const SELECT_NONE = "__onno_none__";

function dispatchAction(url: string) {
  window.dispatchEvent(new CustomEvent("onno:action", { detail: url }));
}

// Mirrors divkit-view's url helpers for the row context menu this island owns:
// open url → delete-action url / route path / absolute shareable link.
function rowDeleteUrl(rowUrl: string): string {
  return "onno://delete/" + rowUrl.slice("onno://".length);
}
function rowShareableUrl(rowUrl: string): string {
  return window.location.origin + withBasePath("/" + rowUrl.slice("onno://".length));
}

/** Whether keyboard input currently belongs to an editable control (so shortcuts stay native). */
function isEditingTarget(): boolean {
  const el = document.activeElement as HTMLElement | null;
  return !!el && (el.tagName === "INPUT" || el.tagName === "TEXTAREA" || el.tagName === "SELECT" || el.isContentEditable);
}

/** The hovered row's id — only if the row belongs to THIS list (several can be mounted at once). */
function hoveredRowId(kind: string, name: string): string | null {
  const el = document.querySelector("[data-onno-row]:hover") as HTMLElement | null;
  const url = el?.dataset.onnoRow;
  if (!url || !url.startsWith(`onno://${kind}/${name}/`)) return null;
  return url.slice(url.lastIndexOf("/") + 1);
}

/** Nearest ancestor that can scroll vertically — the DivKit page wrapper on route surfaces. */
function nearestScrollAncestor(el: HTMLElement): HTMLElement | null {
  for (let p = el.parentElement; p; p = p.parentElement) {
    const o = getComputedStyle(p).overflowY;
    if (o === "auto" || o === "scroll") return p;
  }
  return null;
}

/** The underlying cell string: a resolved ref/enum label, the posted badge, or the raw value. */
function rawCellValue(row: EntityRecord, col: ListColumn, t: Translate): string {
  if (col.columnName === "_posted") return t(row["_posted"] === true ? "status.posted" : "status.draft");
  const v = row[`${col.columnName}_display`] ?? row[col.columnName];
  return v == null ? "" : String(v);
}

/**
 * The displayed text: secret mask, image placeholder, or the value run through .format(...).
 * A full date-time with no authored format falls back to the locale default — a raw
 * "2026-07-05T00:58:48.232+00:00" is machine output, never a display value.
 */
function displayCellValue(raw: string, col: ListColumn): string {
  if (raw === "__SECRET_SET__") return "•••• set";
  if (raw.startsWith("data:")) return "🖼 Image"; // a non-image column keeps the placeholder
  return applyFormat(raw, col.format) ?? formatTimestampDefault(raw) ?? raw;
}

/** One list cell: an image thumbnail for image/avatar columns, otherwise formatted text. */
export function ListCell({ row, col }: { row: EntityRecord; col: ListColumn }) {
  const t = useMessages();
  const raw = rawCellValue(row, col, t);
  if (isImageWidget(col.widget) && raw && looksLikeImageUrl(raw)) {
    const avatar = isAvatarWidget(col.widget);
    return (
      <span className="flex items-center">
        <img
          src={raw}
          alt=""
          loading="lazy"
          className={cn(
            "h-7 shrink-0 border border-border object-cover",
            avatar ? "w-7 rounded-control" : "w-10 rounded"
          )}
        />
      </span>
    );
  }
  // A Ref column whose target carries an avatar (resolved into {col}_avatar by the read API): show
  // the photo as a small round avatar beside the display text (issue #182). The data is already
  // here; only image/avatar-widget columns drew it before, so a plain Ref cell stayed text-only.
  const refAvatar = row[`${col.columnName}_avatar`];
  if (typeof refAvatar === "string" && looksLikeImageUrl(refAvatar)) {
    return (
      <span className="flex min-w-0 items-center gap-2">
        <img
          src={refAvatar}
          alt=""
          loading="lazy"
          className="size-6 shrink-0 rounded-full border border-border object-cover"
        />
        <span className="truncate text-foreground">{displayCellValue(raw, col)}</span>
      </span>
    );
  }
  // A status-coloured enum value: the @EnumLabel(color = …) hex rides as {col}_color (RefResolver),
  // so paint the label as a pill the colour of the spreadsheet cell it replaces.
  const pill = enumPillStyle(row[`${col.columnName}_color`] as string | undefined);
  if (pill && raw) {
    return (
      <span className="flex min-w-0">
        <span
          className="truncate rounded-control px-2 py-0.5 text-xs font-medium"
          style={pill}
        >
          {displayCellValue(raw, col)}
        </span>
      </span>
    );
  }
  return <span className="truncate text-foreground">{displayCellValue(raw, col)}</span>;
}

// Mirror of the server's snake-casing so an SSE event's entity name matches the route name.
function toSnake(name: string): string {
  return name.replace(/ /g, "").replace(/([a-z0-9])([A-Z])/g, "$1_$2").toLowerCase();
}
function eventMatches(event: UiEvent, kind: string, name: string): boolean {
  if (!event || event.type === "ready") return false;
  // catalogs→"catalog", documents→"document", registers→"register" (posting emits register changes).
  const singular = kind === "catalogs" ? "catalog" : kind === "registers" ? "register" : "document";
  return event.entityType === singular && (event.entityName === "*" || toSnake(event.entityName ?? "") === name);
}

/**
 * Shared look for a faceted-filter trigger (a shadcn/Linear-style filter "chip"): a pill that reads
 * as an inert prompt while empty (dashed border, muted, a leading +) and as a committed selection
 * once it carries a value (solid accented border, foreground text, the picked value(s) shown inline
 * as small badges). One consistent control replaces the old "Label: <raw control>" inline pairs.
 */
const facetTriggerCls = (active: boolean) =>
  cn(
    "inline-flex h-8 shrink-0 items-center gap-1.5 rounded-control border px-3 text-xs font-medium outline-none transition-colors focus-visible:ring-2 focus-visible:ring-ring focus-visible:ring-offset-1 focus-visible:ring-offset-background",
    active
      ? "border-solid border-primary/40 bg-primary/5 text-foreground hover:bg-primary/10"
      : "border-dashed border-input bg-transparent text-muted-foreground hover:border-solid hover:border-input hover:bg-accent hover:text-foreground"
  );

// Options-facet lists longer than this get a search row; shorter ones stay a plain scan.
const FACET_SEARCH_THRESHOLD = 8;

/** A thin vertical rule between a facet's label and its selected-value badges. */
function FacetDivider() {
  return <span aria-hidden="true" className="mx-0.5 h-3.5 w-px shrink-0 bg-border" />;
}

/** A selected value rendered inside a facet trigger; an enum option's color tints it like the pills. */
function FacetValueBadge({ color, children }: { color?: string; children: ReactNode }) {
  const pill = enumPillStyle(color);
  return (
    <Badge
      variant="secondary"
      className="rounded-control border-transparent bg-accent px-1 font-normal text-foreground"
      style={pill ?? undefined}
    >
      {children}
    </Badge>
  );
}

/**
 * Hover hint for a facet chip. Wraps the chip's PopoverTrigger (Radix slots compose onto the same
 * button), replacing the native {@code title} attribute so every chip explains itself the same way.
 */
function FacetTip({ hint, children }: { hint: string; children: ReactNode }) {
  return (
    <TooltipProvider delayDuration={300}>
      <Tooltip>
        <TooltipTrigger asChild>{children}</TooltipTrigger>
        <TooltipContent side="bottom">{hint}</TooltipContent>
      </Tooltip>
    </TooltipProvider>
  );
}

/**
 * A single- or multi-select facet. A popover of options with a check on each chosen row; the trigger
 * shows the picked labels inline (up to two, then a "{n} selected" summary). Single-select replaces
 * the prior value and closes; multi-select toggles and stays open. Values are keyed by {@code value}
 * (what the query binds); labels are what the user reads. Empty → no constraint.
 */
function OptionsFacet({
  label,
  options,
  multi,
  selected,
  onChange,
}: {
  label: string;
  options: FilterOption[];
  multi: boolean;
  selected: string[];
  onChange: (next: string[]) => void;
}) {
  const t = useMessages();
  const [open, setOpen] = useState(false);
  const touch = useTouchLayout();
  const optionOf = (value: string) => options.find((o) => o.value === value);
  const active = selected.length > 0;
  // A long option list gets a search row (label match, case-insensitive); short lists stay a
  // plain scan — an input over 4 statuses is noise. Query resets on close so reopening shows all.
  const [query, setQuery] = useState("");
  const searchable = options.length > FACET_SEARCH_THRESHOLD;
  const q = query.trim().toLowerCase();
  const visible = searchable && q ? options.filter((o) => o.label.toLowerCase().includes(q)) : options;
  const close = (next: boolean) => {
    setOpen(next);
    if (!next) setQuery("");
  };
  const toggle = (value: string) => {
    if (multi) {
      onChange(selected.includes(value) ? selected.filter((v) => v !== value) : [...selected, value]);
    } else {
      onChange(selected.includes(value) ? [] : [value]);
      close(false);
    }
  };

  const trigger = (
    <button type="button" className={facetTriggerCls(active)} onClick={touch ? () => setOpen(true) : undefined}>
      <ListFilter className="size-3.5 shrink-0 opacity-60" />
      <span className="whitespace-nowrap">{label}</span>
      {active ? (
        <>
          <FacetDivider />
          {selected.length <= 2 ? (
            selected.map((v) => (
              <FacetValueBadge key={v} color={optionOf(v)?.color}>
                {optionOf(v)?.label ?? v}
              </FacetValueBadge>
            ))
          ) : (
            <FacetValueBadge>{t("list.selected", { count: selected.length })}</FacetValueBadge>
          )}
        </>
      ) : null}
    </button>
  );

  // Phones/tablets: a bottom sheet of 44px check rows instead of a small anchored popover.
  if (touch) {
    return (
      <>
        {trigger}
        {open ? (
          <FacetSheet
            label={label}
            onClose={() => close(false)}
            footer={
              <>
                <SheetClearButton
                  onClick={() => {
                    onChange([]);
                    close(false);
                  }}
                >
                  {t("list.clear")}
                </SheetClearButton>
                <SheetDoneButton onClick={() => close(false)}>{t("list.done")}</SheetDoneButton>
              </>
            }
          >
            <div className="px-2 py-2">
              {searchable ? (
                <div className="mx-1 mb-1.5 flex items-center gap-2 rounded-field bg-muted px-3">
                  <Search className="size-4 shrink-0 opacity-50" />
                  <input
                    type="text"
                    value={query}
                    onChange={(e) => setQuery(e.target.value)}
                    placeholder={t("list.search")}
                    className="h-10 w-full bg-transparent text-[15px] outline-none placeholder:text-muted-foreground"
                  />
                </div>
              ) : null}
              {searchable && visible.length === 0 ? (
                <div className="px-3 py-4 text-center text-sm text-muted-foreground">
                  {t("empty.noMatches")}
                </div>
              ) : null}
              {visible.map((o) => {
                const on = selected.includes(o.value);
                return (
                  <button
                    key={o.value}
                    type="button"
                    onClick={() => toggle(o.value)}
                    className="flex min-h-11 w-full items-center gap-3 rounded-field px-3 py-2 text-left text-[15px] transition-colors active:bg-accent"
                  >
                    <span
                      className={cn(
                        "flex size-5 shrink-0 items-center justify-center rounded-[5px] border transition-colors",
                        on ? "border-primary bg-primary text-primary-foreground" : "border-input"
                      )}
                    >
                      {on ? <Check className="size-3.5" /> : null}
                    </span>
                    {o.color ? (
                      <span
                        aria-hidden="true"
                        className="size-2.5 shrink-0 rounded-full"
                        style={{ backgroundColor: o.color }}
                      />
                    ) : null}
                    <span className="truncate">{o.label}</span>
                  </button>
                );
              })}
            </div>
          </FacetSheet>
        ) : null}
      </>
    );
  }

  return (
    <Popover open={open} onOpenChange={close}>
      <FacetTip hint={t("list.filterHint", { label })}>
        <PopoverTrigger asChild>{trigger}</PopoverTrigger>
      </FacetTip>
      <PopoverContent align="start" className="w-64 p-1">
        {searchable ? (
          <div className="mb-1 flex items-center gap-2 border-b border-border px-2 pb-1.5 pt-1">
            <Search className="size-3.5 shrink-0 opacity-50" />
            <input
              type="text"
              autoFocus
              value={query}
              onChange={(e) => setQuery(e.target.value)}
              placeholder={t("list.search")}
              className="h-6 w-full bg-transparent text-sm outline-none placeholder:text-muted-foreground"
            />
          </div>
        ) : null}
        <div className="max-h-72 overflow-y-auto">
          {searchable && visible.length === 0 ? (
            <div className="px-2 py-3 text-center text-xs text-muted-foreground">
              {t("empty.noMatches")}
            </div>
          ) : null}
          {visible.map((o) => {
            const on = selected.includes(o.value);
            return (
              <button
                key={o.value}
                type="button"
                onClick={() => toggle(o.value)}
                className="flex w-full cursor-pointer items-center gap-2 rounded-field px-2 py-1.5 text-left text-sm hover:bg-accent"
              >
                <span
                  className={cn(
                    "flex size-4 shrink-0 items-center justify-center rounded-[4px] border transition-colors",
                    on ? "border-primary bg-primary text-primary-foreground" : "border-input"
                  )}
                >
                  {on ? <Check className="size-3" /> : null}
                </span>
                {o.color ? (
                  <span
                    aria-hidden="true"
                    className="size-2 shrink-0 rounded-full"
                    style={{ backgroundColor: o.color }}
                  />
                ) : null}
                <span className="truncate">{o.label}</span>
              </button>
            );
          })}
        </div>
        {active ? (
          <>
            <div className="my-1 h-px bg-border" />
            <button
              type="button"
              onClick={() => onChange([])}
              className="w-full rounded-field px-2 py-1.5 text-center text-xs text-muted-foreground hover:bg-accent"
            >
              {t("list.clear")}
            </button>
          </>
        ) : null}
      </PopoverContent>
    </Popover>
  );
}

/**
 * A field-scoped text-match facet: the same chip grammar as the other facets (an inline filled input
 * here used to read as a second search box). The chip opens a popover holding the debounced input —
 * keystrokes commit (and re-query) after a short pause, so a contains/starts-with filter over a
 * high-cardinality column doesn't refetch on every character; Enter commits at once and closes.
 */
function TextFacet({
  label,
  value,
  onCommit,
}: {
  label: string;
  value: string;
  onCommit: (next: string) => void;
}) {
  const t = useMessages();
  const [open, setOpen] = useState(false);
  const [text, setText] = useState(value);
  // Keep the input in sync if the committed value is reset externally (e.g. a clear-all).
  useEffect(() => setText(value), [value]);
  useEffect(() => {
    const id = window.setTimeout(() => {
      if (text !== value) onCommit(text);
    }, 300);
    return () => window.clearTimeout(id);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [text]);
  const active = !!value;
  const touch = useTouchLayout();

  const trigger = (
    <button type="button" className={facetTriggerCls(active)} onClick={touch ? () => setOpen(true) : undefined}>
      <Search className="size-3.5 shrink-0 opacity-60" />
      <span className="whitespace-nowrap">{label}</span>
      {active ? (
        <>
          <FacetDivider />
          <FacetValueBadge>
            <span className="max-w-28 truncate">{value}</span>
          </FacetValueBadge>
        </>
      ) : null}
    </button>
  );

  // Phones/tablets: a bottom sheet with a full-width input and Clear/Done — a popover input
  // under the software keyboard is unusable.
  if (touch) {
    return (
      <>
        {trigger}
        {open ? (
          <FacetSheet
            label={label}
            onClose={() => setOpen(false)}
            footer={
              <>
                <SheetClearButton
                  onClick={() => {
                    setText("");
                    onCommit("");
                    setOpen(false);
                  }}
                >
                  {t("list.clear")}
                </SheetClearButton>
                <SheetDoneButton
                  onClick={() => {
                    onCommit(text);
                    setOpen(false);
                  }}
                >
                  {t("list.done")}
                </SheetDoneButton>
              </>
            }
          >
            <div className="relative px-4 py-3">
              <Search className="pointer-events-none absolute left-7 top-1/2 size-4 -translate-y-1/2 text-muted-foreground" aria-hidden="true" />
              <Input
                autoFocus
                value={text}
                placeholder={label}
                onChange={(e) => setText(e.target.value)}
                onKeyDown={(e) => {
                  if (e.key === "Enter") {
                    onCommit(text);
                    setOpen(false);
                  }
                }}
                className="h-11 rounded-field pl-10 text-[15px]"
              />
            </div>
          </FacetSheet>
        ) : null}
      </>
    );
  }

  return (
    <Popover open={open} onOpenChange={setOpen}>
      <FacetTip hint={t("list.filterHint", { label })}>
        <PopoverTrigger asChild>{trigger}</PopoverTrigger>
      </FacetTip>
      <PopoverContent align="start" className="w-64 p-2">
        <div className="relative">
          <Search className="pointer-events-none absolute left-2.5 top-1/2 size-3.5 -translate-y-1/2 text-muted-foreground" aria-hidden="true" />
          <Input
            autoFocus
            value={text}
            placeholder={label}
            onChange={(e) => setText(e.target.value)}
            onKeyDown={(e) => {
              if (e.key === "Enter") {
                onCommit(text);
                setOpen(false);
              }
            }}
            className="h-8 rounded-field pl-8 text-xs"
          />
        </div>
        {active ? (
          <button
            type="button"
            onClick={() => {
              setText("");
              onCommit("");
              setOpen(false);
            }}
            className="mt-1 w-full rounded-field px-2 py-1.5 text-center text-xs text-muted-foreground hover:bg-accent"
          >
            {t("list.clear")}
          </button>
        ) : null}
      </PopoverContent>
    </Popover>
  );
}

/** Short, locale-aware day label ("May 9") for a stored ISO date, shown inside the date-range chip. */
function fmtDay(iso: string): string {
  try {
    return new Intl.DateTimeFormat(undefined, { month: "short", day: "numeric" }).format(
      new Date(`${iso}T00:00`)
    );
  } catch {
    return iso;
  }
}

// (FacetSheet, SheetClearButton, SheetDoneButton, useTouchLayout live in ui/facet-sheet — the
// dashboard's TimeRangeFacet shares the same touch shell.)

/**
 * A date-range facet: one chip that opens a popover with quick presets (Today, Last 7 days, This
 * month, …) beside a two-month range calendar — the pattern every analytics tool (Grafana, Stripe,
 * Linear) uses. Replaces the old two bare date inputs. State is the same {from,to} ISO pair the
 * query binds to {@code ge}/{@code le}; presets and the calendar both write both ends at once.
 */
function DateRangeFacet({
  label,
  from,
  to,
  onChange,
}: {
  label: string;
  from: string;
  to: string;
  onChange: (next: { from: string; to: string }) => void;
}) {
  const t = useMessages();
  const [open, setOpen] = useState(false);
  const active = !!from && !!to;
  // filterState ISO strings ↔ the calendar's {start,end} CalendarDate range.
  const range = (() => {
    if (!from || !to) return null;
    try {
      return { start: parseDate(from), end: parseDate(to) };
    } catch {
      return null;
    }
  })();
  const setRange = (start: CalendarDate, end: CalendarDate) =>
    onChange({ from: start.toString(), to: end.toString() });
  const now = today(getLocalTimeZone());
  const presets: { label: string; start: CalendarDate; end: CalendarDate }[] = [
    { label: t("list.dateToday"), start: now, end: now },
    { label: t("list.dateYesterday"), start: now.subtract({ days: 1 }), end: now.subtract({ days: 1 }) },
    { label: t("list.dateLast7"), start: now.subtract({ days: 6 }), end: now },
    { label: t("list.dateLast30"), start: now.subtract({ days: 29 }), end: now },
    { label: t("list.dateThisMonth"), start: startOfMonth(now), end: now },
    { label: t("list.dateThisYear"), start: startOfYear(now), end: now },
  ];
  const touch = useTouchLayout();

  const trigger = (
    <button type="button" className={facetTriggerCls(active)} onClick={touch ? () => setOpen(true) : undefined}>
      <CalendarDays className="size-3.5 shrink-0 opacity-60" />
      <span className="whitespace-nowrap">{label}</span>
      {active ? (
        <>
          <FacetDivider />
          <FacetValueBadge>
            {fmtDay(from)} – {fmtDay(to)}
          </FacetValueBadge>
        </>
      ) : null}
    </button>
  );

  // Phones/tablets: a bottom sheet — preset chips, one finger-sized month, a sticky
  // Clear/Done footer — instead of an anchored two-month popover a thumb can't use.
  if (touch) {
    return (
      <>
        {trigger}
        {open ? (
          <FacetSheet
            label={label}
            onClose={() => setOpen(false)}
            footer={
              <>
                <SheetClearButton
                  onClick={() => {
                    onChange({ from: "", to: "" });
                    setOpen(false);
                  }}
                >
                  {t("list.clear")}
                </SheetClearButton>
                <SheetDoneButton onClick={() => setOpen(false)}>{t("list.done")}</SheetDoneButton>
              </>
            }
          >
            <div className="grid grid-cols-3 gap-2 px-4 pt-3">
              {presets.map((p) => (
                <button
                  key={p.label}
                  type="button"
                  onClick={() => {
                    setRange(p.start, p.end);
                    setOpen(false);
                  }}
                  className="h-9 rounded-field border border-input px-2 text-xs font-medium text-foreground transition-colors active:bg-accent"
                >
                  {p.label}
                </button>
              ))}
            </div>
            <div className="flex justify-center">
              <RangeCalendar
                aria-label={label}
                value={range}
                numberOfMonths={1}
                touch
                onChange={(v) => {
                  if (v) setRange(v.start as CalendarDate, v.end as CalendarDate);
                }}
              />
            </div>
          </FacetSheet>
        ) : null}
      </>
    );
  }

  return (
    <Popover open={open} onOpenChange={setOpen}>
      <FacetTip hint={t("list.filterHint", { label })}>
        <PopoverTrigger asChild>{trigger}</PopoverTrigger>
      </FacetTip>
      <PopoverContent align="start" className="w-auto p-0">
        <div className="flex flex-col sm:flex-row">
          <div className="flex flex-row gap-1 border-b p-2 sm:flex-col sm:border-b-0 sm:border-r">
            {presets.map((p) => (
              <button
                key={p.label}
                type="button"
                onClick={() => {
                  setRange(p.start, p.end);
                  setOpen(false);
                }}
                className="whitespace-nowrap rounded-field px-2.5 py-1.5 text-left text-xs text-foreground hover:bg-accent"
              >
                {p.label}
              </button>
            ))}
          </div>
          <div>
            <RangeCalendar
              aria-label={label}
              value={range}
              onChange={(v) => {
                if (v) setRange(v.start as CalendarDate, v.end as CalendarDate);
              }}
            />
            {active ? (
              <div className="flex justify-end border-t px-3 py-2">
                <button
                  type="button"
                  onClick={() => {
                    onChange({ from: "", to: "" });
                    setOpen(false);
                  }}
                  className="rounded-field px-2 py-1 text-xs text-muted-foreground hover:bg-accent hover:text-foreground"
                >
                  {t("list.clear")}
                </button>
              </div>
            ) : null}
          </div>
        </div>
      </PopoverContent>
    </Popover>
  );
}

/**
 * The group-by control as a facet chip — the same pill grammar as the filters (dashed prompt when
 * off, accent-tinted with the picked column shown as a badge when on) instead of an inline labelled
 * {@code <Select>}, so the whole bar speaks one visual language. The popover lists the groupable
 * columns radio-style; picking a date column keeps the popover open and reveals a Day/Month/Year
 * granularity segment.
 */
function GroupByFacet({
  groupable,
  groupBy,
  onGroupBy,
  granularity,
  onGranularity,
}: {
  groupable: GroupableColumn[];
  groupBy: string;
  onGroupBy: (v: string) => void;
  granularity: string;
  onGranularity: (v: string) => void;
}) {
  const t = useMessages();
  const [open, setOpen] = useState(false);
  const col = groupable.find((g) => g.columnName === groupBy);
  const granLabels: Record<string, string> = {
    day: t("list.granDay"),
    month: t("list.granMonth"),
    year: t("list.granYear"),
  };
  const touch = useTouchLayout();
  const choose = (v: string) => {
    onGroupBy(v);
    // A date column stays open so the granularity segment (revealed below) is one click away.
    if (!groupable.find((g) => g.columnName === v)?.date) setOpen(false);
  };

  const trigger = (
    <button type="button" className={facetTriggerCls(!!groupBy)} onClick={touch ? () => setOpen(true) : undefined}>
      <Rows3 className="size-3.5 shrink-0 opacity-60" />
      <span className="whitespace-nowrap">{t("list.groupBy")}</span>
      {col ? (
        <>
          <FacetDivider />
          <FacetValueBadge>{col.label}</FacetValueBadge>
          {col.date && granLabels[granularity] ? (
            <FacetValueBadge>{granLabels[granularity]}</FacetValueBadge>
          ) : null}
        </>
      ) : null}
    </button>
  );

  // Phones/tablets: a bottom sheet of 44px radio rows; a date column reveals a large
  // Day/Month/Year segment above the Done footer.
  if (touch) {
    return (
      <>
        {trigger}
        {open ? (
          <FacetSheet
            label={t("list.groupBy")}
            onClose={() => setOpen(false)}
            footer={<SheetDoneButton onClick={() => setOpen(false)}>{t("list.done")}</SheetDoneButton>}
          >
            <div className="px-2 py-2">
              {[{ columnName: "", label: t("list.groupNone"), date: false }, ...groupable].map((g) => {
                const on = (groupBy || "") === g.columnName;
                return (
                  <button
                    key={g.columnName || "__none"}
                    type="button"
                    onClick={() => choose(g.columnName)}
                    className="flex min-h-11 w-full items-center gap-3 rounded-field px-3 py-2 text-left text-[15px] transition-colors active:bg-accent"
                  >
                    <span
                      className={cn(
                        "flex size-5 shrink-0 items-center justify-center rounded-full border transition-colors",
                        on ? "border-primary bg-primary text-primary-foreground" : "border-input"
                      )}
                    >
                      {on ? <Check className="size-3.5" /> : null}
                    </span>
                    <span className="truncate">{g.label}</span>
                  </button>
                );
              })}
            </div>
            {col?.date ? (
              <div className="flex items-center gap-2 border-t px-4 py-3">
                {(["day", "month", "year"] as const).map((g) => (
                  <button
                    key={g}
                    type="button"
                    onClick={() => onGranularity(g)}
                    className={cn(
                      "h-10 flex-1 rounded-control text-sm font-medium transition-colors",
                      granularity === g
                        ? "bg-accent text-foreground"
                        : "text-muted-foreground active:bg-accent/60"
                    )}
                  >
                    {granLabels[g]}
                  </button>
                ))}
              </div>
            ) : null}
          </FacetSheet>
        ) : null}
      </>
    );
  }

  return (
    <Popover open={open} onOpenChange={setOpen}>
      <FacetTip hint={t("list.groupByHint")}>
        <PopoverTrigger asChild>{trigger}</PopoverTrigger>
      </FacetTip>
      <PopoverContent align="start" className="w-64 p-1">
        <div className="max-h-72 overflow-y-auto">
          {[{ columnName: "", label: t("list.groupNone"), date: false }, ...groupable].map((g) => {
            const on = (groupBy || "") === g.columnName;
            return (
              <button
                key={g.columnName || "__none"}
                type="button"
                onClick={() => choose(g.columnName)}
                className="flex w-full cursor-pointer items-center gap-2 rounded-field px-2 py-1.5 text-left text-sm hover:bg-accent"
              >
                <span
                  className={cn(
                    "flex size-4 shrink-0 items-center justify-center rounded-full border transition-colors",
                    on ? "border-primary bg-primary text-primary-foreground" : "border-input"
                  )}
                >
                  {on ? <Check className="size-3" /> : null}
                </span>
                <span className="truncate">{g.label}</span>
              </button>
            );
          })}
        </div>
        {col?.date ? (
          <>
            <div className="my-1 h-px bg-border" />
            <div className="flex items-center gap-1 p-1">
              {(["day", "month", "year"] as const).map((g) => (
                <button
                  key={g}
                  type="button"
                  onClick={() => onGranularity(g)}
                  className={cn(
                    "flex-1 rounded-control px-2 py-1 text-xs font-medium transition-colors",
                    granularity === g
                      ? "bg-accent text-foreground"
                      : "text-muted-foreground hover:bg-accent/60 hover:text-foreground"
                  )}
                >
                  {granLabels[g]}
                </button>
              ))}
            </div>
          </>
        ) : null}
      </PopoverContent>
    </Popover>
  );
}

// A window bigger than one page cannot exceed the server's list ceiling.
const MAX_WINDOW = 500;
// Extra rows rendered above/below the viewport in infinite mode, so a fast scroll never flashes blank.
const OVERSCAN = 8;

export function EntityListWidget({
  list,
  headerExtra,
}: {
  list: ListDescriptor;
  // Host-provided control rendered in the control island right after the title — the register
  // surface parks its Balance/Movements toggle here so the view switch lives with the list's own
  // controls instead of floating above the card.
  headerExtra?: ReactNode;
}) {
  const { kind, name, columns, pageSize } = list;
  // Feed mode: "infinite" cursor-scrolls a keyset stream (the default); "paged" shows numbered
  // offset pages. The two drive different server engines (cursor vs offset) — see loadInitial.
  const feedMode = list.feedMode === "paged" ? "paged" : "infinite";
  const t = useMessages();
  // Ambient presence: other users viewing each row's record, looked up by row id at render time.
  const viewersById = useViewersById();
  const rootRef = useRef<HTMLDivElement | null>(null);
  const scrollRef = useRef<HTMLDivElement | null>(null);
  const gen = useRef(0); // bumped on each query so a stale window fetch is ignored

  // ---- URL-persisted view state (standalone list routes only) ----
  // A non-embedded list owns its route, so we mirror its search / sort / filters / view into the
  // query string: the view then survives an island remount (a theme switch re-inits DivKit and
  // remounts every island, which used to wipe the filters) and a filtered list becomes a shareable,
  // bookmarkable link. Embedded lists (dashboard cards, master-detail panes) keep purely local state
  // — several of them on one page would collide on the single shared URL.
  const urlSynced = !list.embedded;
  // Captured once at mount; used only to seed the initial state below.
  const initialParams = useMemo(
    () => new URLSearchParams(urlSynced ? window.location.search : ""),
    // eslint-disable-next-line react-hooks/exhaustive-deps
    []
  );

  // The currently displayed rows (null = first load, still blank). In paged mode this is exactly the
  // active page; in infinite mode windows are appended here as you scroll.
  const [pageRows, setPageRows] = useState<EntityRecord[] | null>(null);
  const rowsRef = useRef<EntityRecord[] | null>(null);
  rowsRef.current = pageRows;
  // The exact total (paged; also a cheap estimate in infinite mode when the server can supply one),
  // or null when unknown — infinite mode never blocks on a COUNT.
  const [total, setTotal] = useState<number | null>(null);
  const [page, setPage] = useState(0); // paged mode only
  const pageRef = useRef(0);
  pageRef.current = page;
  // Infinite mode: the opaque cursor for the next window, whether more remain, and whether a
  // load-more is in flight (the ref guards against firing a second fetch before the first lands).
  const cursorRef = useRef<string | null>(null);
  const [hasMore, setHasMore] = useState(true);
  const [loadingMore, setLoadingMore] = useState(false);
  const loadingMoreRef = useRef(false);
  // The island can be squeezed narrow (e.g. a master-detail split) while the viewport stays wide,
  // so the toolbar layout is driven by the measured container width, not a media query.
  const [toolbarWidth, setToolbarWidth] = useState<number | null>(null);
  // Route-surface height: the island fills the space to the window bottom and scrolls internally,
  // so the page (its DivKit container) never scrolls — only the list body does. Null until measured
  // (and unused in embedded mode, where the host page owns scrolling).
  const [surfaceH, setSurfaceH] = useState<number | null>(null);
  // Embedded-mode fallback: cap the scroll body so a big in-page list scrolls internally instead of
  // stretching the host page unboundedly.
  const [maxBodyH, setMaxBodyH] = useState(420);
  // Scroll position + viewport height of the body, for the infinite-mode virtual window.
  const [scrollTop, setScrollTop] = useState(0);
  const [viewportH, setViewportH] = useState(0);
  // Sort + search seed from the URL on a standalone list (see urlSynced), so a shared/bookmarked link
  // restores them; an embedded list starts from the descriptor default.
  const [sort, setSort] = useState<{ column: string | null; descending: boolean }>(() => {
    const col = urlSynced ? initialParams.get("sort") : null;
    return col ? { column: col, descending: initialParams.get("dir") === "desc" } : list.sort;
  });
  const [query, setQuery] = useState(() => (urlSynced ? initialParams.get("q") ?? "" : ""));
  const [debounced, setDebounced] = useState(() => (urlSynced ? initialParams.get("q") ?? "" : ""));
  // Grouping: which column the list is grouped by ("" = flat), and — for a date column — the bucket
  // granularity. Picking a column swaps the flat table for the collapsible grouped view.
  const groupable = useMemo(() => list.groupable ?? [], [list.groupable]);
  const aggregates = useMemo(() => list.aggregates ?? [], [list.aggregates]);
  const [groupBy, setGroupBy] = useState("");
  const [granularity, setGranularity] = useState("month");
  const groupCol = groupable.find((g) => g.columnName === groupBy);
  // Keys of in-flight server actions (`key` for toolbar, `key:id` for a row) → spinner + disabled.
  const [pending, setPending] = useState<Set<string>>(new Set());
  // Batch selection: row ids picked with ⌘/Ctrl-click (toggle) and Shift-click (range from the
  // anchor). Plain click keeps its open-the-record meaning; with a selection active it clears the
  // selection instead. Esc or the toolbar ✕ clears too.
  const [selected, setSelected] = useState<Set<string>>(new Set());
  const selAnchorRef = useRef<number | null>(null);
  // The row right-click menu this island owns (built-ins + custom actions + batch ops). The
  // native event is preventDefault-ed so divkit-view's DOM-sniffing fallback menu stays quiet.
  const [rowMenu, setRowMenu] = useState<{ x: number; y: number; id: string; url: string; row: EntityRecord } | null>(null);
  // Two-step batch delete: first click arms ("sure?"), second click runs.
  const [armedDelete, setArmedDelete] = useState(false);
  // Table vs map vs custom view (each alternative only offered when the list declares it). The map
  // fetches its own rows, so the grid's search/filters/sort are hidden while it's shown; a custom
  // renderer shares the grid's feed, so the toolbar stays live.
  const [view, setView] = useState<"table" | "map" | "custom">(() => {
    if (urlSynced && (list.map || list.custom)) {
      const v = initialParams.get("view");
      if (v === "map" && list.map) return "map";
      if (v === "custom" && list.custom) return "custom";
      if (v === "table") return "table";
    }
    if (list.custom?.defaultView) return "custom";
    return list.map?.defaultView ? "map" : "table";
  });
  const mapMode = !!list.map && view === "map";
  // The custom body renderer, resolved live from the widget registry: a plugin registering after
  // mount re-resolves (the version store bumps), and an unregistered type stays undefined — the
  // list degrades to the default grid rather than failing (no toggle, no blank body).
  const registryVersion = useSyncExternalStore(subscribeRegistry, getRegistryVersion);
  const customType = list.custom?.type ?? "";
  const CustomRenderer = useMemo(
    () => (customType ? (resolveWidget(customType) as ComponentType<ListRendererProps> | undefined) : undefined),
    // eslint-disable-next-line react-hooks/exhaustive-deps
    [customType, registryVersion]
  );
  const customMode = view === "custom" && !!CustomRenderer;
  // Grouped view replaces the flat table (never shown together with the map or a custom body).
  const grouped = !!groupBy && !mapMode && !customMode;
  // Catalog/document rows open their record; register rows (movements/balance) have no detail route,
  // so they aren't clickable. Also gates the single-row live patch (registers have no row identity).
  const openable = kind === "catalogs" || kind === "documents";
  // RBAC write access (see ListDescriptor.canWrite): gates the row menu's Edit/Duplicate/Delete
  // and batch delete. Absent flag (old server) stays permissive; REST enforces regardless.
  const canWrite = list.canWrite !== false;
  // Where windows are fetched from: a register points the island at its own feed; others use the
  // standard /api/list/{kind}/{name} route.
  const feedBase = list.feed ?? `/api/list/${kind}/${name}`;
  // Route surface (own page) vs embedded in an authored dashboard page. Only the route surface takes
  // over the viewport height and scrolls internally; embedded flows with the host page.
  const surfaceMode = !list.embedded;

  const allActions = list.actions ?? [];
  const toolbarActions = allActions.filter((a) => a.scope === "toolbar");
  const rowActions = allActions.filter((a) => a.scope === "row");
  // Only actions without a .menu("…") placement render as inline row icon buttons; the rest live in
  // the row's right-click menu (grouped under their submenu label, in declaration order).
  const rowButtonActions = rowActions.filter((a) => !a.menu);
  const rowSubmenus = useMemo(() => {
    const order: string[] = [];
    const byMenu = new Map<string, ListAction[]>();
    for (const a of rowActions) {
      if (!a.menu) continue;
      if (!byMenu.has(a.menu)) {
        byMenu.set(a.menu, []);
        order.push(a.menu);
      }
      byMenu.get(a.menu)!.push(a);
    }
    return order.map((label) => ({ label, actions: byMenu.get(label)! }));
  }, [rowActions]);
  const toolbarInputs = list.inputs ?? [];
  // Stable reference so the fetch/reset effects don't churn when there are no filters.
  const filters = useMemo(() => list.filters ?? [], [list.filters]);

  // Rebuild filter state from the URL (each filter's `f.<key>` param, mirroring the write below).
  // Unknown/blank params yield an empty {} for that filter, i.e. no constraint.
  const readFiltersFromUrl = useCallback(
    (params: URLSearchParams) =>
      Object.fromEntries(
        filters.map((f) => {
          const p = `f.${f.key}`;
          if (f.type === "dateRange") {
            const from = params.get(`${p}.from`) ?? "";
            const to = params.get(`${p}.to`) ?? "";
            return [f.key, from || to ? { from, to } : {}];
          }
          if (f.type === "multiOptions") {
            const vals = params.getAll(p);
            return [f.key, vals.length ? { in: vals } : {}];
          }
          if (f.type === "contains" || f.type === "startsWith") {
            const text = params.get(p) ?? "";
            return [f.key, text ? { text } : {}];
          }
          const eq = params.get(p) ?? "";
          return [f.key, eq ? { eq } : {}];
        })
      ),
    [filters]
  );

  // Current value of each declarative filter: {eq} for an options filter, {in} (picked values) for a
  // multi-select, {text} for a contains/starts-with typeahead, {from,to} for a date range. Changing
  // one resets the grid and re-queries (see the reset effect below) — it narrows the rows, not just
  // the toolbar. Serialized into filterSig so effects can depend on its content.
  const [filterState, setFilterState] = useState<
    Record<string, { eq?: string; in?: string[]; text?: string; from?: string; to?: string }>
  >(() =>
    urlSynced ? readFiltersFromUrl(initialParams) : Object.fromEntries(filters.map((f) => [f.key, {}]))
  );
  const filterSig = JSON.stringify(filterState);
  const filterStateRef = useRef(filterState);
  filterStateRef.current = filterState;

  // Mirror the applied view state back into the URL with replaceState (no history entry per keystroke,
  // but the browser back button still works at the route level, and the URL is copy-shareable). Only
  // non-default values are written, so an untouched list keeps a clean path. Unrelated query params
  // (anything not `q`/`sort`/`dir`/`view`/`f.*`) are preserved. Uses `debounced` (the applied search),
  // not the raw box, so the URL reflects what's actually queried.
  useEffect(() => {
    if (!urlSynced) return;
    const params = new URLSearchParams(window.location.search);
    const managed = (k: string) =>
      k === "q" || k === "sort" || k === "dir" || k === "view" || k.startsWith("f.");
    for (const k of new Set([...params.keys()].filter(managed))) params.delete(k);
    if (debounced) params.set("q", debounced);
    if (sort.column && (sort.column !== list.sort.column || sort.descending !== list.sort.descending)) {
      params.set("sort", sort.column);
      params.set("dir", sort.descending ? "desc" : "asc");
    }
    const defaultViewName = list.custom?.defaultView ? "custom" : list.map?.defaultView ? "map" : "table";
    const currentViewName = mapMode ? "map" : customMode ? "custom" : "table";
    if (currentViewName !== defaultViewName) params.set("view", currentViewName);
    for (const f of filters) {
      const st = filterStateRef.current[f.key];
      if (!st) continue;
      const p = `f.${f.key}`;
      if (f.type === "dateRange") {
        if (st.from) params.set(`${p}.from`, st.from);
        if (st.to) params.set(`${p}.to`, st.to);
      } else if (f.type === "multiOptions") {
        for (const v of st.in ?? []) params.append(p, v);
      } else if (f.type === "contains" || f.type === "startsWith") {
        if (st.text?.trim()) params.set(p, st.text.trim());
      } else if (st.eq) {
        params.set(p, st.eq);
      }
    }
    const qs = params.toString();
    window.history.replaceState(
      window.history.state,
      "",
      qs ? `${window.location.pathname}?${qs}` : window.location.pathname
    );
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [urlSynced, filterSig, debounced, sort.column, sort.descending, mapMode, customMode]);

  // How many filters currently constrain the query — drives the "Clear all" affordance on the
  // filter bar. A filter counts as active only when it actually narrows rows (a picked option, some
  // typed text, at least one end of a date range), not merely because it's declared.
  const activeFilterCount = useMemo(
    () =>
      filters.reduce((n, f) => {
        const st = filterState[f.key];
        if (!st) return n;
        if (f.type === "dateRange") return n + (st.from || st.to ? 1 : 0);
        if (f.type === "multiOptions") return n + ((st.in?.length ?? 0) > 0 ? 1 : 0);
        if (f.type === "contains" || f.type === "startsWith") return n + (st.text?.trim() ? 1 : 0);
        return n + (st.eq ? 1 : 0);
      }, 0),
    [filters, filterState]
  );
  const clearAllFilters = () =>
    setFilterState(Object.fromEntries(filters.map((f) => [f.key, {}])));

  // Responsive header row, driven by the measured island width and the controls actually present
  // (not a fixed media query). `stacked` puts the title on its own row and the controls below the
  // moment they would no longer fit beside it — so the title never truncates. `compact` (much
  // narrower) additionally drops the button labels to icons. Filters live on their own wrapping bar
  // below, so only the header-row controls (view toggle, search, actions, New) count here.
  const controlsWidthEstimate =
    (list.map ? 150 : 0) +
    (list.custom && CustomRenderer ? 150 : 0) +
    (list.searchable ? 220 : 0) +
    toolbarActions.length * 100 +
    (list.newUrl ? 90 : 0);
  const stacked = toolbarWidth != null && toolbarWidth < controlsWidthEstimate + 170;
  const compact = toolbarWidth != null && toolbarWidth < 560;

  // Live values of the custom toolbar inputs, seeded from their declared defaults. These are sent
  // with every server action so the handler can read them via ActionContext.input(key).
  const [inputValues, setInputValues] = useState<Record<string, string>>(() =>
    Object.fromEntries(toolbarInputs.map((i) => [i.key, i.value ?? ""]))
  );
  const inputValuesRef = useRef(inputValues);
  inputValuesRef.current = inputValues;

  // Column grid template: an authored px width, else a flexible track. A trailing fixed column
  // holds the per-row action buttons when any are declared. The floor is 0 here because the
  // table's overall min-width (below) keeps the columns readable — when the card is narrower
  // than that, the table scrolls horizontally instead of cramming the columns.
  const DATA_COL_MIN = 150;
  const ACTION_COL_W = rowButtonActions.length ? rowButtonActions.length * 36 + 8 : 0;
  const template =
    columns
      .map((c) => {
        const px = parseInt(c.width, 10);
        return Number.isFinite(px) && px > 0 ? `${px}px` : "minmax(0,1fr)";
      })
      .concat(ACTION_COL_W ? [`${ACTION_COL_W}px`] : [])
      .join(" ");

  // When any loaded row has viewers, widen the whole list's left padding so the presence face-pile sits
  // in its own gutter instead of over the first column. Applied to the header too, so columns stay
  // aligned; it collapses back when nobody is viewing (a rare, gentle shift).
  const listHasPresence = openable && (pageRows ?? []).some((r) => r && viewersById.has(String(r._id)));
  const leftPad = listHasPresence ? "pl-11 pr-4" : "px-4";

  // Natural minimum width of the table: each column at its authored width (or DATA_COL_MIN),
  // plus the action column, the 12px inter-column gaps and the 32px (px-4) row padding. When the
  // card is wider, the grid fills it (1fr expands); when narrower, this drives a horizontal scroll.
  const trackCount = columns.length + (ACTION_COL_W ? 1 : 0);
  const minTableWidth =
    columns.reduce((sum, c) => {
      const px = parseInt(c.width, 10);
      return sum + (Number.isFinite(px) && px > 0 ? px : DATA_COL_MIN);
    }, 0) +
    ACTION_COL_W +
    Math.max(0, trackCount - 1) * 12 +
    32;

  // Debounce the search box.
  useEffect(() => {
    const t = window.setTimeout(() => setDebounced(query.trim()), 250);
    return () => window.clearTimeout(t);
  }, [query]);

  // Build the shared query params (sort + search + declarative filters). The pagination params
  // (offset/limit or cursor/limit) are added by the caller, since they differ per feed mode.
  const buildParams = useCallback(() => {
    const params = new URLSearchParams();
    if (sort.column) {
      params.set("sort", sort.column);
      params.set("dir", sort.descending ? "desc" : "asc");
    }
    if (debounced) params.set("q", debounced);
    // Declarative filters → eq/in/like/prefix/ge/le params (column,value); the server re-validates
    // the column. Several filters AND together; a multi-select sends one `in` per picked value.
    for (const f of filters) {
      const st = filterStateRef.current[f.key];
      if (!st) continue;
      if (f.type === "dateRange") {
        if (st.from) params.append("ge", `${f.column},${st.from}`);
        if (st.to) params.append("le", `${f.column},${st.to}`);
      } else if (f.type === "multiOptions") {
        for (const v of st.in ?? []) params.append("in", `${f.column},${v}`);
      } else if (f.type === "contains") {
        if (st.text?.trim()) params.append("like", `${f.column},${st.text.trim()}`);
      } else if (f.type === "startsWith") {
        if (st.text?.trim()) params.append("prefix", `${f.column},${st.text.trim()}`);
      } else if (st.eq) {
        params.append("eq", `${f.column},${st.eq}`);
      }
    }
    return params;
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [sort.column, sort.descending, debounced, filters, filterSig]);

  // The shared query (sort + search + filters) the grouped view fetches groups/rows with, as a
  // string so its effects can depend on it.
  const groupParamsBase = useMemo(() => buildParams().toString(), [buildParams]);

  // Load the first window (infinite) or the active page (paged). Bumps the generation so a slow
  // fetch that lands after a newer query is discarded. A hard load blanks to the skeleton; a soft
  // load (live refresh) keeps the current rows on screen and swaps them in place when data lands.
  const loadInitial = useCallback(
    (soft = false) => {
      const myGen = ++gen.current;
      loadingMoreRef.current = false;
      if (!soft) setPageRows(null);
      const params = buildParams();
      if (feedMode === "paged") {
        params.set("offset", String(pageRef.current * pageSize));
        params.set("limit", String(pageSize));
        fetch(`${feedBase}?${params.toString()}`, { credentials: "include" })
          .then((r) => (r.ok ? r.json() : Promise.reject(new Error(`HTTP ${r.status}`))))
          .then((data: { total: number; offset: number; rows: EntityRecord[] }) => {
            if (myGen !== gen.current) return; // a newer query/page superseded this fetch
            setTotal(data.total);
            setPageRows(data.rows);
            if (!soft && scrollRef.current) scrollRef.current.scrollTop = 0;
          })
          .catch(() => {
            if (myGen === gen.current) setPageRows([]);
          });
        return;
      }
      // Infinite: a soft reload re-fetches everything already loaded in one window (so a live change
      // doesn't yank the user back to the top); a hard load fetches just the first window.
      const loaded = rowsRef.current?.length ?? 0;
      const want = soft ? Math.min(MAX_WINDOW, Math.max(pageSize, loaded)) : pageSize;
      params.set("limit", String(want));
      params.set("count", "estimate"); // cheap approximate total for the header; may be absent
      fetch(`${feedBase}?${params.toString()}`, { credentials: "include" })
        .then((r) => (r.ok ? r.json() : Promise.reject(new Error(`HTTP ${r.status}`))))
        .then((data: { rows: EntityRecord[]; nextCursor: string | null; hasMore: boolean; total?: number }) => {
          if (myGen !== gen.current) return;
          setTotal(typeof data.total === "number" ? data.total : null);
          cursorRef.current = data.nextCursor ?? null;
          setHasMore(!!data.hasMore);
          setPageRows(data.rows ?? []);
          if (!soft && scrollRef.current) scrollRef.current.scrollTop = 0;
        })
        .catch(() => {
          if (myGen === gen.current) setPageRows([]);
        });
    },
    [feedMode, feedBase, pageSize, buildParams]
  );

  // Infinite mode: append the next window, seeking from the current cursor. Guarded so only one
  // load-more runs at a time and a window from a superseded query is dropped.
  const loadMore = useCallback(() => {
    if (feedMode !== "infinite" || loadingMoreRef.current || !cursorRef.current) return;
    loadingMoreRef.current = true;
    setLoadingMore(true);
    const myGen = gen.current;
    const params = buildParams();
    params.set("limit", String(pageSize));
    params.set("cursor", cursorRef.current);
    fetch(`${feedBase}?${params.toString()}`, { credentials: "include" })
      .then((r) => (r.ok ? r.json() : Promise.reject(new Error(`HTTP ${r.status}`))))
      .then((data: { rows: EntityRecord[]; nextCursor: string | null; hasMore: boolean }) => {
        if (myGen !== gen.current) return; // a newer query superseded this window
        cursorRef.current = data.nextCursor ?? null;
        setHasMore(!!data.hasMore);
        setPageRows((cur) => (cur ? [...cur, ...(data.rows ?? [])] : data.rows ?? []));
      })
      .catch(() => {})
      .finally(() => {
        loadingMoreRef.current = false;
        if (myGen === gen.current) setLoadingMore(false);
      });
  }, [feedMode, feedBase, pageSize, buildParams]);

  // Snap back to the first page whenever the sort, the (debounced) search, or a filter changes
  // (paged mode; infinite mode stays at page 0 and resets via the load effect below).
  useEffect(() => {
    setPage(0);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [sort.column, sort.descending, debounced, filterSig]);

  // Load the initial window/page: on first mount, on any query change, and (paged) on a page turn.
  // Reset the infinite cursor so a query change re-seeks from the top rather than an old cursor.
  useEffect(() => {
    cursorRef.current = null;
    setHasMore(true);
    loadInitial(false);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [feedMode, feedBase, pageSize, sort.column, sort.descending, debounced, filterSig, page]);

  // Track the island width so the toolbar can stack / collapse responsively (see below).
  useLayoutEffect(() => {
    const el = rootRef.current;
    if (!el) return;
    const apply = () => setToolbarWidth(el.clientWidth);
    apply();
    const ro = new ResizeObserver(apply);
    ro.observe(el);
    return () => ro.disconnect();
  }, []);

  // Route surface: fill the space from the island's top to the bottom of the page scroller, so the
  // list body scrolls internally and the page (DivKit container) never scrolls. The root then
  // carries its own padding, so the gap under the card matches the top/left gap.
  //
  // The limit is the enclosing scroller's content box, NOT window.innerHeight: the shell keeps
  // padding below the scroller, so sizing to the window overshoots by that padding and leaves the
  // page a few px of scroll play (the whole card wiggles and the gaps look uneven). The offset is
  // computed in the scroller's content coordinates (scroll-invariant), so the height converges to
  // exactly zero page scroll even if measured while the page is scrolled.
  //
  // We must measure our top live, not just once: the workspace tab bar, the sidebar and web-font
  // loading all shift it *after* mount, and the DivKit wrappers around us are `overflow: clip` and
  // sized to our fixed height — so a stale (too-small) top overshoots the viewport and the clip eats
  // the bottom padding (the card then runs flush to the window edge). A re-measure on the next frame
  // and on any shell reflow keeps the height correct, so the bottom gap stays equal to the top.
  useLayoutEffect(() => {
    if (!surfaceMode) {
      setSurfaceH(null);
      return;
    }
    const el = rootRef.current;
    if (!el) return;
    const measure = () => {
      const top = el.getBoundingClientRect().top;
      const sc = nearestScrollAncestor(el);
      if (sc) {
        const offsetInContent = top - sc.getBoundingClientRect().top - sc.clientTop + sc.scrollTop;
        setSurfaceH(Math.max(240, Math.floor(sc.clientHeight - offsetInContent)));
      } else {
        setSurfaceH(Math.max(240, Math.floor(window.innerHeight - top)));
      }
    };
    measure();
    const raf = requestAnimationFrame(measure); // re-measure once layout has settled
    window.addEventListener("resize", measure);
    // Any reflow in the shell can move our top; re-measure. It converges (same top → same height),
    // so this doesn't loop.
    const ro = new ResizeObserver(measure);
    ro.observe(document.body);
    return () => {
      cancelAnimationFrame(raf);
      window.removeEventListener("resize", measure);
      ro.disconnect();
    };
  }, [surfaceMode, stacked]);

  // Embedded mode only: cap the scroll body at the space to the visible bottom of the enclosing
  // scroller (leaving room for the pager footer) so a big in-page list scrolls internally rather
  // than stretching the host page.
  useLayoutEffect(() => {
    if (surfaceMode) return;
    const measure = () => {
      const el = scrollRef.current;
      if (!el) return;
      const top = el.getBoundingClientRect().top;
      const sc = nearestScrollAncestor(el);
      const bottom = sc
        ? sc.getBoundingClientRect().top + sc.clientTop + sc.clientHeight
        : window.innerHeight;
      setMaxBodyH(Math.max(160, Math.floor(bottom - top - 72)));
    };
    measure();
    window.addEventListener("resize", measure);
    const ro = new ResizeObserver(measure);
    if (scrollRef.current?.parentElement) ro.observe(scrollRef.current.parentElement);
    return () => {
      window.removeEventListener("resize", measure);
      ro.disconnect();
    };
    // grouped/mapMode/customMode: the observed body remounts when those views swap back to the
    // table — re-run so the observer isn't left holding the unmounted element (see the viewport
    // effect).
  }, [surfaceMode, grouped, mapMode, customMode]);

  // Keep the virtual-window viewport height in sync with the body's client height. Keyed on the
  // view toggles too: the grouped/map views unmount the table body, and unmount delivers a final
  // 0-height resize for the old element — without re-running here on swap-back, viewportH stays 0
  // and the virtual window renders only OVERSCAN rows ("list cuts off at the 8th row" after
  // grouping and ungrouping), with the rest left as blank padding.
  useLayoutEffect(() => {
    const el = scrollRef.current;
    if (!el) return;
    const apply = () => setViewportH(el.clientHeight);
    apply();
    // The remounted body starts unscrolled — resync the window offset (the state may still hold
    // the pre-swap scroll position, which would blank the first rows).
    setScrollTop(el.scrollTop);
    const ro = new ResizeObserver(apply);
    ro.observe(el);
    return () => ro.disconnect();
  }, [surfaceMode, grouped, mapMode, customMode]);

  // Body scroll: drive the virtual window, and (infinite) fetch the next window as the end nears.
  const onScroll = useCallback(() => {
    const el = scrollRef.current;
    if (!el) return;
    setScrollTop(el.scrollTop);
    if (
      feedMode === "infinite" &&
      hasMore &&
      !loadingMoreRef.current &&
      el.scrollTop + el.clientHeight >= el.scrollHeight - ROW_H * OVERSCAN
    ) {
      loadMore();
    }
  }, [feedMode, hasMore, loadMore]);

  // Re-fetch the loaded rows in place (after a server action or a live event) — a soft refresh that
  // keeps the visible rows until the new data lands, so it never flashes the skeleton.
  const reload = useCallback(() => {
    loadInitial(true);
  }, [loadInitial]);

  // An action-form dialog waiting for input: the clicked form-declaring action plus its target —
  // one row id, a batch of ids, or neither (toolbar). Submitting runs the action with the values.
  const [formPrompt, setFormPrompt] = useState<{ action: ListAction; id?: string; ids?: string[] } | null>(null);
  const [formBusy, setFormBusy] = useState(false);

  // Run a custom action button. A navigation action just routes (filling {id} for a row); a
  // server action POSTs to /api/actions and applies the ActionResult — toast, navigate, refresh.
  // An action declaring a form first opens the modal dialog to collect its fields (formInputs
  // arrive on the second, submitting call). While the (possibly slow/async) handler runs, the
  // button shows a spinner and is disabled, so there's feedback and no double-submit.
  // (api.runAction is CSRF-aware and toasts failures.)
  const runAction = useCallback(
    (action: ListAction, id?: string, formInputs?: Record<string, string>): Promise<void> | void => {
      if (!action.server) {
        if (action.url) dispatchAction(id ? action.url.replace("{id}", id) : action.url);
        return;
      }
      if (action.form?.length && !formInputs) {
        setFormPrompt({ action, id });
        return;
      }
      const k = id ? `${action.key}:${id}` : action.key;
      setPending((s) => {
        if (s.has(k)) return s; // already running — ignore the re-click
        const n = new Set(s);
        n.add(k);
        return n;
      });
      return api
        .runAction(action.kind, action.name, action.key, id, { ...inputValuesRef.current, ...formInputs })
        .then((result) => {
          if (result?.message) toast.success(result.message);
          if (result?.navigate) dispatchAction(result.navigate);
          if (result?.refresh) reload();
        })
        .catch(() => {})
        .finally(() =>
          setPending((s) => {
            if (!s.has(k)) return s;
            const n = new Set(s);
            n.delete(k);
            return n;
          })
        );
    },
    [reload]
  );

  const clearSelection = useCallback(() => {
    setSelected(new Set());
    selAnchorRef.current = null;
  }, []);

  // A changed query invalidates the selection's row set — indices shift, rows drop out.
  useEffect(() => {
    clearSelection();
  }, [kind, name, debounced, filterSig, sort.column, sort.descending, clearSelection]);

  // Esc clears the selection. Captured + consumed: the shell's own Esc handler closes the
  // focused tab (and only yields to an already-defaultPrevented event), so clearing a selection
  // must swallow the key or Esc would clear AND close the surface in one press. An open
  // menu/dialog/drawer is a higher layer — it takes this press (and consumes it itself); the
  // selection clears on the next one. Cascade: layer → selection → tab.
  useEffect(() => {
    if (selected.size === 0) return;
    const onKey = (e: KeyboardEvent) => {
      if (e.key !== "Escape" || e.defaultPrevented) return;
      if (isInteractiveLayerOpen()) return;
      e.preventDefault();
      clearSelection();
    };
    window.addEventListener("keydown", onKey, true);
    return () => window.removeEventListener("keydown", onKey, true);
  }, [selected.size, clearSelection]);

  // One batch runs at a time. A ref (not the pending set) is the re-entrancy guard: a setState
  // updater's side effects aren't guaranteed to run synchronously (React only eager-evaluates the
  // first queued update), so "did I just add the key?" can't be derived from setPending. The
  // pending key still drives the disabled state of the menu items.
  const batchBusyRef = useRef(false);

  // Run a custom server action over every selected row — ONE request: the server's batch endpoint
  // invokes the handler per id and returns {ok, failed, total}, so a 200-row batch isn't 200 HTTP
  // round-trips, survives the tab closing mid-run, and gets a single loading→summary toast here.
  // Per-result navigate doesn't apply — a batch can't open N panes.
  const runBatchAction = useCallback(
    async (action: ListAction, ids: string[], formInputs?: Record<string, string>) => {
      if (action.form?.length && !formInputs) {
        // Collect the form once up front; the submitted values apply to every selected row.
        setFormPrompt({ action, ids });
        return;
      }
      if (batchBusyRef.current) return;
      batchBusyRef.current = true;
      const k = `batch:${action.key}`;
      setPending((s) => new Set(s).add(k));
      const toastId = toast.loading(t("batch.running", { label: action.label, n: ids.length }));
      try {
        const r = await api.runActionBatch(action.kind, action.name, action.key, ids, {
          ...inputValuesRef.current,
          ...formInputs,
        });
        (r.ok === r.total ? toast.success : toast.error)(
          t("batch.done", { label: action.label, ok: r.ok, n: r.total }),
          { id: toastId }
        );
        clearSelection();
        reload();
      } catch {
        // the api layer already toasted the failure — just retire the loading toast
        toast.dismiss(toastId);
      } finally {
        batchBusyRef.current = false;
        setPending((s) => {
          const n = new Set(s);
          n.delete(k);
          return n;
        });
      }
    },
    [reload, clearSelection, t]
  );

  const runBatchDelete = useCallback(
    async (ids: string[]) => {
      if (batchBusyRef.current) return;
      batchBusyRef.current = true;
      const k = "batch:__delete";
      setPending((s) => new Set(s).add(k));
      const toastId = toast.loading(t("batch.running", { label: t("action.delete"), n: ids.length }));
      try {
        const r = await api.batchDelete(kind as "catalogs" | "documents", name, ids);
        (r.ok === r.total ? toast.success : toast.error)(
          t("batch.deleted", { ok: r.ok, n: r.total }),
          { id: toastId }
        );
        clearSelection();
        reload();
      } catch {
        toast.dismiss(toastId);
      } finally {
        batchBusyRef.current = false;
        setPending((s) => {
          const n = new Set(s);
          n.delete(k);
          return n;
        });
      }
    },
    [kind, name, reload, clearSelection, t]
  );

  // Surgically refresh a single changed row in place: fetch just that id (same decorated shape as a
  // page) and swap it where it sits — no cache wipe, no skeleton flash, no scroll jump. If the row
  // isn't in the loaded window we ignore it (it'll load fresh on scroll); if it has left the result
  // set (e.g. deletion-marked via an update) we fall back to a window reload.
  const patchRow = useCallback(
    (id: string) => {
      setPageRows((current) => {
        if (!current || !current.some((r) => r && String(r._id) === id)) return current; // not on this page
        // Fetch just this row (same decorated shape as a page) and splice it in place.
        fetch(`${feedBase}?ids=${encodeURIComponent(id)}`, { credentials: "include" })
          .then((r) => (r.ok ? r.json() : Promise.reject(new Error(`HTTP ${r.status}`))))
          .then((data: { rows: EntityRecord[] }) => {
            const fresh = data.rows?.[0];
            if (fresh) {
              setPageRows((rows) =>
                rows ? rows.map((r) => (String(r._id) === id ? fresh : r)) : rows
              );
            } else {
              reload(); // row dropped out of the set — refresh the page
            }
          })
          .catch(() => {});
        return current;
      });
    },
    [feedBase, reload]
  );

  // Live updates: divkit-view fans out every SSE row change as an "onno:dataevent" window event
  // (one shared stream). An in-place update to an already-loaded row patches just that row; a
  // create/delete (or a wildcard event, or a register change) reloads the visible window — the row
  // count and ordering shift, so a window refresh is the correct, cheap response.
  useEffect(() => {
    const onData = (e: Event) => {
      const event = (e as CustomEvent).detail as UiEvent;
      if (!eventMatches(event, kind, name)) return;
      const surgical =
        openable &&
        !!event.id &&
        event.entityName !== "*" &&
        (event.type === "updated" ||
          event.type === "posted" ||
          event.type === "unposted" ||
          event.type === "changed");
      if (surgical) patchRow(event.id!);
      else reload();
    };
    window.addEventListener("onno:dataevent", onData);
    return () => window.removeEventListener("onno:dataevent", onData);
  }, [kind, name, openable, reload, patchRow]);

  // Pager math (paged mode): how many pages, and the 1-based "from–to of total" range for the page.
  const paged = feedMode === "paged";
  const pageCount = total == null ? 1 : Math.max(1, Math.ceil(total / pageSize));
  const rangeFrom = total ? page * pageSize + 1 : 0;
  const rangeTo = total == null ? 0 : Math.min(total, page * pageSize + pageSize);
  // Clamp the page if the row count shrank under us (a filter/delete) and left us past the last page.
  useEffect(() => {
    if (paged && total != null && page > 0 && page > pageCount - 1) setPage(pageCount - 1);
  }, [paged, total, page, pageCount]);

  // Rows currently loaded, and — the header count — the exact/estimated total when known, else the
  // loaded count (infinite mode without a server estimate, e.g. on H2).
  const loadedRows = pageRows ?? [];
  const countValue = total ?? (pageRows == null ? null : loadedRows.length);

  // Virtual window (infinite mode): render only the rows overlapping the viewport (± overscan),
  // padding the scroller above/below with the height of the rows not drawn. Paged mode renders the
  // whole page (it's small), so the window spans everything.
  const virtual = feedMode === "infinite" && pageRows != null;
  const startIdx = virtual ? Math.max(0, Math.floor(scrollTop / ROW_H) - OVERSCAN) : 0;
  const endIdx = virtual
    ? Math.min(loadedRows.length, Math.ceil((scrollTop + viewportH) / ROW_H) + OVERSCAN)
    : loadedRows.length;
  const visibleRows = pageRows == null ? [] : loadedRows.slice(startIdx, endIdx);
  const padTop = startIdx * ROW_H;
  const padBottom = Math.max(0, (loadedRows.length - endIdx) * ROW_H);

  // The custom renderer's contract (see ListRendererProps): a stable descriptor slice plus
  // ref-stable open callbacks, so a memoized renderer isn't re-rendered by unrelated island state.
  const rendererList = useMemo(
    () => ({ kind, name, title: list.title, columns, canWrite }),
    [kind, name, list.title, columns, canWrite]
  );
  const rendererOpenUrl = useCallback(
    (row: EntityRecord) => (openable && row._id != null ? `onno://${kind}/${name}/${row._id}` : null),
    [openable, kind, name]
  );
  const rendererOpen = useCallback(
    (row: EntityRecord) => {
      const url = openable && row._id != null ? `onno://${kind}/${name}/${row._id}` : null;
      if (url) dispatchAction(url);
    },
    [openable, kind, name]
  );

  // Cycle a column through three states on repeated clicks: ascending → descending → off
  // (off clears the sort entirely, so the list falls back to the server's default order).
  const toggleSort = (col: string) => {
    setSort((s) => {
      if (s.column !== col) return { column: col, descending: false }; // new column → ascending
      if (!s.descending) return { column: col, descending: true }; // ascending → descending
      return { column: null, descending: false }; // descending → off
    });
  };

  // ⌘C / ⌘V on rows. Copy writes two clipboard flavours from the native `copy` event: text/plain
  // TSV of the visible columns (pastes into a text file or spreadsheet as-is) and an app payload
  // (custom MIME with the record ids). Paste reads the payload back and creates a server-side
  // duplicate per id — so records copy within (or across tabs of) the app. Native behaviour wins
  // wherever it makes sense: an editable field focused or a real text selection active.
  useEffect(() => {
    const textSelected = () => {
      const sel = window.getSelection();
      return !!sel && !sel.isCollapsed && !!sel.toString().trim();
    };
    const onCopy = (e: ClipboardEvent) => {
      if (e.defaultPrevented || !e.clipboardData || !openable) return;
      if (isEditingTarget() || textSelected()) return;
      // The selection, else the hovered row — and only a row of THIS list.
      let rows: EntityRecord[];
      if (selected.size) {
        rows = loadedRows.filter((r) => r._id != null && selected.has(String(r._id)));
      } else {
        const id = hoveredRowId(kind, name);
        const row = id ? loadedRows.find((r) => String(r._id) === id) : undefined;
        if (!row) return;
        rows = [row];
      }
      if (!rows.length) return;
      e.preventDefault();
      const tsv = rows
        .map((r) => columns.map((c) => displayCellValue(rawCellValue(r, c, t), c)).join("\t"))
        .join("\n");
      e.clipboardData.setData("text/plain", tsv);
      e.clipboardData.setData(
        "application/x-onno-rows",
        JSON.stringify({ kind, name, ids: rows.map((r) => String(r._id)) })
      );
      toast.success(t("clipboard.copied", { count: rows.length }));
    };
    const onPaste = (e: ClipboardEvent) => {
      if (e.defaultPrevented || !e.clipboardData || !openable) return;
      if (isEditingTarget()) return;
      const raw = e.clipboardData.getData("application/x-onno-rows");
      if (!raw) return;
      let payload: { kind?: string; name?: string; ids?: string[] };
      try {
        payload = JSON.parse(raw);
      } catch {
        return;
      }
      // Only the matching list consumes the paste (several lists can be mounted at once).
      if (payload.kind !== kind || payload.name !== name || !Array.isArray(payload.ids) || !payload.ids.length) return;
      if (!list.newUrl) return; // read-only for this user — no pasting copies
      e.preventDefault();
      // Each pasted id is a full server-side create; cap the fan-out a single ⌘V can trigger.
      const PASTE_LIMIT = 50;
      if (payload.ids.length > PASTE_LIMIT) {
        toast.error(t("clipboard.tooMany", { max: PASTE_LIMIT }));
        return;
      }
      const ids = payload.ids;
      void (async () => {
        if (batchBusyRef.current) return;
        batchBusyRef.current = true;
        let ok = 0;
        try {
          for (const id of ids) {
            try {
              if (kind === "documents") await api.duplicateDocument(name, id);
              else await api.duplicateCatalogItem(name, id);
              ok++;
            } catch {
              // already toasted by the api layer
            }
          }
        } finally {
          batchBusyRef.current = false;
        }
        (ok === ids.length ? toast.success : toast.error)(t("clipboard.pasted", { ok, n: ids.length }));
        reload();
      })();
    };
    document.addEventListener("copy", onCopy);
    document.addEventListener("paste", onPaste);
    return () => {
      document.removeEventListener("copy", onCopy);
      document.removeEventListener("paste", onPaste);
    };
  }, [kind, name, openable, selected, loadedRows, columns, list.newUrl, reload, t]);

  // Keyboard selection: ⌘A selects every loaded row; ⇧⌘↓ / ⇧⌘↑ extend the selection from the
  // anchor to the bottom / top of the loaded set (an infinite feed selects what's loaded — ids of
  // unloaded rows aren't known). Gated on this list being "engaged" — it already has a selection,
  // or the cursor is over one of its rows — so a page-level ⌘A isn't hijacked when no list is
  // being worked with, and two mounted lists can't both grab it (defaultPrevented wins).
  useEffect(() => {
    if (!openable) return;
    const onKey = (e: KeyboardEvent) => {
      if (e.defaultPrevented || isEditingTarget()) return;
      if (!(e.metaKey || e.ctrlKey)) return;
      // matchesKey: layout-independent — ⌘A stays the physical A key on a Cyrillic layout too.
      const isSelectAll = !e.shiftKey && matchesKey(e, "a");
      const isExtend = e.shiftKey && (e.key === "ArrowDown" || e.key === "ArrowUp");
      if (!isSelectAll && !isExtend) return;
      const hoverId = hoveredRowId(kind, name);
      if (selected.size === 0 && hoverId == null) return; // not engaged with this list
      const idsBetween = (from: number, to: number) => {
        const out: string[] = [];
        for (let i = Math.max(0, from); i <= Math.min(loadedRows.length - 1, to); i++) {
          const r = loadedRows[i];
          if (r?._id != null) out.push(String(r._id));
        }
        return out;
      };
      if (isSelectAll) {
        e.preventDefault();
        const all = idsBetween(0, loadedRows.length - 1);
        setSelected(new Set(all));
        selAnchorRef.current = 0;
        // An infinite feed can only select what's loaded — say so when more rows exist, or
        // "50 selected" quietly reads as "everything".
        if (total != null && total > all.length) {
          toast.info(t("list.selectedPartial", { count: all.length, total }));
        }
        return;
      }
      e.preventDefault();
      // The pivot the range grows from: the click anchor, else the hovered row, else the
      // selection's nearest edge in the direction of travel.
      let pivot: number | null = selAnchorRef.current;
      if (pivot == null && hoverId != null) {
        const i = loadedRows.findIndex((r) => String(r._id) === hoverId);
        pivot = i >= 0 ? i : null;
      }
      if (pivot == null) {
        const idxs: number[] = [];
        loadedRows.forEach((r, i) => {
          if (r._id != null && selected.has(String(r._id))) idxs.push(i);
        });
        if (!idxs.length) return;
        pivot = e.key === "ArrowDown" ? idxs[0] : idxs[idxs.length - 1];
      }
      const range =
        e.key === "ArrowDown" ? idsBetween(pivot, loadedRows.length - 1) : idsBetween(0, pivot);
      setSelected((prev) => {
        const next = new Set(prev);
        for (const id of range) next.add(id);
        return next;
      });
      if (selAnchorRef.current == null) selAnchorRef.current = pivot;
    };
    window.addEventListener("keydown", onKey);
    return () => window.removeEventListener("keydown", onKey);
  }, [openable, kind, name, selected, loadedRows, total, t]);

  // The row right-click menu: built-ins (Open/Edit/Duplicate/Copy link/Delete) plus the entity's
  // custom row actions — flat ones as top-level items, .menu("…")-grouped ones as submenus. With a
  // multi-row selection (and the click landing on a selected row) it switches to batch mode:
  // server actions run over every selected id, and Delete becomes a two-step "Delete N".
  const rowMenuEl = rowMenu
    ? (() => {
        const batch = selected.size > 1 && selected.has(rowMenu.id);
        const ids = batch ? [...selected] : [rowMenu.id];
        // Navigation actions are single-record by nature — batch mode offers server actions only.
        const eligible = (a: ListAction) => !batch || a.server;
        const flatCustom = rowActions.filter((a) => !a.menu).filter(eligible);
        const submenus = rowSubmenus
          .map((g) => ({ label: g.label, actions: g.actions.filter(eligible) }))
          .filter((g) => g.actions.length);
        const close = () => {
          setRowMenu(null);
          setArmedDelete(false);
        };
        const actionItem = (a: ListAction) => {
          // Per-row overrides only make sense against the one clicked row; a batch shows the
          // static descriptor (each row's own visibility is the handler's business).
          const st = batch
            ? undefined
            : (rowMenu.row._actions as Record<string, RowActionState> | undefined)?.[a.key];
          if (!batch && st?.visible === false) return null;
          const disabled = (!batch && st?.enabled === false) || pending.has(`batch:${a.key}`);
          return (
            <ContextMenuItem
              key={a.key}
              disabled={disabled}
              onSelect={() => {
                if (batch) void runBatchAction(a, ids);
                else runAction(a, rowMenu.id);
                close();
              }}
            >
              {a.logo ? (
                <img src={a.logo} alt="" aria-hidden="true" className="size-4 shrink-0 object-contain" />
              ) : (
                <DynamicLucide name={(!batch && st?.icon) || a.icon || "zap"} size={16} />
              )}
              <span>{(!batch && st?.label) || a.label}</span>
            </ContextMenuItem>
          );
        };
        // Built-ins: batch = label + delete; single = open/dup/copyLink/delete — minus the
        // write items (dup, delete) when the viewer can't write the entity.
        const itemCount = (batch ? (canWrite ? 2 : 1) : canWrite ? 4 : 2) + flatCustom.length + submenus.length;
        return (
          <ContextMenuContent
            open
            position={{ x: rowMenu.x, y: rowMenu.y }}
            onOpenChange={(o) => {
              if (!o) close();
            }}
            width={216}
            estimatedHeight={itemCount * 38 + 24}
          >
            {batch ? (
              <ContextMenuLabel>{t("list.selected", { count: selected.size })}</ContextMenuLabel>
            ) : (
              <>
                <ContextMenuItem
                  onSelect={() => {
                    dispatchAction(rowMenu.url);
                    close();
                  }}
                >
                  <ExternalLink className="text-muted-foreground" aria-hidden="true" />
                  <span>{t("action.open")}</span>
                  <ContextMenuShortcut>{shortcutLabel({ key: "Enter", mod: true })}</ContextMenuShortcut>
                </ContextMenuItem>
                {/* Open IS edit now — the record surface is the editable form — so the write
                    items are just Duplicate (and Delete below). */}
                {canWrite ? (
                  <ContextMenuItem
                    onSelect={() => {
                      dispatchAction(rowMenu.url + "/duplicate");
                      close();
                    }}
                  >
                    <Copy className="text-muted-foreground" aria-hidden="true" />
                    <span>{t("action.duplicate")}</span>
                    <ContextMenuShortcut>{shortcutLabel({ key: "d", mod: true, shift: true })}</ContextMenuShortcut>
                  </ContextMenuItem>
                ) : null}
              </>
            )}
            {flatCustom.length || submenus.length ? <ContextMenuSeparator /> : null}
            {flatCustom.map(actionItem)}
            {submenus.map((g) => (
              <ContextMenuSub key={g.label} label={<span>{g.label}</span>} width={200}>
                {g.actions.map(actionItem)}
              </ContextMenuSub>
            ))}
            {!batch ? (
              <>
                <ContextMenuSeparator />
                <ContextMenuItem
                  onSelect={() => {
                    void copyToClipboard(rowShareableUrl(rowMenu.url)).then((ok) =>
                      ok ? toast.success("Link copied") : toast.error("Couldn't copy link")
                    );
                    close();
                  }}
                >
                  <Link2 className="text-muted-foreground" aria-hidden="true" />
                  <span>{t("action.copyLink")}</span>
                  <ContextMenuShortcut>{shortcutLabel({ key: "c", mod: true, shift: true })}</ContextMenuShortcut>
                </ContextMenuItem>
              </>
            ) : null}
            {canWrite ? (
              <>
                <ContextMenuSeparator />
                {batch ? (
                  <ContextMenuItem
                    variant="destructive"
                    disabled={pending.has("batch:__delete")}
                    onClick={(e) => {
                      // Two-step: the first click arms (label flips to the confirm wording and the
                      // menu stays open), the second runs. preventDefault keeps onSelect from firing.
                      e.preventDefault();
                      if (!armedDelete) {
                        setArmedDelete(true);
                        return;
                      }
                      void runBatchDelete(ids);
                      close();
                    }}
                  >
                    <Trash2 aria-hidden="true" />
                    <span>
                      {armedDelete
                        ? t("batch.deleteConfirm", { n: ids.length })
                        : t("batch.delete", { n: ids.length })}
                    </span>
                  </ContextMenuItem>
                ) : (
                  <ContextMenuItem
                    variant="destructive"
                    onSelect={() => {
                      dispatchAction(rowDeleteUrl(rowMenu.url));
                      close();
                    }}
                  >
                    <Trash2 aria-hidden="true" />
                    <span>{t("action.delete")}</span>
                    <ContextMenuShortcut>{shortcutLabel({ key: "Delete" })}</ContextMenuShortcut>
                  </ContextMenuItem>
                )}
              </>
            ) : null}
          </ContextMenuContent>
        );
      })()
    : null;

  // Pager footer — paged mode only, when there's more than one page (infinite mode scrolls for
  // more; the toolbar already shows the count). Shared by the table card and a custom body.
  const pagerEl =
    paged && total != null && total > pageSize ? (
      <div className="flex items-center justify-between gap-3 border-t border-border bg-card px-4 py-2.5 text-xs text-muted-foreground">
        <span className="tabular-nums">{t("list.pageRange", { from: rangeFrom, to: rangeTo, total })}</span>
        <div className="flex items-center gap-1.5">
          <button
            type="button"
            disabled={page <= 0}
            onClick={() => setPage((p) => Math.max(0, p - 1))}
            className="inline-flex h-8 items-center gap-1 rounded-control border border-input bg-card px-2.5 font-medium text-foreground hover:bg-accent disabled:cursor-not-allowed disabled:opacity-40"
            aria-label={t("list.prev")}
          >
            <ChevronLeft className="size-4" />
            {compact ? null : t("list.prev")}
          </button>
          <span className="px-1.5 tabular-nums">{t("list.pageOf", { page: page + 1, pages: pageCount })}</span>
          <button
            type="button"
            disabled={page >= pageCount - 1}
            onClick={() => setPage((p) => Math.min(pageCount - 1, p + 1))}
            className="inline-flex h-8 items-center gap-1 rounded-control border border-input bg-card px-2.5 font-medium text-foreground hover:bg-accent disabled:cursor-not-allowed disabled:opacity-40"
            aria-label={t("list.next")}
          >
            {compact ? null : t("list.next")}
            <ChevronRight className="size-4" />
          </button>
        </div>
      </div>
    ) : null;

  return (
    // DivKit wraps custom blocks in spans with pointer-events:none, which the island inherits —
    // re-assert pointer-events:auto here so hover, row clicks and the right-click menu all work.
    // As its own route surface the island takes the full height from its top to the window bottom
    // (equal padding all round) and scrolls only its list body inside — so the page never scrolls.
    // Embedded in a page, it flows with the host page (which owns scrolling) and drops its
    // horizontal gutter so the table aligns with the sibling cards.
    <div
      ref={rootRef}
      className={cn(
        "pointer-events-auto flex min-h-0 flex-col",
        surfaceMode ? "overflow-hidden p-4 sm:p-6" : "py-4"
      )}
      style={surfaceMode && surfaceH != null ? { height: surfaceH } : undefined}
    >
      {/* Control island — a single floating bar that replaces the old title/search/actions row AND
          the separate filter bar. Title + row count lead; search, group-by and the filter facets fill
          the middle; the view toggle, custom actions and New pin right. It wraps (flex-wrap) when
          narrow; `shrink-0` keeps it from being squeezed by a tall card below. */}
      <div className="mb-3 flex shrink-0 flex-wrap items-center gap-x-2 gap-y-2 rounded-card border border-border/70 bg-card px-2.5 py-2">
        {/* title + host control + row count. The host-provided control (e.g. the register's
            Balance/Movements toggle) sits between the fixed title and the count, so a changing
            count (or the "…" while it loads) never shifts the control the user is clicking. */}
        <div className="mr-1 flex min-w-0 items-center gap-2">
          <h1 className="truncate text-base font-semibold text-foreground">{list.title}</h1>
          {headerExtra}
          <span className="whitespace-nowrap text-xs tabular-nums text-muted-foreground">
            {countValue == null ? "…" : t("list.count", { count: countValue })}
          </span>
          {selected.size ? (
            <button
              type="button"
              onClick={clearSelection}
              className="inline-flex h-6 shrink-0 items-center gap-1 whitespace-nowrap rounded-control bg-primary/10 px-2 text-xs font-medium text-primary transition-colors hover:bg-primary/15"
              title={t("list.clearSelection")}
            >
              {t("list.selected", { count: selected.size })}
              <X className="size-3" aria-hidden="true" />
            </button>
          ) : null}
        </div>

        {/* group-by (+ granularity for a date column) — a facet chip like the filters beside it.
            Hidden on the map (it fetches its own rows) and on a custom body (grouping renders the
            grouped table, which the custom renderer replaces). */}
        {!mapMode && !customMode && groupable.length ? (
          <GroupByFacet
            groupable={groupable}
            groupBy={groupBy}
            onGroupBy={setGroupBy}
            granularity={granularity}
            onGranularity={setGranularity}
          />
        ) : null}

        {/* filter facets */}
        {!mapMode &&
          filters.map((f) => {
            if (f.type === "dateRange") {
              return (
                <DateRangeFacet
                  key={f.key}
                  label={f.label}
                  from={filterState[f.key]?.from ?? ""}
                  to={filterState[f.key]?.to ?? ""}
                  onChange={(next) => setFilterState((s) => ({ ...s, [f.key]: next }))}
                />
              );
            }
            if (f.type === "options" || f.type === "multiOptions") {
              const multi = f.type === "multiOptions";
              const st = filterState[f.key];
              const selected = multi ? st?.in ?? [] : st?.eq ? [st.eq] : [];
              return (
                <OptionsFacet
                  key={f.key}
                  label={f.label}
                  options={f.options}
                  multi={multi}
                  selected={selected}
                  onChange={(next) =>
                    setFilterState((s) => ({
                      ...s,
                      [f.key]: multi ? { in: next } : { eq: next[0] ?? "" },
                    }))
                  }
                />
              );
            }
            return (
              <TextFacet
                key={f.key}
                label={f.label}
                value={filterState[f.key]?.text ?? ""}
                onCommit={(next) => setFilterState((s) => ({ ...s, [f.key]: { text: next } }))}
              />
            );
          })}
        {!mapMode &&
          toolbarInputs.map((inp) => (
            <label key={inp.key} className="flex shrink-0 items-center gap-1.5 text-xs text-muted-foreground">
              <span className="whitespace-nowrap">{inp.label}</span>
              {inp.type === "select" ? (
                <Select
                  value={inputValues[inp.key] ?? ""}
                  onValueChange={(val) =>
                    setInputValues((v) => ({ ...v, [inp.key]: val === SELECT_NONE ? "" : val }))
                  }
                >
                  <SelectTrigger className="h-8 w-36 rounded-field text-xs">
                    <SelectValue placeholder={inp.placeholder || inp.label} />
                  </SelectTrigger>
                  <SelectContent>
                    {inp.placeholder ? <SelectItem value={SELECT_NONE}>{inp.placeholder}</SelectItem> : null}
                    {inp.options.map((o) => (
                      <SelectItem key={o} value={o}>
                        {o}
                      </SelectItem>
                    ))}
                  </SelectContent>
                </Select>
              ) : inp.type === "date" ? (
                <div className="w-40">
                  <DatePicker
                    value={inputValues[inp.key] || undefined}
                    onChange={(val) => setInputValues((v) => ({ ...v, [inp.key]: val }))}
                  />
                </div>
              ) : (
                <Input
                  type={inp.type === "number" ? "number" : "text"}
                  value={inputValues[inp.key] ?? ""}
                  onChange={(e) => setInputValues((v) => ({ ...v, [inp.key]: e.target.value }))}
                  placeholder={inp.placeholder}
                  className="h-8 w-36 text-xs"
                />
              )}
            </label>
          ))}
        {!mapMode && activeFilterCount > 0 ? (
          <button
            type="button"
            onClick={clearAllFilters}
            className="inline-flex h-8 shrink-0 items-center gap-1 rounded-control px-2.5 text-xs font-medium text-muted-foreground transition-colors hover:bg-accent hover:text-foreground"
          >
            <X className="size-3.5" />
            {t("list.clearAll")}
          </button>
        ) : null}

        {/* right cluster — search, view toggle, custom actions, New */}
        <div className="ml-auto flex flex-wrap items-center gap-2">
          {/* search — right-aligned, leading the action cluster */}
          {!mapMode && list.searchable ? (
            <div className={cn("relative", stacked ? "min-w-[9rem] flex-1" : "")}>
              <Search className="pointer-events-none absolute left-2.5 top-1/2 size-3.5 -translate-y-1/2 text-muted-foreground" aria-hidden="true" />
              <Input
                value={query}
                onChange={(e) => setQuery(e.target.value)}
                placeholder={t("list.search")}
                className={cn("h-8 rounded-field pl-8 text-xs", stacked ? "w-full" : "w-44 sm:w-52")}
              />
            </div>
          ) : null}
          {list.map || (list.custom && CustomRenderer) ? (
            // Table plus whichever alternate bodies the list declares. The custom option only
            // appears once its type is registered — an unknown renderer degrades to the plain grid.
            <Segmented
              value={customMode ? "custom" : view === "custom" ? "table" : view}
              onChange={setView}
              options={[
                { value: "table", icon: Table2, label: compact ? undefined : t("list.tableView"), ariaLabel: t("list.tableView") },
                ...(list.map
                  ? [{ value: "map" as const, icon: MapIcon, label: compact ? undefined : t("list.mapView"), ariaLabel: t("list.mapView") }]
                  : []),
                ...(list.custom && CustomRenderer
                  ? [{
                      value: "custom" as const,
                      icon: LayoutGrid,
                      label: compact ? undefined : list.custom.label || t("list.customView"),
                      ariaLabel: list.custom.label || t("list.customView"),
                    }]
                  : []),
              ]}
            />
          ) : null}
          {toolbarActions.map((a) => {
            const busy = pending.has(a.key);
            const iconOnly = compact && (!!a.icon || !!a.logo);
            return (
              <button
                key={a.key}
                type="button"
                onClick={() => runAction(a)}
                disabled={busy}
                className={cn(
                  "inline-flex h-8 shrink-0 items-center gap-1.5 whitespace-nowrap rounded-control bg-secondary text-xs font-medium text-foreground transition-colors hover:bg-accent disabled:cursor-not-allowed disabled:opacity-60",
                  iconOnly ? "w-8 justify-center px-0" : "px-3"
                )}
                title={a.label}
                aria-label={a.label}
              >
                {busy ? (
                  <Loader2 className="size-4 animate-spin" aria-hidden="true" />
                ) : a.logo ? (
                  <img src={a.logo} alt="" aria-hidden="true" className="size-4 shrink-0 object-contain" />
                ) : a.icon ? (
                  <DynamicLucide name={a.icon} size={16} />
                ) : null}
                {iconOnly ? null : a.label}
              </button>
            );
          })}
          {list.newUrl ? (
            <button
              type="button"
              onClick={() => dispatchAction(list.newUrl!)}
              className={cn(
                "inline-flex h-8 shrink-0 items-center gap-1.5 whitespace-nowrap rounded-control bg-primary text-xs font-medium text-primary-foreground transition-colors hover:bg-primary/90",
                compact ? "w-8 justify-center px-0" : "px-3"
              )}
              title={t("action.new")}
              aria-label={t("action.new")}
            >
              <Plus className="size-4" aria-hidden="true" />
              {compact ? null : t("action.new")}
            </button>
          ) : null}
        </div>
      </div>

      {mapMode ? (
        // Only catalog/document lists ever declare a map config, so mapMode is never true for a
        // register — narrow the kind for the map view, which pages from /api/list/{kind}/{name}.
        <div className={cn(surfaceMode && "min-h-0 flex-1")}>
          <ListMapView kind={kind as "catalogs" | "documents"} name={name} config={list.map!} fill={surfaceMode} />
        </div>
      ) : customMode && CustomRenderer ? (
        // Custom body (ListSpec.custom): the registered renderer draws the rows; the island keeps
        // the toolbar (search, filters, sort) and the feed — the same scroller drives infinite
        // load-more, and paged mode keeps the pager below. No card chrome: the renderer owns its
        // own look.
        <div className={cn("flex flex-col", surfaceMode && "min-h-0 flex-1")}>
          <div
            ref={scrollRef}
            onScroll={onScroll}
            className={cn("overflow-auto", surfaceMode && "min-h-0 flex-1")}
            style={surfaceMode ? undefined : { maxHeight: maxBodyH }}
          >
            {pageRows == null ? (
              <div className="flex items-center justify-center py-10 text-sm text-muted-foreground">
                <Loader2 className="mr-2 size-4 animate-spin" aria-hidden="true" />
                {t("loading.generic")}
              </div>
            ) : loadedRows.length === 0 ? (
              <div className="px-4 py-10 text-center text-sm text-muted-foreground">
                {debounced ? t("empty.noMatches") : t("empty.noRecords")}
              </div>
            ) : (
              <CustomRenderer rows={loadedRows} list={rendererList} open={rendererOpen} openUrl={rendererOpenUrl} />
            )}
            {feedMode === "infinite" && loadingMore ? (
              <div className="flex items-center justify-center gap-2 py-3 text-xs text-muted-foreground">
                <Loader2 className="size-4 animate-spin" aria-hidden="true" />
                {t("list.loadingMore")}
              </div>
            ) : null}
          </div>
          {pagerEl ? (
            <div className="mt-3 shrink-0 overflow-hidden rounded-card border border-border">{pagerEl}</div>
          ) : null}
        </div>
      ) : grouped ? (
        // Grouped view: collapsible GROUP BY headers with subtotals; a group's rows lazily load
        // through the same feed. Shares the toolbar's search/filters/sort via groupParamsBase.
        <GroupedList
          feedBase={feedBase}
          kind={kind}
          name={name}
          columns={columns}
          template={template}
          minTableWidth={minTableWidth}
          leftPad={leftPad}
          aggregates={aggregates}
          groupBy={groupBy}
          granularity={groupCol?.date ? granularity : ""}
          paramsBase={groupParamsBase}
          pageSize={pageSize}
          openable={openable}
          canWrite={canWrite}
          surfaceMode={surfaceMode}
          scrollCap={maxBodyH}
        />
      ) : (
      /* table card — one scroller for both axes; the header sticks to the top (so it scrolls
         horizontally in lock-step with the rows) and the inner min-width drives horizontal
         scroll when the card is narrower than the columns need. On a route surface the card sizes
         to its rows and only shrinks (min-h-0, no grow) when they overflow the island's remaining
         height — a short list ends at its last row instead of dragging an empty card to the window
         bottom; embedded, it caps at maxBodyH. */
      <div className={cn(
        "flex flex-col overflow-hidden rounded-card border border-border bg-card",
        surfaceMode && "min-h-0"
      )}>
        {/* the body scroller — the only thing that scrolls. Header sticks to its top; the inner
            min-width drives horizontal scroll when the card is narrower than the columns need. */}
        <div
          ref={scrollRef}
          onScroll={onScroll}
          className={cn("overflow-auto", surfaceMode && "min-h-0 flex-1")}
          style={surfaceMode ? undefined : { maxHeight: maxBodyH }}
        >
          <div style={{ minWidth: minTableWidth }}>
            {/* sticky header */}
            <div
              className={cn("sticky top-0 z-10 grid items-center gap-3 border-b border-border bg-card py-2.5", leftPad)}
              style={{ gridTemplateColumns: template }}
            >
              {columns.map((c) => {
                const active = sort.column === c.columnName;
                // The help "?" sits beside (not inside) the sort button — nesting buttons is
                // invalid HTML and would make the hint trigger a sort.
                return (
                  <div key={c.columnName} className="flex min-w-0 items-center gap-1">
                    <button
                      type="button"
                      onClick={() => toggleSort(c.columnName)}
                      className={cn(
                        "flex min-w-0 items-center gap-1 truncate text-left text-xs font-medium",
                        // The sorted column carries the brand (accent = state): which column orders
                        // the list. Inactive headers stay muted and brighten on hover.
                        active ? "text-primary" : "text-muted-foreground hover:text-foreground"
                      )}
                      title={t("list.sortBy", { column: c.label })}
                    >
                      <span className="truncate">{c.label}</span>
                      {active ? (
                        sort.descending ? <ArrowDown className="size-3 shrink-0" /> : <ArrowUp className="size-3 shrink-0" />
                      ) : (
                        <ChevronsUpDown className="size-3 shrink-0 opacity-30" />
                      )}
                    </button>
                    <HintIcon text={c.hint} size={13} />
                  </div>
                );
              })}
              {rowButtonActions.length ? <span aria-hidden="true" /> : null}
            </div>

            {/* body — the virtual window in infinite mode (spacers stand in for off-screen rows);
                the whole page in paged mode. */}
            {pageRows != null && loadedRows.length === 0 ? (
              <div className="px-4 py-10 text-center text-sm text-muted-foreground">
                {debounced ? t("empty.noMatches") : t("empty.noRecords")}
              </div>
            ) : pageRows == null ? (
              // Loading placeholder — static bars (no pulse), one card-row each.
              Array.from({ length: Math.min(pageSize, 10) }).map((_, i) => (
                <div
                  key={i}
                  className={cn("grid items-center gap-3 border-b border-border/50", leftPad)}
                  style={{ minHeight: ROW_H, gridTemplateColumns: template }}
                >
                  {columns.map((c) => (
                    <span key={c.columnName} className="h-3.5 w-2/3 rounded bg-muted/60" />
                  ))}
                  {rowButtonActions.length ? <span aria-hidden="true" /> : null}
                </div>
              ))
            ) : (
              // Pad above/below with the height of the rows outside the window (infinite mode);
              // paged mode renders every row (padTop/padBottom are 0).
              <div style={{ paddingTop: padTop, paddingBottom: padBottom }}>
                {visibleRows.map((row, i) => {
                  const absIdx = startIdx + i;
                  const rowId = openable && row._id != null ? String(row._id) : null;
                  const url = openable ? `onno://${kind}/${name}/${row._id}` : undefined;
                  const rowViewers = openable ? viewersById.get(String(row._id)) : undefined;
                  const isSelected = rowId != null && selected.has(rowId);
                  return (
                    // Key by absolute row index: register (balance) rows have no _id, so an id-based
                    // key collides to the same value for every row and React mis-reconciles into
                    // ghost/duplicate rows. The loaded list is append-only per query, so the absolute
                    // index is a stable, correct key here.
                    <div
                      key={absIdx}
                      data-onno-row={url}
                      // "0" tells the shell's fallback row menu / shortcuts to hide write actions;
                      // omitted when writable so rows from older servers keep the full menu.
                      data-onno-row-writable={canWrite ? undefined : "0"}
                      // Shift-click extends the selection — suppress the browser's text-range drag.
                      onMouseDown={(e) => {
                        if (e.shiftKey && rowId) e.preventDefault();
                      }}
                      onClick={(e) => {
                        // ⌘/Ctrl toggles a row in and out; Shift selects the range from the last
                        // toggled row. Plain click opens the record — or, mid-selection, just
                        // drops the selection (so a stray click can't navigate away from it).
                        if (rowId && (e.metaKey || e.ctrlKey || e.shiftKey)) {
                          e.preventDefault();
                          setSelected((prev) => {
                            const next = new Set(prev);
                            if (e.shiftKey && selAnchorRef.current != null) {
                              const lo = Math.min(selAnchorRef.current, absIdx);
                              const hi = Math.max(selAnchorRef.current, absIdx);
                              for (let j = lo; j <= hi; j++) {
                                const r = loadedRows[j];
                                if (r?._id != null) next.add(String(r._id));
                              }
                            } else {
                              if (next.has(rowId)) next.delete(rowId);
                              else next.add(rowId);
                              selAnchorRef.current = absIdx;
                            }
                            return next;
                          });
                          if (selAnchorRef.current == null) selAnchorRef.current = absIdx;
                          return;
                        }
                        if (selected.size) {
                          clearSelection();
                          return;
                        }
                        if (url) dispatchAction(url);
                      }}
                      onContextMenu={(e) => {
                        if (!url || !rowId) return; // register rows: keep the browser menu
                        // Yield to an active text selection, like the global row menu does.
                        const sel = window.getSelection();
                        if (sel && !sel.isCollapsed && sel.toString().trim()) return;
                        // preventDefault also marks the native event, so divkit-view's
                        // DOM-sniffing fallback menu skips this row.
                        e.preventDefault();
                        if (selected.size && !selected.has(rowId)) clearSelection();
                        setArmedDelete(false);
                        setRowMenu({ x: e.clientX, y: e.clientY, id: rowId, url, row });
                      }}
                      className={cn(
                        // Hover highlight is owned by the [data-onno-row]:hover rule in index.css.
                        // Register rows aren't clickable (no detail route), so no pointer cursor.
                        "relative grid items-center gap-3 border-b border-border/50 text-sm",
                        url && "cursor-pointer",
                        leftPad,
                        absIdx % 2 === 1 && !isSelected && "bg-muted/20",
                        isSelected && "bg-primary/10"
                      )}
                      style={{ minHeight: ROW_H, gridTemplateColumns: template }}
                    >
                      {columns.map((c) => (
                        <ListCell key={c.columnName} row={row} col={c} />
                      ))}
                      {rowViewers ? (
                        <div className="pointer-events-none absolute left-0 top-1/2 z-10 flex w-11 -translate-y-1/2 items-center justify-end pr-1.5">
                          <PresenceAvatars viewers={rowViewers} size={16} max={3} overlap />
                        </div>
                      ) : null}
                      {rowButtonActions.length ? (
                        <div className="flex items-center justify-end gap-1">
                          {rowButtonActions.map((a) => {
                            // State-aware row actions: the server attaches a per-row override
                            // (icon/label/visible/enabled) under `_actions`, computed from the row's data.
                            const st = (row._actions as Record<string, RowActionState> | undefined)?.[a.key];
                            if (st?.visible === false) return null;
                            const icon = st?.icon ?? a.icon;
                            const label = st?.label ?? a.label;
                            const busy = pending.has(`${a.key}:${row._id}`);
                            const disabled = busy || st?.enabled === false;
                            return (
                              <button
                                key={a.key}
                                type="button"
                                disabled={disabled}
                                onClick={(e) => {
                                  e.stopPropagation();
                                  runAction(a, String(row._id));
                                }}
                                className="inline-flex size-7 items-center justify-center rounded-control text-muted-foreground hover:bg-accent hover:text-foreground disabled:cursor-not-allowed disabled:opacity-60"
                                title={label}
                                aria-label={label}
                              >
                                {busy ? (
                                  <Loader2 className="size-[15px] animate-spin" aria-hidden="true" />
                                ) : a.logo ? (
                                  <img src={a.logo} alt="" aria-hidden="true" className="size-[15px] shrink-0 object-contain" />
                                ) : (
                                  <DynamicLucide name={icon || "zap"} size={15} />
                                )}
                              </button>
                            );
                          })}
                        </div>
                      ) : null}
                    </div>
                  );
                })}
              </div>
            )}
            {/* infinite mode: a slim "loading more" row while the next window is in flight */}
            {feedMode === "infinite" && loadingMore ? (
              <div className="flex items-center justify-center gap-2 border-t border-border/50 py-3 text-xs text-muted-foreground">
                <Loader2 className="size-4 animate-spin" aria-hidden="true" />
                {t("list.loadingMore")}
              </div>
            ) : null}
          </div>
        </div>
        {pagerEl}
      </div>
      )}
      {rowMenuEl}
      {formPrompt ? (
        <ActionFormDialog
          title={formPrompt.action.label}
          fields={formPrompt.action.form ?? []}
          busy={formBusy}
          onClose={() => setFormPrompt(null)}
          onSubmit={(values) => {
            setFormBusy(true);
            const done = () => {
              setFormBusy(false);
              setFormPrompt(null);
            };
            // Both runners resolve (never reject): failures are toasted inside them by the api
            // layer / summary logic, so .finally is the whole contract — close the dialog either way.
            const p = formPrompt.ids
              ? runBatchAction(formPrompt.action, formPrompt.ids, values)
              : Promise.resolve(runAction(formPrompt.action, formPrompt.id, values));
            void Promise.resolve(p).finally(done);
          }}
        />
      ) : null}
    </div>
  );
}
