import { openRoute } from "../../lib/navigation";
import { notifyError } from "../../lib/notify-error";
import { ResponsiveActionPanel } from "../ui/responsive-action-panel";
import { nearestScrollAncestor, trailingPaddingTo } from "../../lib/fill-height";
import { ExtensionSlot } from "../../lib/ui-extensions";
import { Button, buttonVariants } from "../ui/button";
import { ListSelectionCheckbox, selectLoaded } from "./list-selection-checkbox";
import { useCallback, useContext, useEffect, useId, useLayoutEffect, useMemo, useRef, useState, type ComponentType, type ReactNode } from "react";
import { PageGutterContext } from "../../lib/page-gutter";
import { ArrowDown, ArrowLeftRight, ArrowUp, CalendarDays, Check, ChevronsUpDown, Copy, ExternalLink, GanttChart, LayoutGrid, Link2, ListFilter, Loader2, Map as MapIcon, PanelLeftClose, PanelLeftOpen, Plus, Rows3, Search, Table2, Trash2, X } from "lucide-react";
import { CalendarDate, getLocalTimeZone, parseDate, startOfMonth, startOfYear, today } from "@internationalized/date";
import { toast } from "../ui/toast";
import { ListMapView, type ListMapConfig } from "../maps/list-map-view";
import { ListCalendarView, ListTimelineView } from "./list-schedule-view";
import type { ListCalendarConfig, ListTimelineConfig } from "../../lib/schedule";
import { CommandFormDialog, type CommandFormValues } from "../actions/command-form-dialog";
import {
  commandIcon,
  normalizePresentation,
  presentCommands,
  type CommandDescriptor,
  type CommandPresentation,
  type CommandState,
  type PresentedCommand,
} from "../../lib/commands";
import { GroupedList } from "./entity-list-grouped";
import { initialFilterState, normalizeNumberBound, numberRangeLabel, type FilterState } from "./list-defaults";
import { listColumnPixelWidth } from "./list-column-width";
import { PresenceAvatars } from "../collaboration/presence-avatars";
import { useViewersById } from "../../lib/presence-store";
import { Input } from "../ui/input";
import { Popover, PopoverContent, PopoverTrigger } from "../ui/popover";
import { FacetSheet, SheetClearButton, SheetDoneButton, useTouchLayout } from "../ui/facet-sheet";
import { Tooltip, TooltipContent, TooltipProvider, TooltipTrigger } from "../ui/tooltip";
import { Badge } from "../ui/badge";
import { Avatar, AvatarFallback, AvatarImage } from "../ui/avatar";
import { RangeCalendar } from "../ui/calendar";
import { HintIcon } from "../ui/hint-icon";
import { Segmented } from "../ui/segmented";
import {
  ContextMenuContent,
  ContextMenuItem,
  ContextMenuLabel,
  ContextMenuSeparator,
  ContextMenuShortcut,
  ContextMenuSub,
} from "../ui/context-menu";
import { Icon } from "../../lib/icons";
import { api, ApiError, downloadJson, downloadSelection } from "../../lib/api";
import { useAuthUser } from "../../providers/auth-provider";
import { useCommandFeedback } from "../../lib/command-feedback";
import { isInteractiveLayerOpen, matchesKey, shortcutLabel } from "../../lib/keybindings";
import { withBasePath } from "../../lib/base-path";
import { cn, copyToClipboard, enumPillStyle, rowStyleClass } from "../../lib/utils";
import type { RowTone } from "../../lib/entity-customizations";
import type { WhereClause } from "../../lib/measure";
import { useQueries } from "@tanstack/react-query";
import { invalidateEntity, queryClient, useEntityChanges } from "../../lib/query";
import { IDENTITY_AVATAR_FIELDS } from "../../lib/identity-avatar";
import { useControllable } from "../../lib/use-controllable";
import {
  formatTimestampDefault, formatValue, isImageInput, looksLikeImageUrl, type FieldInput, type ValueFormat,
} from "../../lib/cell-format";
import { linkify } from "../../lib/linkify";
import type { EntityRecord } from "../../lib/types";
import { useListExportRoles, useMessages } from "../../providers/messages-provider";
import { RestrictedRef, isRestrictedRef } from "../fields/restricted-ref";
import type { Translate } from "../../lib/messages";
import { localeOutsideReact } from "../../lib/messages-outside-react";

/**
 * An entity's list: a virtualized, server-paged data grid. The server's default view is only a
 * descriptor (columns, filters, commands, the open/New routes); this component fetches windows of rows from {@code /api/list/...} as you scroll, sorts and searches
 * on the server, and renders only the visible rows — so a 10k-row entity stays smooth. Rows carry
 * {@code data-onno-row} so the existing right-click Open/Duplicate menu keeps working.
 */

/** A field's value type as the list knows it. */
export type ColumnType = "text" | "number" | "boolean" | "date" | "enum" | "ref";

export type ListColumn = {
  columnName: string;
  /** Logical field key used by catalog/document entity responses; query operations use columnName. */
  fieldName?: string;
  label: string;
  width: string;
  /** The field's value type, from the model (what a filter on it defaults to). */
  type?: ColumnType;
  /** An image/avatar input renders the cell as a thumbnail, a color input as a swatch. */
  input?: FieldInput;
  /** How the cell's value displays. */
  format?: ValueFormat;
  /** The cell, drawn by the app (the field's `render` customization): wins over format and input. */
  render?: (value: unknown, record: EntityRecord) => ReactNode;
  /** Optional help text; surfaced as a hoverable "?" next to the column header. */
  hint?: string;
  /** A command group this cell opens directly on right-click. */
  cellMenu?: string;
  /** False for a host-computed column the feed cannot sort by: its header is a plain label. */
  sortable?: boolean;
};

function responseKey(col: ListColumn): string {
  return col.fieldName || col.columnName;
}

export function entityRowId(row: EntityRecord): string | null {
  const value = row.id ?? row._id;
  return value == null ? null : String(value);
}

/** The per-record command state the feed attaches to a row (`_actions`, keyed by command key). */
function rowCommandState(row: EntityRecord): Record<string, CommandState> | undefined {
  return (row.actions ?? row._actions) as Record<string, CommandState> | undefined;
}

export function entityRowTone(row: EntityRecord, rowStyle?: (row: EntityRecord) => RowTone): unknown {
  // The app's rowStyle (an entity customization) wins over whatever the feed carried.
  const custom = rowStyle ? rowStyle(row) : undefined;
  return custom !== undefined ? custom : row.style ?? row._style;
}
/**
 * An entity command as the list offers it: the server's descriptor with the app's presentation
 * (placement, icon, title) resolved. Record commands run on a row (inline button, context menu,
 * selection batch); collection commands are toolbar buttons.
 */
export type ListCommand = PresentedCommand;
/**
 * A read-only selection download (e.g. batch print): POST {ids} to {@code url}, save the attachment.
 * Keyed and titled by the server; its icon and whether it shows come from the `commands`
 * customization under its key (default: a download icon).
 */
export type SelectionDownload = { key: string; title: string; url: string };
/**
 * A declarative filter that drives the list query itself. "options" renders a SELECT matched for equality on {@code column};
 * "multiOptions" renders a multi-select matched as {@code column IN (…)}; "contains"/"startsWith"
 * render a debounced typeahead matched case-insensitively (LIKE %v% / v%) — the high-cardinality
 * answer where enumerating every value is unusable; "dateRange" renders from/to date pickers driving
 * a {@code column >= from AND column <= to} range; "numberRange" renders from/to number inputs
 * driving the same inclusive range over a numeric column, either bound optional. The current value is sent to /api/list as
 * eq/in/like/prefix/ge/le params, and several filters narrow the list jointly (AND).
 */
/** One choice of a (multi-)options filter: {@code value} is matched on the column, {@code label} is
 *  shown, {@code color} (an @EnumLabel hex) tints enum choices, and {@code avatarUrl} shows a
 *  profile image for reference-backed choices such as assignees. */
export type FilterOption = { value: string; label: string; color?: string; avatarUrl?: string };
export type ListFilterControl = {
  key: string;
  label: string;
  column: string;
  type: "options" | "multiOptions" | "contains" | "startsWith" | "dateRange" | "numberRange";
  options: FilterOption[];
  /** Load the choices from this catalog's records (value = id, label = description, photo). */
  optionsFrom?: string;
};
/**
 * A custom list-body view (the app's `list.custom`): the component that renders the rows, an
 * optional toolbar-toggle {@code label} (else the {@code list.customView} message), and whether the
 * list opens on it.
 */
export type ListCustomConfig = {
  component?: ComponentType<ListRendererProps>;
  label?: string;
  defaultView?: boolean;
};
/**
 * The props a custom list-body view receives. The framework keeps the toolbar
 * (search, filters, sort, group-by) and the keyset feed/live refresh — the renderer only
 * draws the rows it's handed and opens a record through the callback.
 */
export type ListRendererProps = {
  /** Shared selection, including ids outside the visible feed window. */
  selectedIds?: string[];
  setSelection?: (ids: string[]) => void;
  selectAll?: () => void;
  clearSelection?: () => void;
  selectingAll?: boolean;
  allSelected?: boolean | "indeterminate";
  loading?: boolean;
  toolbar?: ReactNode;
  /** More keyset windows remain. Renderers with an inner scroll pane should request them there. */
  hasMore?: boolean;
  /** A next-window request is in flight. */
  loadingMore?: boolean;
  /** The last next-window request failed; keep a visible explicit retry control. */
  loadMoreFailed?: boolean;
  /** Request/retry the next window using the host's current query and cursor. */
  loadMore?: () => void;
  /** Total matching rows from the server, independent of loaded pages; null when unknown. */
  total?: number | null;
  /** All keyset windows loaded so far. */
  rows: EntityRecord[];
  /** The list's descriptor slice: entity route, title, resolved columns (labels/widgets/formats), write access. */
  list: { kind: string; name: string; title: string; columns: ListColumn[]; canWrite: boolean };
  /** Open a record's detail pane (no-op for rows without an id, e.g. register rows). */
  open: (row: EntityRecord) => void;
  /** The record's {@code onno://} detail route, or null when it has none. */
  openUrl: (row: EntityRecord) => string | null;
};
/** A list view component can set {@code ownsLoadingMore} to show its own loading feedback. */
export type ListRendererComponent = ComponentType<ListRendererProps> & { ownsLoadingMore?: boolean };

/** How the reference picker dialog (#411) presents one row: hidden, or disabled with a reason/badge. */
export type PickRowState = {
  hidden?: boolean;
  disabled?: boolean;
  reason?: string;
  badge?: string;
  tone?: string;
  /** Explicit badge colour (a decorator's `color`), overriding the tone. */
  color?: string;
};

/** Badge tones of a picker row; the same palette the dropdown's option badges use. */
const PICK_TONE_CLASS: Record<string, string> = {
  neutral: "bg-muted text-muted-foreground",
  success: "bg-emerald-500/15 text-emerald-700 dark:text-emerald-400",
  warning: "bg-amber-500/15 text-amber-700 dark:text-amber-400",
  danger: "bg-red-500/15 text-red-700 dark:text-red-400",
  accent: "bg-primary/15 text-primary",
};

/** A picker row's decorator badge, pinned to the row's trailing edge. */
export function PickRowBadge({ pick }: { pick: PickRowState | undefined }) {
  if (!pick?.badge) return null;
  return (
    <span
      className={cn(
        "pointer-events-none absolute right-2 top-1/2 -translate-y-1/2 rounded-control px-2 py-0.5 text-[11px] font-medium",
        !pick.color && (PICK_TONE_CLASS[pick.tone ?? "neutral"] ?? PICK_TONE_CLASS.neutral)
      )}
      style={pick.color ? { backgroundColor: pick.color, color: "#fff" } : undefined}
    >
      {pick.badge}
    </span>
  );
}

/**
 * Select mode (#411): the list as a reference picker. A click highlights one row, a double click
 * or Enter activates it (↑/↓ move the highlight while focus is inside the list or the enclosing
 * `[data-onno-picker]` element, e.g. the dialog's own search box), and the record-opening,
 * context-menu, batch-selection and clipboard behaviour is off. Rows stamp
 * `data-onno-pick-row` instead of `data-onno-row`, so the shell's row menu and shortcuts skip them.
 */
export type ListSelectMode = {
  /** The highlighted row id (controlled by the host). */
  highlightedId: string | null;
  onHighlight: (row: EntityRecord) => void;
  /** Double click / Enter on a row that is not disabled. */
  onActivate: (row: EntityRecord) => void;
  /** Per-row presentation; a hidden row is not drawn at all. */
  rowState?: (row: EntityRecord) => PickRowState | undefined;
  /** Every newly loaded batch of rows (flat windows and expanded groups alike). */
  onRowsLoaded?: (rows: EntityRecord[]) => void;
  /** The search query, when the host owns the search box (it then hides the toolbar's own). */
  query?: string;
  /** Host-supplied text for a cell (e.g. a computed column); undefined renders the cell as usual. */
  cellText?: (row: EntityRecord, col: ListColumn) => string | undefined;
};
/** A column the list can be grouped by; `date` columns bucket by a chosen day/month/year granularity. */
export type GroupableColumn = { columnName: string; label: string; date: boolean };
/** A per-group subtotal: an aggregate `fn` over `columnName`, shown with `format` and headed `label`. */
export type ListAggregate = { columnName: string; fn: string; label: string; format?: ValueFormat };
export type ListView = "table" | "map" | "custom" | "calendar" | "timeline";

/** List state a parent may own (see {@link EntityListView}'s `state`). Columns are data columns. */
export type ListStateProps = {
  view?: ListView;
  onViewChange?: (view: ListView) => void;
  /** The grouping column ("" = flat). */
  groupBy?: string;
  onGroupByChange?: (column: string) => void;
  sort?: { column: string | null; descending: boolean };
  onSortChange?: (sort: { column: string | null; descending: boolean }) => void;
  search?: string;
  onSearchChange?: (search: string) => void;
  /** Selected record ids. */
  selection?: string[];
  onSelectionChange?: (ids: string[]) => void;
};

export type ListDescriptor = {
  kind: "catalogs" | "documents" | "registers";
  name: string;
  title: string;
  columns: ListColumn[];
  searchable: boolean;
  sort: { column: string | null; descending: boolean };
  newUrl: string | null;
  /**
   * The viewer's write access on the entity (server-stamped from RBAC). When false the list
   * hides its write affordances — row Duplicate/Delete and batch delete — and stamps rows
   * data-onno-row-writable="0" so the shell's fallback row menu and shortcuts hide theirs too.
   * Absent (old server) means unknown; treat as writable, REST enforces regardless.
   */
  canWrite?: boolean;
  /** The entity's commands (record and collection) the viewer may run. */
  commands?: CommandDescriptor[];
  /**
   * True when some commands are late-bound. The list then refreshes the descriptors on each
   * context-menu open and selection; absent/false keeps static entities request-free.
   */
  dynamicCommands?: boolean;
  /** How each command is offered (the entity's `commands` customization). */
  commandPresentation?: Record<string, CommandPresentation>;
  filters?: ListFilterControl[];
  /** When set, the toolbar offers a Table ⇄ Map toggle that plots the rows as markers. */
  map?: ListMapConfig;
  /** When set, the toolbar offers a Calendar view over the same feed (the `calendar` customization). */
  calendar?: ListCalendarConfig;
  /** When set, the toolbar offers a Timeline (resource scheduler) view (the `timeline` customization). */
  timeline?: ListTimelineConfig;
  /** When set, the toolbar offers a Table ⇄ custom-view toggle. */
  custom?: ListCustomConfig;
  /** Columns offered in the "Group by ▾" picker; a `date` column buckets by day/month/year. */
  groupable?: GroupableColumn[];
  // `baseWhere` is a server-side constraint (a `where` predicate, sent as JSON) always applied on
  // top of the viewer's own filters. `defaultGroupBy` seeds the group-by column.
  baseWhere?: WhereClause;
  defaultGroupBy?: string;
  defaultGroupByDateGranularity?: "day" | "month" | "year";
  /** Removable initial selections keyed by a declared filter's key. */
  defaultFilters?: Record<string, string[]>;
  /** Per-group subtotals shown on each group header (and their display format). */
  aggregates?: ListAggregate[];
  /** Open every group band expanded instead of collapsed. */
  groupsExpanded?: boolean;
  selectionCheckboxes?: boolean;
  /** A component shown in the toolbar while rows are selected (the app's, passed directly). */
  selectionComponent?: ComponentType<{ ids: string[]; complete: () => void }>;
  /** Conditional row tint, evaluated per row (the app's `rowStyle`). */
  rowStyle?: (row: EntityRecord) => RowTone;
  /**
   * Read-only file downloads over the selected rows (e.g. batch print), contributed server-side by a
   * SelectionDownloadContributor. Offered to every reader: the endpoint enforces access itself.
   */
  selectionDownloads?: SelectionDownload[];
  pageSize: number;
  // Embedded in an authored page (PageBuilder.list) rather than rendered as its own route surface.
  // The page already pads its content, so the list drops its horizontal gutter to align its table
  // with the page's other full-width components (settings, action sections).
  embedded?: boolean;
  /** Embedded operational surface that fills the host height and scrolls internally. */
  fill?: boolean;
  // Override the data feed URL. Catalogs/documents page from /api/list/{kind}/{name}; a register
  // has no such route, so its descriptor points the list at /api/list/registers/{name}/movements
  // (or /balance). When unset, the default {kind}/{name} feed is used.
  feed?: string;
  /**
   * "Open in list" (#410): the record id the list route was opened on (`?focus=<id>`). The first
   * window is requested around that record (the feed's `focus` param), the list scrolls to it and
   * highlights it, opening flat and in the table view. A record the feed cannot show (filtered out,
   * out of scope, deleted) leaves the list at the top with a notice.
   */
  focus?: string;
};

/** The keyset feed envelope: a window plus its cursors in both directions. */
type FeedWindow = {
  rows: EntityRecord[];
  nextCursor: string | null;
  hasMore: boolean;
  prevCursor?: string | null;
  hasPrevious?: boolean;
  /** Present when the window was anchored on the requested `focus` record. */
  focus?: string;
  total?: number;
};

export const ROW_H = 40;


// URL helpers for the row context menu this list owns:
// open url → delete-action url / route path / absolute shareable link.
function rowDeleteUrl(rowUrl: string): string {
  return "onno://delete/" + rowUrl.slice("onno://".length);
}
function rowShareableUrl(rowUrl: string): string {
  return window.location.origin + withBasePath("/" + rowUrl.slice("onno://".length));
}
function rowOpenUrl(kind: string, name: string, id: string): string {
  return `onno://${kind}/${name}/${id}`;
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

/** The underlying cell string: a resolved ref/enum label, the posted badge, or the raw value. */
function rawCellValue(row: EntityRecord, col: ListColumn, t: Translate): string {
  const key = responseKey(col);
  if (key === "posted" || col.columnName === "_posted") {
    return t(row[key] === true ? "status.posted" : "status.draft");
  }
  const v = row[`${key}Display`] ?? row[`${col.columnName}_display`] ?? row[key];
  return v == null ? "" : String(v);
}

/**
 * The displayed text: secret mask, image placeholder, or the value run through .format(...).
 * A full date-time with no authored format falls back to the locale default — a raw
 * "2026-07-05T00:58:48.232+00:00" is machine output, never a display value.
 */
function displayCellValue(raw: string, col: ListColumn): string {
  if (raw === "__SECRET_SET__") return "•••• set";
  return formatValue(raw, col.format) ?? formatTimestampDefault(raw) ?? raw;
}

/** One list cell: an image thumbnail for image/avatar columns, otherwise formatted text. */
export function ListCell({ row, col }: { row: EntityRecord; col: ListColumn }) {
  const t = useMessages();
  // A ref to a record the viewer may not read: the server withheld its id and label.
  if (isRestrictedRef(row, responseKey(col)) || isRestrictedRef(row, col.columnName)) {
    return <RestrictedRef />;
  }
  const raw = rawCellValue(row, col, t);
  if (col.render) return <>{col.render(row[responseKey(col)] ?? null, row)}</>;
  if (isImageInput(col.input) && raw) {
    if (looksLikeImageUrl(raw)) {
      const avatar = col.input === "avatar";
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
    return <span className="text-xs text-destructive">Invalid media URL</span>;
  }
  // A color input's column holds a #RRGGBB value: show a swatch beside the hex text.
  if (col.input === "color" && raw && /^#(?:[0-9a-f]{3}|[0-9a-f]{6})$/i.test(raw)) {
    return (
      <span className="flex min-w-0 items-center gap-2">
        <span
          aria-hidden
          className="size-3.5 shrink-0 rounded-full border border-border"
          style={{ backgroundColor: raw }}
        />
        <span className="truncate text-foreground">{raw}</span>
      </span>
    );
  }
  // A Ref column whose target carries an avatar (resolved into FieldAvatar by logical reads): show
  // the photo as a small round avatar beside the display text (issue #182). The data is already
  // here; only image/avatar-widget columns drew it before, so a plain Ref cell stayed text-only.
  const key = responseKey(col);
  const refAvatar = row[`${key}Avatar`] ?? row[`${col.columnName}_avatar`];
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
  // A status-coloured enum value: the @EnumLabel(color = …) hex rides as FieldColor,
  // so paint the label as a pill the colour of the spreadsheet cell it replaces.
  const pill = enumPillStyle((row[`${key}Color`] ?? row[`${col.columnName}_color`]) as string | undefined);
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
  // Plain text — auto-linkify bare http(s) URLs into clickable links (same treatment comment bodies
  // get), so a value like a chat/file URL is reachable straight from the cell. linkify's anchors
  // stopPropagation, so opening a link doesn't also trigger the row's navigate-to-detail click.
  const display = displayCellValue(raw, col);
  return <span className="truncate text-foreground">{linkify(display)}</span>;
}

/** The body a list opens on: the first alternate view declared as its default, else the grid. */
function defaultViewOf(list: { custom?: ListCustomConfig; map?: ListMapConfig; calendar?: ListCalendarConfig; timeline?: ListTimelineConfig }): ListView {
  if (list.custom?.defaultView) return "custom";
  if (list.map?.defaultView) return "map";
  if (list.timeline?.defaultView) return "timeline";
  if (list.calendar?.defaultView) return "calendar";
  return "table";
}

/**
 * Shared look for a faceted-filter trigger (a shadcn/Linear-style filter "chip"): a pill that reads
 * as an inert prompt while empty (dashed border, muted, a leading +) and as a committed selection
 * once it carries a value (solid accented border, foreground text, the picked value(s) shown inline
 * as small badges). One consistent control replaces the old "Label: <raw control>" inline pairs.
 */
const facetTriggerCls = (active: boolean) =>
  cn(
    "onno-facet-trigger",
    buttonVariants({ variant: "subtle", size: "toolbar" }),
    active
      ? "border-solid border-primary/40 bg-primary/5 text-foreground hover:bg-primary/10"
      : ""
  );

// Options-facet lists longer than this get a search row; shorter ones stay a plain scan.
const FACET_SEARCH_THRESHOLD = 8;

function optionInitials(name: string | undefined): string {
  if (!name) return "?";
  const parts = name.trim().split(/[\s._-]+/).filter(Boolean);
  if (parts.length >= 2) return (parts[0][0] + parts[1][0]).toUpperCase();
  return (name.trim().slice(0, 2) || "?").toUpperCase();
}

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

function FacetOptionRow({
  option,
  selected,
  multi,
  touch,
  onSelect,
}: {
  option: FilterOption;
  selected: boolean;
  multi: boolean;
  touch?: boolean;
  onSelect: () => void;
}) {
  return (
    <button
      key={option.value}
      type="button"
      onClick={onSelect}
      className={cn(
        "flex w-full cursor-pointer items-center text-left transition-colors",
        touch
          ? "min-h-11 gap-3 rounded-field px-3 py-2 text-[15px] active:bg-accent"
          : "gap-2 rounded-field px-2 py-1.5 text-sm hover:bg-accent"
      )}
    >
      <span
        className={cn(
          "flex shrink-0 items-center justify-center border transition-colors",
          touch ? "size-5" : "size-4",
          multi ? (touch ? "rounded-[5px]" : "rounded-[4px]") : "rounded-full",
          selected ? "border-primary bg-primary text-primary-foreground" : "border-input"
        )}
      >
        {selected ? <Check className={touch ? "size-3.5" : "size-3"} /> : null}
      </span>
      {option.avatarUrl ? (
        <Avatar className={cn("shrink-0", touch ? "size-7" : "size-6")}>
          <AvatarImage src={option.avatarUrl} alt="" />
          <AvatarFallback className={touch ? "text-[11px]" : "text-[10px]"}>
            {optionInitials(option.label)}
          </AvatarFallback>
        </Avatar>
      ) : option.color ? (
        <span
          aria-hidden="true"
          className={cn("shrink-0 rounded-full", touch ? "size-2.5" : "size-2")}
          style={{ backgroundColor: option.color }}
        />
      ) : null}
      <span className="truncate">{option.label}</span>
    </button>
  );
}

/**
 * A single- or multi-select facet. A popover of options with a check on each chosen row; the trigger
 * shows the picked labels inline (up to two, then a "{n} selected" summary). Single-select replaces
 * the prior value and closes; multi-select toggles and stays open. Values are keyed by {@code value}
 * (what the query binds); labels are what the user reads. Empty → no constraint.
 */
export function OptionsFacet({
  inline = false,
  label,
  options,
  multi,
  selected,
  onChange,
}: {
  inline?: boolean;
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

  if (inline) return <fieldset className="w-full min-w-0 space-y-2">
    <legend className="text-xs font-semibold text-muted-foreground">{label}</legend>
    {searchable && <Input aria-label={`${t("list.searchLabel")} ${label}`} value={query} onChange={event => setQuery(event.target.value)} placeholder={t("list.searchLabel")} />}
    <div className="flex max-h-64 flex-col gap-1 overflow-y-auto">
      <Button size="sm" className="min-h-10 w-full justify-between rounded-field px-3 text-left" variant={!active ? "secondary" : "ghost"} aria-pressed={!active} onClick={() => onChange([])}>{t("list.all")}{!active && <Check className="size-4 shrink-0" />}</Button>
      {visible.map(option => <Button key={option.value} size="sm" className="min-h-10 w-full justify-between gap-3 whitespace-normal rounded-field px-3 text-left" variant={selected.includes(option.value) ? "secondary" : "ghost"} aria-pressed={selected.includes(option.value)} onClick={() => toggle(option.value)}><span className="flex min-w-0 items-center gap-2">{option.color && <span aria-hidden="true" className="size-2 shrink-0 rounded-full" style={{backgroundColor:option.color}} />}{option.label}</span>{selected.includes(option.value) && <Check className="size-4 shrink-0" />}</Button>)}
      {searchable && !visible.length && <p className="text-sm text-muted-foreground">{t("empty.noMatches")}</p>}
    </div>
  </fieldset>;

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

  // Compact layouts: 44px option rows in a framed overlay instead of a small anchored popover.
  if (touch) {
    return (
      <>
        {trigger}
        <FacetSheet open={open}
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
                <FacetOptionRow
                  key={o.value}
                  option={o}
                  selected={on}
                  multi={multi}
                  touch
                  onSelect={() => toggle(o.value)}
                />
              );
            })}
          </div>
        </FacetSheet>
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
              <FacetOptionRow
                key={o.value}
                option={o}
                selected={on}
                multi={multi}
                onSelect={() => toggle(o.value)}
              />
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
        <FacetSheet open={open}
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
      </>
    );
  }

  // Desktop: the chip itself expands inline into the input — same morph as the list-wide search.
  return <SearchChip label={label} value={value} onCommit={onCommit} openWidth={208} debounceMs={300} />;
}

/**
 * The list-wide search dressed as a facet chip — the exact TextFacet grammar (icon + label, the
 * committed query as a value badge, dashed→solid/accented borders), except clicking doesn't open a
 * popover: the chip itself animates out into an inline input, with the icon staying put as the
 * input's leading glyph. It collapses back on blur/Esc — the committed query then reads as a badge
 * in the collapsed chip, like any other applied facet. Width tweens between measured pixel values,
 * so the label-sized chip and the fixed-width input morph smoothly in both directions.
 */
function SearchChip({
  label,
  value,
  onCommit,
  openWidth,
  debounceMs = 0,
}: {
  /** Collapsed-chip label, no ellipsis — the input's placeholder appends one on open. */
  label: string;
  value: string;
  onCommit: (next: string) => void;
  openWidth: number;
  /** >0: keystrokes commit after this pause (TextFacet semantics); 0: every keystroke commits. */
  debounceMs?: number;
}) {
  // Starts open when a query was restored (e.g. from the URL)… and immediately blurs closed into
  // the badge form, which is fine — the query stays visible either way.
  const [open, setOpen] = useState(() => !!value);
  // The draft text; equal to `value` unless a debounced commit is pending.
  const [text, setText] = useState(value);
  // Keep the draft in sync if the committed value is reset externally (e.g. a clear-all).
  useEffect(() => setText(value), [value]);
  useEffect(() => {
    if (debounceMs <= 0) return;
    const id = window.setTimeout(() => {
      if (text !== value) onCommit(text);
    }, debounceMs);
    return () => window.clearTimeout(id);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [text]);
  const wrapRef = useRef<HTMLDivElement | null>(null);
  const inputRef = useRef<HTMLInputElement | null>(null);
  const chipRef = useRef<HTMLButtonElement | null>(null);
  // The collapsed chip's natural width — the tween needs numbers on both ends (auto doesn't
  // animate). Re-measured whenever the collapsed content changes (the value badge grows it);
  // null until the first measure, which renders width:auto with no tween (first paint only).
  // +2 covers the container's own left+right border, which the border-box width must include —
  // without it the button's right padding is clipped by exactly the border width.
  const [chipW, setChipW] = useState<number | null>(null);
  useLayoutEffect(() => {
    if (!open && chipRef.current) setChipW(chipRef.current.offsetWidth + 2);
  }, [open, value]);
  const active = !!value;
  return (
    <div
      ref={wrapRef}
      className={cn(
        "relative h-8 shrink-0 overflow-hidden rounded-control border transition-[width,border-color,background-color] duration-300 ease-out focus-within:ring-2 focus-within:ring-ring focus-within:ring-offset-1 focus-within:ring-offset-background",
        open
          ? "border-solid border-input bg-transparent"
          : active
            ? "border-solid border-primary/40 bg-primary/5 text-foreground hover:bg-primary/10"
            : "border-dashed border-input bg-transparent text-muted-foreground hover:border-solid hover:bg-accent hover:text-foreground"
      )}
      style={{ width: open ? openWidth : chipW ?? undefined }}
    >
      <button
        ref={chipRef}
        type="button"
        onClick={() => {
          setOpen(true);
          // preventScroll: the browser would otherwise instantly yank the still-narrow chip into
          // view mid-tween; the smooth scroll below reveals the full-width input instead, once
          // the width transition has finished (300ms) — matters inside the scrolling facet rail.
          requestAnimationFrame(() => inputRef.current?.focus({ preventScroll: true }));
          window.setTimeout(() => {
            wrapRef.current?.scrollIntoView({ behavior: "smooth", block: "nearest", inline: "nearest" });
          }, 320);
        }}
        tabIndex={open ? -1 : 0}
        aria-expanded={open}
        aria-label={label}
        title={label}
        className={cn(
          // A touch more right padding than the px-3 facet chips: the leading icon shifts the
          // label right, so a symmetric inset reads tighter on the trailing side.
          "inline-flex h-full items-center gap-1.5 whitespace-nowrap pl-3 pr-4 text-xs font-medium outline-none",
          open && "pointer-events-none"
        )}
      >
        <Search className="size-3.5 shrink-0 opacity-60" />
        <span className={cn("transition-opacity duration-200", open && "opacity-0")}>{label}</span>
        {active ? (
          <span className={cn("inline-flex items-center gap-1.5 transition-opacity duration-200", open && "opacity-0")}>
            <FacetDivider />
            <FacetValueBadge>
              <span className="max-w-28 truncate">{value}</span>
            </FacetValueBadge>
          </span>
        ) : null}
      </button>
      <Input
        ref={inputRef}
        value={text}
        onChange={(e) => {
          setText(e.target.value);
          if (debounceMs <= 0) onCommit(e.target.value);
        }}
        onBlur={() => {
          if (text !== value) onCommit(text); // flush a pending debounced commit
          setOpen(false);
        }}
        onKeyDown={(e) => {
          if (e.key === "Escape") {
            setText("");
            onCommit("");
            e.currentTarget.blur();
          } else if (e.key === "Enter") {
            e.currentTarget.blur();
          }
        }}
        placeholder={`${label}…`}
        tabIndex={open ? 0 : -1}
        className={cn(
          "absolute inset-0 h-full w-full rounded-none border-0 bg-transparent pl-8 pr-3 text-xs transition-opacity duration-200 focus-visible:ring-0 focus-visible:ring-offset-0",
          open ? "opacity-100" : "pointer-events-none opacity-0"
        )}
      />
    </div>
  );
}

/** Short, locale-aware day label ("May 9") for a stored ISO date, shown inside the date-range chip. */
function fmtDay(iso: string): string {
  try {
    return new Intl.DateTimeFormat(localeOutsideReact(), { month: "short", day: "numeric" }).format(
      new Date(`${iso}T00:00`)
    );
  } catch {
    return iso;
  }
}

// (FacetSheet, SheetClearButton, SheetDoneButton, useTouchLayout live in ui/facet-sheet — the
// dashboard's TimeRangeFacet shares the same touch shell.)

/**
 * A number-range facet: one chip that opens two number inputs (from / to) over a numeric column.
 * Either bound may stay empty ("above 1000", "below 5"); both are inclusive. State is the same
 * {from,to} pair the date range uses, bound to {@code ge}/{@code le} — here plain decimals,
 * normalized (decimal comma → dot, spaces dropped) before they are committed. Typing commits after
 * a short pause so the list doesn't refetch per keystroke; Enter commits at once and closes.
 */
function NumberRangeFacet({
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
  const [draft, setDraft] = useState({ from, to });
  // Keep the inputs in sync if the committed value is reset externally (e.g. a clear-all) — but
  // leave text that already means the committed value alone, so a half-typed "12," isn't rewritten.
  useEffect(
    () =>
      setDraft((d) =>
        normalizeNumberBound(d.from) === from && normalizeNumberBound(d.to) === to ? d : { from, to }
      ),
    [from, to]
  );
  const commit = (next: { from: string; to: string }) => {
    const clean = { from: normalizeNumberBound(next.from), to: normalizeNumberBound(next.to) };
    if (clean.from !== from || clean.to !== to) onChange(clean);
  };
  useEffect(() => {
    const id = window.setTimeout(() => commit(draft), 400);
    return () => window.clearTimeout(id);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [draft.from, draft.to]);
  const active = !!from || !!to;
  const touch = useTouchLayout();
  const clear = () => {
    setDraft({ from: "", to: "" });
    onChange({ from: "", to: "" });
    setOpen(false);
  };

  const trigger = (
    <button type="button" className={facetTriggerCls(active)} onClick={touch ? () => setOpen(true) : undefined}>
      <ArrowLeftRight className="size-3.5 shrink-0 opacity-60" />
      <span className="whitespace-nowrap">{label}</span>
      {active ? (
        <>
          <FacetDivider />
          <FacetValueBadge>{numberRangeLabel(from, to)}</FacetValueBadge>
        </>
      ) : null}
    </button>
  );
  const input = (bound: "from" | "to", cls: string, autoFocus = false) => (
    <Input
      autoFocus={autoFocus}
      inputMode="decimal"
      aria-label={`${label}: ${t(bound === "from" ? "list.rangeFrom" : "list.rangeTo")}`}
      placeholder={t(bound === "from" ? "list.rangeFrom" : "list.rangeTo")}
      value={draft[bound]}
      onChange={(e) => setDraft((d) => ({ ...d, [bound]: e.target.value }))}
      onKeyDown={(e) => {
        if (e.key === "Enter") {
          commit(draft);
          setOpen(false);
        }
      }}
      className={cls}
    />
  );

  if (touch) {
    return (
      <>
        {trigger}
        <FacetSheet open={open}
          label={label}
          onClose={() => setOpen(false)}
          footer={
            <>
              <SheetClearButton onClick={clear}>{t("list.clear")}</SheetClearButton>
              <SheetDoneButton
                onClick={() => {
                  commit(draft);
                  setOpen(false);
                }}
              >
                {t("list.done")}
              </SheetDoneButton>
            </>
          }
        >
          <div className="flex items-center gap-2 px-4 py-3">
            {input("from", "h-11 rounded-field text-[15px]", true)}
            <span aria-hidden="true" className="text-muted-foreground">–</span>
            {input("to", "h-11 rounded-field text-[15px]")}
          </div>
        </FacetSheet>
      </>
    );
  }

  return (
    <Popover open={open} onOpenChange={setOpen}>
      <FacetTip hint={t("list.filterHint", { label })}>
        <PopoverTrigger asChild>{trigger}</PopoverTrigger>
      </FacetTip>
      <PopoverContent align="start" className="w-auto p-2">
        <div className="flex items-center gap-2">
          {input("from", "h-8 w-28 text-xs", true)}
          <span aria-hidden="true" className="text-muted-foreground">–</span>
          {input("to", "h-8 w-28 text-xs")}
        </div>
        {active ? (
          <div className="mt-2 flex justify-end border-t pt-2">
            <button
              type="button"
              onClick={clear}
              className="rounded-field px-2 py-1 text-xs text-muted-foreground hover:bg-accent hover:text-foreground"
            >
              {t("list.clear")}
            </button>
          </div>
        ) : null}
      </PopoverContent>
    </Popover>
  );
}

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
        <FacetSheet open={open}
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
  granularity: "day" | "month" | "year";
  onGranularity: (v: "day" | "month" | "year") => void;
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
        <FacetSheet open={open}
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

const NO_FILTERS: ListFilterControl[] = [];

/**
 * The list's filters, with the choices of `optionsFrom` filters loaded from that catalog's records
 * (cached by TanStack Query, so every list filtering by the same catalog shares one read).
 */
function useFilterOptions(filters: ListFilterControl[] | undefined): ListFilterControl[] {
  const sources = useMemo(
    () => [...new Set((filters ?? []).map((f) => f.optionsFrom).filter((s): s is string => !!s))],
    [filters],
  );
  const results = useQueries({
    queries: sources.map((catalog) => ({
      queryKey: ["onno", catalog, "filter-options"],
      queryFn: async (): Promise<FilterOption[]> =>
        (await api.listCatalog(catalog))
          .filter((r) => r.id != null && !r.deletionMark)
          .map((r) => {
            const photo = IDENTITY_AVATAR_FIELDS.map((f) => r[f]).find((v) => typeof v === "string" && v);
            return { value: String(r.id), label: String(r.description || r.code || r.id), ...(photo ? { avatarUrl: String(photo) } : {}) };
          }),
    })),
  }, queryClient);
  const loaded = results.map((r) => r.data);
  const key = loaded.map((d) => (d ? d.length : -1)).join(",");
  return useMemo(() => {
    if (!filters?.length) return NO_FILTERS;
    if (!sources.length) return filters;
    return filters.map((f) => {
      const i = f.optionsFrom ? sources.indexOf(f.optionsFrom) : -1;
      return i >= 0 && loaded[i] ? { ...f, options: loaded[i]! } : f;
    });
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [filters, sources, key]);
}

export function EntityListView({
  list,
  headerExtra,
  toolbarContent,
  toolbarFooter,
  toolbarPlacement = "header",
  toolbarOptions,
  queryParams,
  renderer,
  refreshKey,
  initialPage,
  selectMode,
  state,
}: {
  list: ListDescriptor;
  /**
   * List state the parent owns. Each value passed is controlled (the parent's `on…Change` must
   * update it); each one left out the list keeps itself, starting from the descriptor's defaults.
   */
  state?: ListStateProps;
  /** Reference-picker mode (#411); absent for every ordinary list. */
  selectMode?: ListSelectMode;
  renderer?: ComponentType<ListRendererProps>;
  refreshKey?: number;
  /** One-use bootstrap response. Query excludes cursor/limit/count; must match the active feed and filters. */
  initialPage?: { feed: string; query: string; rows: EntityRecord[]; total?: number; nextCursor: string | null; hasMore: boolean };
  /** Additional server-validated constraints supplied by a composed workspace. */
  queryParams?: Record<string, string[]>;
  // Host-provided control rendered in the toolbar right after the title — the register
  // surface parks its Balance/Movements toggle here so the view switch lives with the list's own
  // controls instead of floating above the card.
  headerExtra?: ReactNode;
  /** Full-width secondary controls inside the same toolbar surface. */
  toolbarContent?: ReactNode;
  /** Workspace navigation actions, separate from query controls. */
  toolbarFooter?: ReactNode;
  /** Custom renderers may place the host controls within their own workspace. */
  toolbarPlacement?: "header" | "renderer";
  toolbarOptions?: {
    title?: boolean; count?: boolean; viewSwitcher?: boolean; search?: boolean;
    controls?: string[]; views?: ListView[];
    filterKeys?: string[]; actionKeys?: string[];
    compactPresentation?: "inline" | "sheet";
    /** Show persistent navigation beside a custom workspace when its container has room. */
    filterSidebar?: boolean;
  };
}) {
  const bootstrapPage = useRef(initialPage);
  const { kind, name, columns, pageSize } = list;
  const listExportRoles = useListExportRoles();
  const user = useAuthUser();
  const [exportBusy, setExportBusy] = useState(false);
  const [exportOpen, setExportOpen] = useState(false);
  const touchToolbar = useTouchLayout();
  const [filterSheetOpen, setFilterSheetOpen] = useState(false);
  const [sidebarCollapsed, setSidebarCollapsed] = useState(false);
  const sidebarId = useId();
  const [toolbarElement, setToolbarElement] = useState<HTMLDivElement | null>(null);
  const [availableToolbarWidth, setAvailableToolbarWidth] = useState<number | null>(null);
  useLayoutEffect(() => {
    if (!toolbarElement) return;
    const measure = () => {
      const width = toolbarElement.getBoundingClientRect().width;
      if (width > 0) setAvailableToolbarWidth(width);
    };
    measure();
    const observer = new ResizeObserver(measure);
    observer.observe(toolbarElement);
    return () => observer.disconnect();
  }, [toolbarElement]);
  const mobileControls = toolbarOptions?.filterSidebar || (touchToolbar || (availableToolbarWidth != null && availableToolbarWidth < 560))
    && toolbarOptions?.compactPresentation === "sheet";
  const showControl = (key: string) => !toolbarOptions?.controls || toolbarOptions.controls.includes(key);
  const queryParamSig = JSON.stringify(queryParams ?? {});
  const t = useMessages();
  // Ambient presence: other users viewing each row's record, looked up by row id at render time.
  const viewersById = useViewersById();
  const rootRef = useRef<HTMLDivElement | null>(null);
  const [workspaceWidth, setWorkspaceWidth] = useState(0);
  useLayoutEffect(() => {
    const root = rootRef.current;
    if (!root || !toolbarOptions?.filterSidebar) return;
    const measure = () => setWorkspaceWidth(root.getBoundingClientRect().width);
    measure();
    const observer = new ResizeObserver(measure);
    observer.observe(root);
    return () => observer.disconnect();
  }, [toolbarOptions?.filterSidebar]);
  const scrollRef = useRef<HTMLDivElement | null>(null);
  const gen = useRef(0); // bumped on each query so a stale window fetch is ignored
  const windowRequest = useRef<number | null>(null);
  const refreshQueued = useRef(false);
  const [queuedRefresh, setQueuedRefresh] = useState(0);
  const finishWindow = useCallback((generation: number) => {
    if (generation !== gen.current) return;
    windowRequest.current = null;
    if (refreshQueued.current) {
      refreshQueued.current = false;
      // Run after the completed rows have rendered, including an appended page.
      setQueuedRefresh(value => value + 1);
    }
  }, []);

  // ---- URL-persisted view state (standalone list routes only) ----
  // A non-embedded list owns its route, so we mirror its search / sort / filters / view into the
  // query string: the view then survives a list remount (a remount used to wipe the filters) and a filtered list becomes a shareable,
  // bookmarkable link. Embedded lists (dashboard cards, master-detail panes) keep purely local state
  // — several of them on one page would collide on the single shared URL.
  const urlSynced = !list.embedded;
  // Captured once at mount; used only to seed the initial state below.
  const initialParams = useMemo(
    () => new URLSearchParams(urlSynced ? window.location.search : ""),
    // eslint-disable-next-line react-hooks/exhaustive-deps
    []
  );

  // The currently displayed rows (null = first load, still blank). Keyset windows append as you scroll.
  const [pageRows, setPageRows] = useState<EntityRecord[] | null>(null);
  const rowsRef = useRef<EntityRecord[] | null>(null);
  rowsRef.current = pageRows;
  // The exact total when requested, or null when unknown.
  const [total, setTotal] = useState<number | null>(null);
  // The opaque cursor for the next window, whether more remain, and whether a
  // load-more is in flight (the ref guards against firing a second fetch before the first lands).
  const cursorRef = useRef<string | null>(null);
  const [hasMore, setHasMore] = useState(true);
  const [loadingMore, setLoadingMore] = useState(false);
  const loadingMoreRef = useRef(false);
  const [loadMoreFailed, setLoadMoreFailed] = useState(false);
  // "Open in list" (#410). A window opened on a record does not start at the top of the list: the
  // feed's prevCursor loads the rows above it as the body scrolls up (prepended, scroll position
  // kept). `pendingFocus` is consumed by the first full load only; `focusedId` is the highlighted row.
  const pendingFocusRef = useRef<string | null>(list.focus ?? null);
  const messagesRef = useRef(t);
  messagesRef.current = t;
  const [focusedId, setFocusedId] = useState<string | null>(null);
  const prevCursorRef = useRef<string | null>(null);
  const [hasPrevious, setHasPrevious] = useState(false);
  const loadingPrevRef = useRef(false);
  // Rows prepended above the first loaded window, so row keys stay stable while the list grows upward.
  const [headOffset, setHeadOffset] = useState(0);
  // The list can be squeezed narrow (e.g. a master-detail split) while the viewport stays wide,
  // so the toolbar layout is driven by the measured container width, not a media query.
  const [toolbarWidth, setToolbarWidth] = useState<number | null>(null);
  // Route-surface height: the list fills the space to the window bottom and scrolls internally,
  // so the page never scrolls — only the list body does. Null until measured
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
  const [sort, setSort] = useControllable<{ column: string | null; descending: boolean }>(state?.sort, state?.onSortChange, () => {
    const col = urlSynced ? initialParams.get("sort") : null;
    return col ? { column: col, descending: initialParams.get("dir") === "desc" } : list.sort;
  });
  const [query, setQuery] = useControllable(state?.search, state?.onSearchChange, () => (urlSynced ? initialParams.get("q") ?? "" : ""));
  const [debounced, setDebounced] = useState(() => (urlSynced ? initialParams.get("q") ?? "" : ""));
  // The toolbar's facet rail: it scrolls horizontally when the chips don't fit, and the
  // left/right fades are the "there's more" signal. Re-measured on every render (chips appear and
  // resize with filter state) plus on rail scroll — the setter's referential guard keeps the
  // every-render call from looping.
  const railRef = useRef<HTMLDivElement | null>(null);
  const [railEdges, setRailEdges] = useState({ left: false, right: false });
  const measureRail = useCallback(() => {
    const el = railRef.current;
    if (!el) return;
    const left = el.scrollLeft > 1;
    const right = el.scrollLeft < el.scrollWidth - el.clientWidth - 1;
    setRailEdges((p) => (p.left === left && p.right === right ? p : { left, right }));
  }, []);
  useLayoutEffect(() => {
    measureRail();
  });
  // Grouping: which column the list is grouped by ("" = flat), and — for a date column — the bucket
  // granularity. Picking a column swaps the flat table for the collapsible grouped view.
  const groupable = useMemo(() => list.groupable ?? [], [list.groupable]);
  const aggregates = useMemo(() => list.aggregates ?? [], [list.aggregates]);
  // Opens on the descriptor's default group-by (PageBuilder.list default view), else flat ("").
  // A list opened on a record ("Open in list") starts flat: a focus window is a flat keyset window.
  const [groupBy, setGroupBy] = useControllable(state?.groupBy, state?.onGroupByChange, list.focus ? "" : list.defaultGroupBy ?? "");
  const [granularity, setGranularity] = useState<"day" | "month" | "year">(
    list.defaultGroupByDateGranularity ?? "month"
  );
  const groupCol = groupable.find((g) => g.columnName === groupBy);
  // Keys of in-flight commands (`key` for toolbar, `key:id` for a row) → spinner + disabled.
  const [pending, setPending] = useState<Set<string>>(new Set());
  // Batch selection: row ids picked with ⌘/Ctrl-click (toggle) and Shift-click (range from the
  // anchor). Plain click keeps its open-the-record meaning; with a selection active it clears the
  // selection instead. Esc or the toolbar ✕ clears too.
  const controlledSelection = useMemo(() => (state?.selection ? new Set(state.selection) : undefined), [state?.selection]);
  const onSelectionChange = state?.onSelectionChange;
  const [selected, setSelected] = useControllable<Set<string>>(
    controlledSelection,
    useMemo(() => (onSelectionChange ? (next: Set<string>) => onSelectionChange([...next]) : undefined), [onSelectionChange]),
    () => new Set(),
  );
  const selAnchorRef = useRef<number | null>(null);
  // The row right-click menu this list owns (built-ins + record commands + batch ops); the native
  // event is preventDefault-ed so the browser's own menu stays closed.
  const [rowMenu, setRowMenu] = useState<{
    x: number;
    y: number;
    id: string;
    url: string;
    row: EntityRecord;
    /** Set by a cell-menu right-click (a column's `cellMenu`): render ONLY this command group, flat. */
    only?: string;
    batch?: boolean;
  } | null>(null);
  // Two-step batch delete: first click arms ("sure?"), second click runs.
  const [armedDelete, setArmedDelete] = useState(false);
  // Table vs map vs custom view (each alternative only offered when the list declares it). The map
  // fetches its own rows, so the grid's search/filters/sort are hidden while it's shown; a custom
  // renderer shares the grid's feed, so the toolbar stays live.
  const [view, setView] = useControllable<ListView>(state?.view, state?.onViewChange, () => {
    // "Open in list" lands on a highlighted table row, whatever the default view.
    if (list.focus) return "table";
    if (urlSynced && (list.map || list.custom || list.calendar || list.timeline)) {
      const v = initialParams.get("view");
      if (v === "calendar" && list.calendar) return "calendar";
      if (v === "timeline" && list.timeline) return "timeline";
      if (v === "map" && list.map) return "map";
      if (v === "custom" && list.custom) return "custom";
      if (v === "table") return "table";
    }
    return defaultViewOf(list);
  });

  const SelectionWidget = list.selectionComponent;
  const CustomRenderer = (renderer ?? list.custom?.component) as ListRendererComponent | undefined;
  const ownsLoadingMore = Boolean(CustomRenderer?.ownsLoadingMore);
  // Calendar/timeline read the catalog/document list feed, so a register never offers them.
  const schedulable = kind === "catalogs" || kind === "documents";
  const availableViews = ([
    "table",
    ...(list.map ? ["map"] : []),
    ...(schedulable && list.calendar ? ["calendar"] : []),
    ...(schedulable && list.timeline ? ["timeline"] : []),
    ...(CustomRenderer ? ["custom"] : []),
  ] as ListView[]);
  const configuredViews = toolbarOptions?.views?.filter(value => availableViews.includes(value));
  const allowedViews = configuredViews?.length ? configuredViews : availableViews;
  const activeView = allowedViews.includes(view) ? view : allowedViews[0];
  const mapMode = !!list.map && activeView === "map";
  const customMode = activeView === "custom" && !!CustomRenderer;
  const calendarMode = activeView === "calendar" && !!list.calendar;
  const timelineMode = activeView === "timeline" && !!list.timeline;
  // Calendar and timeline keep the toolbar's search and filters live (they query the list feed
  // with them) but, like the map, replace the grid — so grouping and row selection step aside.
  const scheduleMode = calendarMode || timelineMode;
  // Grouped view replaces the flat table (never shown together with the map or a custom body).
  const grouped = !!groupBy && !mapMode && !customMode && !scheduleMode;
  // Catalog/document rows open their record; register rows (movements/balance) have no detail route,
  // so they aren't clickable. Also gates the single-row live patch (registers have no row identity).
  const openable = kind === "catalogs" || kind === "documents";
  // RBAC write access (see ListDescriptor.canWrite): gates the row menu's Duplicate/Delete
  // and batch delete. Absent flag (old server) stays permissive; REST enforces regardless.
  const canWrite = list.canWrite !== false;
  // Select mode (#411): the list picks a row for a reference field instead of opening records.
  const picking = selectMode != null;
  const selectModeRef = useRef(selectMode);
  selectModeRef.current = selectMode;
  // Where windows are fetched from: a register points the list at its own feed; others use the
  // standard /api/list/{kind}/{name} route.
  const feedBase = list.feed ?? `/api/list/${kind}/${name}`;
  // Route surface (own page) vs embedded in an authored dashboard page. Only the route surface takes
  // over the viewport height and scrolls internally; embedded flows with the host page.
  const surfaceMode = !list.embedded || list.fill === true;
  // A full-height list that is a route's whole content (an inbox, a scheduler) gets the same gutter
  // as a default list page, unless a <Page> around it already provides one.
  const inPage = useContext(PageGutterContext);
  const ownsSurfacePadding = !list.embedded || (list.fill === true && !inPage);

  // Last successfully resolved late-bound descriptors. A failed menu-open refresh deliberately
  // keeps this snapshot (or the startup static descriptors before the first success).
  const [resolvedCommands, setResolvedCommands] = useState<CommandDescriptor[] | null>(null);
  const [resolvedCommandStates, setResolvedCommandStates] =
    useState<Record<string, Record<string, CommandState>>>({});
  useEffect(() => {
    setResolvedCommands(null);
    setResolvedCommandStates({});
  }, [kind, name, list.dynamicCommands]);
  // Every command with its presentation; hidden ones stay runnable by extensions (execute(key)).
  const allCommands = useMemo(
    () => presentCommands(resolvedCommands ?? list.commands, list.commandPresentation),
    [resolvedCommands, list.commands, list.commandPresentation]
  );
  const collectionCommands = useMemo(
    () => allCommands.filter((c) => c.target === "collection" && c.placement !== "hidden"),
    [allCommands]
  );
  const recordCommands = useMemo(
    () => allCommands.filter((c) => c.target !== "collection" && c.placement !== "hidden"),
    [allCommands]
  );
  // "row"-placed commands also render as inline icon buttons on every row; every record command
  // lives in the row's right-click menu (grouped ones under their group's submenu, in order).
  const rowButtonCommands = recordCommands.filter((c) => c.placement === "row");
  const rowSubmenus = useMemo(() => {
    const order: string[] = [];
    const byGroup = new Map<string, ListCommand[]>();
    for (const c of recordCommands) {
      if (!c.group) continue;
      if (!byGroup.has(c.group)) {
        byGroup.set(c.group, []);
        order.push(c.group);
      }
      byGroup.get(c.group)!.push(c);
    }
    return order.map((label) => ({ label, commands: byGroup.get(label)! }));
  }, [recordCommands]);
  // A built-in the app's `commands` customization hides (duplicate / delete) leaves the row menu.
  const builtInHidden = (key: string) => normalizePresentation(list.commandPresentation?.[key]).placement === "hidden";
  // A selection download's icon: the app's `commands` customization under its key, else "download".
  const downloadIcon = (key: string) => {
    const icon = normalizePresentation(list.commandPresentation?.[key]).icon;
    return icon && typeof icon !== "function" ? icon : "download";
  };
  // Stable reference so the fetch/reset effects don't churn when there are no filters.
  const filters = useFilterOptions(list.filters);

  // Rebuild filter state from the URL (each filter's `f.<key>` param, mirroring the write below).
  // Unknown/blank params yield an empty {} for that filter, i.e. no constraint.
  const readFiltersFromUrl = useCallback(
    (params: URLSearchParams) =>
      Object.fromEntries(
        filters.map((f) => {
          const p = `f.${f.key}`;
          if (f.type === "dateRange" || f.type === "numberRange") {
            const raw = (bound: string) => params.get(`${p}.${bound}`) ?? "";
            const from = f.type === "numberRange" ? normalizeNumberBound(raw("from")) : raw("from");
            const to = f.type === "numberRange" ? normalizeNumberBound(raw("to")) : raw("to");
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
  const [filterState, setFilterState] = useState<FilterState>(() =>
    urlSynced ? readFiltersFromUrl(initialParams) : initialFilterState(filters, list.defaultFilters)
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
    const defaultViewName = defaultViewOf(list);
    const currentViewName: ListView =
      mapMode ? "map" : customMode ? "custom" : calendarMode ? "calendar" : timelineMode ? "timeline" : "table";
    if (currentViewName !== defaultViewName) params.set("view", currentViewName);
    for (const f of filters) {
      const st = filterStateRef.current[f.key];
      if (!st) continue;
      const p = `f.${f.key}`;
      if (f.type === "dateRange" || f.type === "numberRange") {
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
  }, [urlSynced, filterSig, debounced, sort.column, sort.descending, mapMode, customMode, calendarMode, timelineMode]);

  // How many filters currently constrain the query — drives the "Clear all" affordance on the
  // filter bar. A filter counts as active only when it actually narrows rows (a picked option, some
  // typed text, at least one end of a date range), not merely because it's declared.
  const activeFilterCount = useMemo(
    () =>
      filters.reduce((n, f) => {
        const st = filterState[f.key];
        if (!st) return n;
        if (f.type === "dateRange" || f.type === "numberRange") return n + (st.from || st.to ? 1 : 0);
        if (f.type === "multiOptions") return n + ((st.in?.length ?? 0) > 0 ? 1 : 0);
        if (f.type === "contains" || f.type === "startsWith") return n + (st.text?.trim() ? 1 : 0);
        return n + (st.eq ? 1 : 0);
      }, 0),
    [filters, filterState]
  );
  const clearAllFilters = () =>
    setFilterState(Object.fromEntries(filters.map((f) => [f.key, {}])));

  // Responsive action sizing, driven by the measured list width and the controls actually present
  // (not a fixed media query). `constrainedActions` lets search yield width when the fixed right-side
  // controls need more room. `compact` additionally drops button labels to icons.
  const controlsWidthEstimate =
    (list.map ? 150 : 0) +
    (list.calendar ? 150 : 0) +
    (list.timeline ? 150 : 0) +
    (list.custom && CustomRenderer ? 150 : 0) +
    (list.searchable ? 36 : 0) + // the collapsed search icon; the expanded input may wrap, which is fine
    collectionCommands.length * 100 +
    (list.newUrl ? 90 : 0);
  const constrainedActions = toolbarWidth != null && toolbarWidth < controlsWidthEstimate + 170;
  const compact = toolbarWidth != null && toolbarWidth < 560;

  // Column grid template: an authored px width, else a flexible track. A trailing fixed column
  // holds the per-row command buttons when any are placed on rows. The floor is 0 here because the
  // table's overall min-width (below) keeps the columns readable — when the card is narrower
  // than that, the table scrolls horizontally instead of cramming the columns.
  const DATA_COL_MIN = 150;
  const ACTION_COL_W = rowButtonCommands.length ? rowButtonCommands.length * 36 + 8 : 0;
  const showSelectionCheckboxes = list.selectionCheckboxes === true && openable;
  const template = (showSelectionCheckboxes ? "20px " : "") +
    columns
      .map((c) => {
        const px = listColumnPixelWidth(c.width);
        return px == null ? "minmax(0,1fr)" : `${px}px`;
      })
      .concat(ACTION_COL_W ? [`${ACTION_COL_W}px`] : [])
      .join(" ");

  // When any loaded row has viewers, widen the whole list's left padding so the presence face-pile sits
  // in its own gutter instead of over the first column. Applied to the header too, so columns stay
  // aligned; it collapses back when nobody is viewing (a rare, gentle shift).
  const listHasPresence = openable && (pageRows ?? []).some((r) => {
    const id = r ? entityRowId(r) : null;
    return id != null && viewersById.has(id);
  });
  const leftPad = listHasPresence ? "pl-11 pr-4" : "px-4";

  // Natural minimum width of the table: each column at its authored width (or DATA_COL_MIN),
  // plus the action column, the 12px inter-column gaps and the 32px (px-4) row padding. When the
  // card is wider, the grid fills it (1fr expands); when narrower, this drives a horizontal scroll.
  const trackCount = columns.length + (ACTION_COL_W ? 1 : 0) + (showSelectionCheckboxes ? 1 : 0);
  const minTableWidth =
    columns.reduce((sum, c) => {
      const px = listColumnPixelWidth(c.width);
      return sum + (px ?? DATA_COL_MIN);
    }, 0) +
    ACTION_COL_W + (showSelectionCheckboxes ? 20 : 0) +
    Math.max(0, trackCount - 1) * 12 +
    32;

  // Debounce the search box.
  useEffect(() => {
    const t = window.setTimeout(() => setDebounced(query.trim()), 250);
    return () => window.clearTimeout(t);
  }, [query]);

  // Build the shared query params (sort + search + declarative filters). The keyset cursor/limit
  // parameters are added by the caller.
  const baseWhereSig = list.baseWhere ? JSON.stringify(list.baseWhere) : "";
  const buildParams = useCallback((search = debounced) => {
    const params = new URLSearchParams();
    for (const [key, values] of Object.entries(queryParams ?? {})) for (const value of values) params.append(key, value);
    // A default-view base filter (PageBuilder.list) is a server-side constraint applied on top of the
    // viewer's own filters/search — sent on every window fetch, grouped or flat.
    if (list.baseWhere && Object.keys(list.baseWhere).length) params.set("where", JSON.stringify(list.baseWhere));
    if (sort.column) {
      params.set("sort", sort.column);
      params.set("dir", sort.descending ? "desc" : "asc");
    }
    if (search) params.set("q", search);
    // Declarative filters → eq/in/like/prefix/ge/le params (column,value); the server re-validates
    // the column. Several filters AND together; a multi-select sends one `in` per picked value.
    for (const f of filters) {
      const st = filterStateRef.current[f.key];
      if (!st) continue;
      if (f.type === "dateRange" || f.type === "numberRange") {
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
  }, [queryParamSig, baseWhereSig, sort.column, sort.descending, debounced, filters, filterSig]);

  const standardFeed = `/api/list/${kind}/${name}`;
  const encodedFeed = `/api/list/${kind}/${encodeURIComponent(name)}`;
  const registerView = kind === "registers" ? (["movements", "balance"] as const).find(view =>
    list.feed === `${standardFeed}/${view}` || list.feed === `${encodedFeed}/${view}`) : undefined;
  const exportableFeed = kind === "registers" ? !!registerView
    : !list.feed || list.feed === standardFeed || list.feed === encodedFeed;
  const canExport = exportableFeed && columns.length > 0
    && user?.roles.some(role => listExportRoles.includes(role.trim().toUpperCase().replace(/^ROLE_/, "")));

  async function exportList(format: "csv" | "xlsx") {
    setExportOpen(false);
    setExportBusy(true);
    const params = buildParams(query.trim());
    const queryValues: Record<string, string[]> = Object.fromEntries(
      [...new Set(params.keys())].map(key => [key, params.getAll(key)]),
    );
    try {
      await downloadJson(`/api/list/export/${kind}/${encodeURIComponent(name)}`, {
        format,
        view: registerView,
        columns: columns.map(column => ({ key: column.columnName, label: column.label })),
        query: queryValues,
        groupBy: grouped ? groupBy : undefined,
        granularity: grouped ? granularity : undefined,
      }, `${name}.${format}`);
    } catch (error) {
      notifyError(error, { format: () => error instanceof ApiError && error.status === 413
        ? t("list.export.tooManyRows")
        : error instanceof ApiError && error.status === 422
          ? t("list.export.textTooLong") : t("list.export.failed") });
    } finally {
      setExportBusy(false);
    }
  }

  // The shared query (sort + search + filters) the grouped view fetches groups/rows with, as a
  // string so its effects can depend on it.
  const groupParamsBase = useMemo(() => buildParams().toString(), [buildParams]);
  // The same query for the calendar/timeline views, minus the grid's sort: entries are placed by
  // their dates, and a sort change must not refetch the period.
  const scheduleQuery = useMemo(() => {
    const params = new URLSearchParams(groupParamsBase);
    params.delete("sort");
    params.delete("dir");
    return params.toString();
  }, [groupParamsBase]);

  // Load the first keyset window. Bumps the generation so a slow fetch that lands after a newer
  // query is discarded. A hard load blanks to the skeleton; a soft live refresh keeps the current
  // rows on screen and swaps them in place when data lands.
  const loadInitial = useCallback(
    (soft = false) => {
      // Live events must not supersede a request for the same query: a busy inbox
      // could otherwise discard every response forever. Keep one follow-up refresh.
      if (soft && (windowRequest.current !== null || loadingMoreRef.current)) {
        refreshQueued.current = true;
        return;
      }
      refreshQueued.current = false;
      const myGen = ++gen.current;
      windowRequest.current = myGen;
      loadingMoreRef.current = false;
      setLoadingMore(false);
      setLoadMoreFailed(false);
      if (!soft) {
        cursorRef.current = null;
        prevCursorRef.current = null;
        setHasPrevious(false);
        setFocusedId(null);
        setPageRows(null);
      }
      const params = buildParams();
      // "Open in list": the first hard load asks for the window containing the record. A soft
      // refresh of a window that does not start at the top re-anchors on the first visible row, so
      // a live change neither yanks the list back to the top nor shifts what the user is reading.
      const requestedFocus = !soft ? pendingFocusRef.current : null;
      if (!soft) pendingFocusRef.current = null;
      let keep: { id: string; offset: number } | null = null;
      if (requestedFocus) {
        params.set("focus", requestedFocus);
      } else if (soft && prevCursorRef.current) {
        const el = scrollRef.current;
        const top = el?.scrollTop ?? 0;
        const idx = Math.min(Math.floor(top / ROW_H), (rowsRef.current?.length ?? 1) - 1);
        const anchorRow = idx >= 0 ? rowsRef.current?.[idx] : undefined;
        const anchorId = anchorRow ? entityRowId(anchorRow) : null;
        if (anchorId) {
          params.set("focus", anchorId);
          keep = { id: anchorId, offset: top - idx * ROW_H };
        }
      }
      const bootstrap = bootstrapPage.current;
      bootstrapPage.current = undefined;
      const canonical = (value: URLSearchParams) => { value.sort(); return value.toString(); };
      if (!soft && bootstrap?.feed === feedBase && canonical(new URLSearchParams(bootstrap.query)) === canonical(new URLSearchParams(params))) {
        setTotal(typeof bootstrap.total === "number" ? bootstrap.total : null);
        cursorRef.current = bootstrap.nextCursor;
        setHasMore(bootstrap.hasMore);
        setPageRows(bootstrap.rows);
        finishWindow(myGen);
        return;
      }
      // A soft reload re-fetches everything already loaded in one window (so a live change
      // doesn't yank the user back to the top); a hard load fetches just the first window.
      const loaded = rowsRef.current?.length ?? 0;
      const want = soft ? Math.min(MAX_WINDOW, Math.max(pageSize, loaded)) : pageSize;
      params.set("limit", String(want));
      params.set("count", "exact"); // real total for the header — an estimate drifts (and is absent on H2)
      fetch(`${feedBase}?${params.toString()}`, { credentials: "include" })
        .then((r) => (r.ok ? r.json() : Promise.reject(new Error(`HTTP ${r.status}`))))
        .then((data: FeedWindow) => {
          if (myGen !== gen.current) return;
          setTotal(typeof data.total === "number" ? data.total : null);
          cursorRef.current = data.nextCursor ?? null;
          setHasMore(data.hasMore);
          prevCursorRef.current = data.prevCursor ?? null;
          setHasPrevious(!!data.hasPrevious);
          setHeadOffset(0);
          setPageRows(data.rows ?? []);
          if (requestedFocus) {
            if (data.focus) {
              setFocusedId(data.focus);
              scrollPlanRef.current = { id: data.focus, mode: "center", offset: 0 };
            } else {
              // Filtered out, outside the record scope or deleted: the feed answers with the top.
              toast.info(messagesRef.current("list.focusMissing"));
            }
          } else if (keep && data.focus) {
            scrollPlanRef.current = { id: keep.id, mode: "keep", offset: keep.offset };
          }
          if (!soft && !data.focus && scrollRef.current) scrollRef.current.scrollTop = 0;
        })
        .catch(() => {
          if (myGen === gen.current && !soft) setPageRows([]);
        })
        .finally(() => finishWindow(myGen));
    },
    [feedBase, pageSize, buildParams, finishWindow]
  );

  // Where to scroll once the rows just loaded have rendered: centre the focused record, or keep an
  // anchor row at the offset it had before a soft refresh re-fetched the window around it.
  const scrollPlanRef = useRef<{ id: string; mode: "center" | "keep"; offset: number } | null>(null);
  useLayoutEffect(() => {
    const plan = scrollPlanRef.current;
    const el = scrollRef.current;
    if (!plan || !el || !pageRows) return;
    scrollPlanRef.current = null;
    const idx = pageRows.findIndex((row) => entityRowId(row) === plan.id);
    if (idx < 0) return;
    el.scrollTop = plan.mode === "center"
      ? Math.max(0, idx * ROW_H - Math.max(0, el.clientHeight - ROW_H) / 2)
      : idx * ROW_H + plan.offset;
    setScrollTop(el.scrollTop);
  }, [pageRows]);

  // Prepend the window above the first loaded row (a focused window's prevCursor), keeping the
  // rows on screen where they are: the scroll offset grows by the height of the inserted rows.
  const loadPrevious = useCallback(() => {
    if (loadingPrevRef.current || !prevCursorRef.current) return;
    loadingPrevRef.current = true;
    const myGen = gen.current;
    const params = buildParams();
    params.set("limit", String(pageSize));
    params.set("before", prevCursorRef.current);
    fetch(`${feedBase}?${params.toString()}`, { credentials: "include" })
      .then((r) => (r.ok ? r.json() : Promise.reject(new Error(`HTTP ${r.status}`))))
      .then((data: FeedWindow) => {
        if (myGen !== gen.current) return;
        prevCursorRef.current = data.prevCursor ?? null;
        setHasPrevious(!!data.hasPrevious);
        const current = rowsRef.current ?? [];
        const seen = new Set(current.map(entityRowId).filter(Boolean));
        const above = (data.rows ?? []).filter((row) => {
          const id = entityRowId(row);
          return !id || !seen.has(id);
        });
        if (!above.length) return;
        const el = scrollRef.current;
        if (el) {
          el.scrollTop += above.length * ROW_H;
          setScrollTop(el.scrollTop);
        }
        setHeadOffset((offset) => offset + above.length);
        setPageRows([...above, ...current]);
      })
      .catch(() => {})
      .finally(() => {
        loadingPrevRef.current = false;
      });
  }, [feedBase, pageSize, buildParams]);

  // Append the next window, seeking from the current cursor. Guarded so only one
  // load-more runs at a time and a window from a superseded query is dropped.
  const loadMore = useCallback(() => {
    if (loadingMoreRef.current || !cursorRef.current) return;
    if (windowRequest.current !== null) {
      // An explicit next-page request takes priority over a background refresh.
      // Its response must not later replace the page we are about to append.
      ++gen.current;
      windowRequest.current = null;
      refreshQueued.current = true;
    }
    loadingMoreRef.current = true;
    setLoadingMore(true);
    setLoadMoreFailed(false);
    const myGen = gen.current;
    const params = buildParams();
    params.set("limit", String(pageSize));
    params.set("cursor", cursorRef.current);
    fetch(`${feedBase}?${params.toString()}`, { credentials: "include" })
      .then((r) => (r.ok ? r.json() : Promise.reject(new Error(`HTTP ${r.status}`))))
      .then((data: { rows: EntityRecord[]; nextCursor: string | null; hasMore: boolean }) => {
        if (myGen !== gen.current) return; // a newer query superseded this window
        cursorRef.current = data.nextCursor ?? null;
        setHasMore(data.hasMore);
        setPageRows((cur) => {
          const seen = new Set((cur ?? []).map(entityRowId).filter(Boolean));
          const appended = (data.rows ?? []).filter(row => {
            const id = entityRowId(row);
            if (!id) return true; // Register rows may have no entity id.
            if (seen.has(id)) return false;
            seen.add(id);
            return true;
          });
          return [...(cur ?? []), ...appended];
        });
      })
      .catch(() => { if (myGen === gen.current) setLoadMoreFailed(true); })
      .finally(() => {
        if (myGen !== gen.current) return;
        loadingMoreRef.current = false;
        setLoadingMore(false);
        finishWindow(myGen);
      });
  }, [feedBase, pageSize, buildParams, finishWindow]);

  // Load the initial window on first mount or query change. Reset the cursor so the query re-seeks
  // from the top rather than an old position.
  useEffect(() => {
    cursorRef.current = null;
    setHasMore(true);
    loadInitial(false);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [feedBase, pageSize, sort.column, sort.descending, debounced, filterSig, queryParamSig]);

  useEffect(() => { if (refreshKey) loadInitial(true); }, [refreshKey, loadInitial]);
  useEffect(() => { if (queuedRefresh) loadInitial(true); }, [queuedRefresh]); // eslint-disable-line react-hooks/exhaustive-deps
  useEffect(() => () => {
    ++gen.current;
    windowRequest.current = null;
    refreshQueued.current = false;
  }, []);

  // Track the list width so the toolbar can stack / collapse responsively (see below).
  useLayoutEffect(() => {
    const el = rootRef.current;
    if (!el) return;
    const apply = () => setToolbarWidth(el.clientWidth);
    apply();
    const ro = new ResizeObserver(apply);
    ro.observe(el);
    return () => ro.disconnect();
  }, []);

  // Route surface: fill the space from the list's top to the bottom of the page scroller, so the
  // list body scrolls internally and the page never scrolls. The root then
  // carries its own padding, so the gap under the card matches the top/left gap.
  //
  // The limit is the enclosing scroller's content box, NOT window.innerHeight: the shell keeps
  // padding below the scroller, so sizing to the window overshoots by that padding and leaves the
  // page a few px of scroll play (the whole card wiggles and the gaps look uneven). Authored pages
  // also wrap embedded blocks in padded regions; subtract their trailing padding because it
  // sits after the list but still contributes to the scroller's content height. The offset is
  // computed in the scroller's content coordinates (scroll-invariant), so the height converges to
  // exactly zero page scroll even if measured while the page is scrolled.
  //
  // We must measure our top live, not just once: the workspace tab bar, the sidebar and web-font
  // loading all shift it *after* mount, and a wrapper with `overflow: clip`
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
        const trailingPadding = trailingPaddingTo(el, sc);
        setSurfaceH(Math.max(240, Math.floor(sc.clientHeight - offsetInContent - trailingPadding)));
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
  }, [surfaceMode]);

  // Embedded mode only: cap the scroll body at the space to the visible bottom of the enclosing
  // scroller so a big in-page list scrolls internally rather than stretching the host page.
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
      setMaxBodyH(Math.max(160, Math.floor(bottom - top - 20)));
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
  }, [surfaceMode, grouped, mapMode, customMode, scheduleMode]);

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
  }, [surfaceMode, grouped, mapMode, customMode, scheduleMode]);

  // Body scroll: drive the virtual window and fetch the next keyset window as the end nears.
  const onScroll = useCallback(() => {
    const el = scrollRef.current;
    if (!el) return;
    setScrollTop(el.scrollTop);
    if (
      hasMore &&
      !loadingMoreRef.current &&
      el.scrollTop + el.clientHeight >= el.scrollHeight - ROW_H * OVERSCAN
    ) {
      loadMore();
    }
    if (hasPrevious && !loadingPrevRef.current && el.scrollTop <= ROW_H * OVERSCAN) {
      loadPrevious();
    }
  }, [hasMore, loadMore, hasPrevious, loadPrevious]);

  // Re-fetch the loaded rows in place (after a command or a live event) — a soft refresh that
  // keeps the visible rows until the new data lands, so it never flashes the skeleton.
  const reload = useCallback(() => {
    loadInitial(true);
  }, [loadInitial]);

  // A command dialog waiting for input: the clicked command plus its target — one row id, a batch
  // of ids, or neither (collection command). Submitting runs the command with the values.
  const [formPrompt, setFormPrompt] = useState<{ command: ListCommand; id?: string; ids?: string[] } | null>(null);
  const feedback = useCommandFeedback();

  // Run a command: POST /api/commands and apply the CommandResult — toast, refresh. A command with
  // inputs first opens the dialog to collect them (formInputs arrive on the second, submitting
  // call). While the (possibly slow/async) handler runs, the button shows a spinner and is
  // disabled, so there's feedback and no double-submit. (api.runCommand is CSRF-aware and toasts
  // failures.)
  const runCommand = useCallback(
    (command: ListCommand, id?: string, formInputs?: CommandFormValues, propagateError = false): Promise<void> | void => {
      if (command.form?.length && !formInputs) {
        setFormPrompt({ command, id });
        return;
      }
      const k = id ? `${command.key}:${id}` : command.key;
      setPending((s) => {
        if (s.has(k)) return s; // already running — ignore the re-click
        const n = new Set(s);
        n.add(k);
        return n;
      });
      return api
        .runCommand(kind, name, command.key, id, formInputs)
        .then((result) => {
          feedback.applyResult(result, { refresh: () => invalidateEntity(`${kind}/${name}`, id ? [id] : undefined) });
        })
        .catch((error) => {
          if (propagateError) throw error;
          feedback.fromError(error);
        })
        .finally(() =>
          setPending((s) => {
            if (!s.has(k)) return s;
            const n = new Set(s);
            n.delete(k);
            return n;
          })
        );
    },
    [reload, kind, name, feedback]
  );

  const selectAllRequest = useRef<AbortController | null>(null);
  const [selectingAll, setSelectingAll] = useState(false);
  const [allMatchingIds, setAllMatchingIds] = useState<Set<string> | null>(null);
  const clearSelection = useCallback(() => {
    selectAllRequest.current?.abort();
    selectAllRequest.current = null;
    setSelectingAll(false);
    setAllMatchingIds(null);
    setSelected(new Set());
    selAnchorRef.current = null;
  }, []);
  useEffect(() => () => { selectAllRequest.current?.abort(); }, []);

  // Walk the scoped feed independently of the visible window, including collapsed groups and
  // rows before a focus window. Commit only a complete result; failures keep the old selection.
  const selectAllMatching = useCallback(async () => {
    if (selectAllRequest.current) return;
    const controller = new AbortController();
    selectAllRequest.current = controller;
    setSelectingAll(true);
    const ids = new Set<string>();
    const params = buildParams();
    for (const key of ["cursor", "before", "focus", "count"]) params.delete(key);
    params.set("limit", String(MAX_WINDOW));
    const cursors = new Set<string>();
    try {
      while (true) {
        const response = await fetch(`${feedBase}?${params}`, { credentials: "include", signal: controller.signal });
        if (!response.ok) throw new Error(`HTTP ${response.status}`);
        const data: FeedWindow = await response.json();
        if (controller.signal.aborted) return;
        for (const row of data.rows ?? []) {
          const id = entityRowId(row);
          if (id) ids.add(id);
        }
        if (!data.hasMore) break;
        if (!data.nextCursor || cursors.has(data.nextCursor)) throw new Error("Missing or repeated selection cursor");
        cursors.add(data.nextCursor);
        params.set("cursor", data.nextCursor);
      }
      setAllMatchingIds(ids);
      setSelected(ids);
      selAnchorRef.current = 0;
    } catch {
      if (!controller.signal.aborted) toast.error(t("list.selectAllFailed"));
    } finally {
      if (selectAllRequest.current === controller) {
        selectAllRequest.current = null;
        setSelectingAll(false);
      }
    }
  }, [buildParams, feedBase, t]);
  const allSelectionState = useMemo<boolean | "indeterminate">(() => selected.size === 0 ? false
    : allMatchingIds && selected.size === allMatchingIds.size && [...allMatchingIds].every(id => selected.has(id))
      ? true : "indeterminate", [selected, allMatchingIds]);
  const rendererSelectedIds = useMemo(() => openable && !picking ? Array.from(selected) : [], [selected, openable, picking]);
  const setRendererSelection = useCallback((ids: string[]) => {
    clearSelection();
    setSelected(new Set(ids));
  }, [clearSelection]);
  const toggleAllSelection = (checked: boolean) => { if (checked) void selectAllMatching(); else clearSelection(); };

  // Open the row context menu — shared by the flat table's rows and the grouped view's (the menu
  // element itself renders once at the list root, so either body can drive it). Right-clicking
  // outside an active selection drops the selection first, like the flat rows always did.
  const openRowMenu = useCallback(
    (menu: { x: number; y: number; id: string; url: string; row: EntityRecord; only?: string; batch?: boolean }) => {
      if (selected.size && !selected.has(menu.id)) clearSelection();
      setArmedDelete(false);
      setRowMenu(menu);
      if (list.dynamicCommands) {
        // Fetch on every open: additions, removals, titles, order, forms and handlers are all
        // live. Failure is intentionally swallowed — the currently rendered descriptors remain a
        // last-known fallback and the next open retries.
        void api.getCommands(kind, name, menu.id)
          .then((response) => {
            setResolvedCommands(response.commands);
            setResolvedCommandStates((current) => ({
              ...current,
              [menu.id]: response.state ?? {},
            }));
          })
          .catch(() => {});
      }
    },
    [selected, clearSelection, list.dynamicCommands, kind, name]
  );

  // A changed query invalidates the selection's row set — indices shift, rows drop out.
  useEffect(() => {
    clearSelection();
  }, [kind, name, feedBase, debounced, filterSig, queryParamSig, baseWhereSig, sort.column, sort.descending, groupBy, granularity, view, clearSelection]);

  // A confirmation applies only to the exact selection that armed it.
  useEffect(() => { setArmedDelete(false); }, [selected]);
  useEffect(() => {
    if (!list.dynamicCommands || !selected.size) return;
    let cancelled = false;
    void api.getCommands(kind, name, [...selected][0])
      .then(response => { if (!cancelled) setResolvedCommands(response.commands); })
      .catch(() => {});
    return () => { cancelled = true; };
  }, [selected, list.dynamicCommands, kind, name]);

  // Esc clears the selection. Captured + consumed: the shell's own Esc handler closes the
  // focused tab (and only yields to an already-defaultPrevented event), so clearing a selection
  // must swallow the key or Esc would clear AND close the surface in one press. An open
  // menu/dialog/drawer is a higher layer — it takes this press (and consumes it itself); the
  // selection clears on the next one. Cascade: layer → selection → tab.
  useEffect(() => {
    if (selected.size === 0 && !selectingAll) return;
    const onKey = (e: KeyboardEvent) => {
      if (e.key !== "Escape" || e.defaultPrevented) return;
      if (isInteractiveLayerOpen()) return;
      e.preventDefault();
      clearSelection();
    };
    window.addEventListener("keydown", onKey, true);
    return () => window.removeEventListener("keydown", onKey, true);
  }, [selected.size, selectingAll, clearSelection]);

  // One batch runs at a time. A ref (not the pending set) is the re-entrancy guard: a setState
  // updater's side effects aren't guaranteed to run synchronously (React only eager-evaluates the
  // first queued update), so "did I just add the key?" can't be derived from setPending. The
  // pending key still drives the disabled state of the menu items.
  const batchBusyRef = useRef(false);

  // Download a file built from the selected rows (batch print and similar read-only exports).
  const runSelectionDownload = useCallback(async (download: SelectionDownload, ids: string[]) => {
    const k = `download:${download.key}`;
    setPending((s) => new Set(s).add(k));
    const toastId = toast.loading(t("list.download.running", { label: download.title, n: ids.length }));
    try {
      await downloadSelection(download.url, ids, `${download.key}.zip`);
      toast.dismiss(toastId);
    } catch (error) {
      // Turns the loading toast into the error; downloadSelection never toasts, so this is the only one.
      // eslint-disable-next-line onno/notify-caught-error
      toast.error(error instanceof Error && error.message ? error.message : t("list.download.failed", { label: download.title }),
        { id: toastId });
    } finally {
      setPending((s) => {
        const n = new Set(s);
        n.delete(k);
        return n;
      });
    }
  }, [t]);

  // Run a record command over every selected row. The API client chunks selections above the
  // server's 500-id request ceiling and folds them into one {ok, failed, total}, so the list still
  // owns one loading→summary toast and one refresh for the complete selection.
  // Per-result navigate doesn't apply — a batch can't open N panes.
  const runBatchCommand = useCallback(
    async (command: ListCommand, ids: string[], formInputs?: CommandFormValues, propagateFeedback = false) => {
      if (command.form?.length && !formInputs) {
        // Collect the inputs once up front; the submitted values apply to every selected row.
        setFormPrompt({ command, ids });
        return;
      }
      if (batchBusyRef.current) return;
      batchBusyRef.current = true;
      const k = `batch:${command.key}`;
      setPending((s) => new Set(s).add(k));
      const toastId = toast.loading(t("batch.running", { label: command.title, n: ids.length }));
      try {
        const r = await api.runCommandBatch(kind, name, command.key, ids, formInputs);
        if (r.feedback) {
          toast.dismiss(toastId);
          if (propagateFeedback && r.feedbackRejected) {
            return Promise.reject(new ApiError(r.feedback.message || r.feedback.title || "Command rejected", 422, undefined, r.feedback));
          }
          feedback.present(r.feedback);
        } else {
          (r.ok === r.total ? toast.success : toast.error)(
            t("batch.done", { label: command.title, ok: r.ok, n: r.total }),
            { id: toastId }
          );
        }
        clearSelection();
        reload();
      } catch (error) {
        // the api layer already toasted the failure — just retire the loading toast
        toast.dismiss(toastId);
        if (propagateFeedback) throw error;
      } finally {
        batchBusyRef.current = false;
        setPending((s) => {
          const n = new Set(s);
          n.delete(k);
          return n;
        });
      }
    },
    [reload, clearSelection, t, kind, name, feedback]
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
  // window) and swap it where it sits — no cache wipe, no skeleton flash, no scroll jump. If the row
  // isn't in the loaded window we ignore it (it'll load fresh on scroll); if it has left the result
  // set (e.g. deletion-marked via an update) we fall back to a window reload.
  const patchRow = useCallback(
    (id: string) => {
      setPageRows((current) => {
        if (!current || !current.some((r) => r && entityRowId(r) === id)) return current;
        // Fetch just this row (same decorated shape as a keyset row) and splice it in place.
        fetch(`${feedBase}?ids=${encodeURIComponent(id)}`, { credentials: "include" })
          .then((r) => (r.ok ? r.json() : Promise.reject(new Error(`HTTP ${r.status}`))))
          .then((data: { rows: EntityRecord[] }) => {
            const fresh = data.rows?.find((row) => entityRowId(row) === id);
            if (fresh) {
              setPageRows((rows) =>
                rows ? rows.map((r) => (entityRowId(r) === id ? fresh : r)) : rows
              );
            } else {
              reload(); // row dropped out of the set — refresh the loaded window
            }
          })
          .catch(() => {});
        return current;
      });
    },
    [feedBase, reload]
  );

  // Live updates arrive through the query cache: a change to rows already loaded patches just those
  // rows; anything else (a create/delete, a command's refresh, a register change) reloads the visible
  // window — the row count and ordering shift, so a window refresh is the correct, cheap response.
  useEntityChanges(`${kind}/${name}`, (ids) => {
    if (ids && openable) ids.forEach(patchRow);
    else reload();
  });

  // Rows currently loaded, and — the header count — the exact total when known, else the loaded
  // count. In select mode a row the picker would not offer (PickRowState.hidden) is never drawn.
  const pickRowState = selectMode?.rowState;
  const loadedRows = useMemo(
    () => (pageRows == null ? [] : pickRowState ? pageRows.filter((row) => !pickRowState(row)?.hidden) : pageRows),
    [pageRows, pickRowState]
  );
  const countValue = total ?? (pageRows == null ? null : loadedRows.length);

  // Select mode: a host-owned search box drives the list query.
  const hostQuery = selectMode?.query;
  useEffect(() => {
    if (hostQuery !== undefined) setQuery(hostQuery);
  }, [hostQuery]);

  // Select mode: report each newly loaded batch, so the picker can resolve how to present it.
  const reportedRowsRef = useRef<EntityRecord[] | null>(null);
  useEffect(() => {
    if (!picking || pageRows == null || reportedRowsRef.current === pageRows) return;
    reportedRowsRef.current = pageRows;
    selectModeRef.current?.onRowsLoaded?.(pageRows);
  }, [picking, pageRows]);

  // Select mode keyboard: ↑/↓ move the highlight over the drawn rows and Enter activates it, while
  // focus is inside this list (its search box included). The highlighted row is scrolled into view.
  useEffect(() => {
    if (!picking) return;
    const onKey = (e: KeyboardEvent) => {
      if (e.defaultPrevented || e.altKey || e.metaKey || e.ctrlKey) return;
      if (e.key !== "ArrowDown" && e.key !== "ArrowUp" && e.key !== "Enter") return;
      const root = rootRef.current;
      const scope = root?.closest("[data-onno-picker]") ?? root;
      if (!scope || !scope.contains(document.activeElement)) return;
      const mode = selectModeRef.current;
      if (!mode || grouped) return;
      const ids = loadedRows.map((r) => entityRowId(r));
      const current = mode.highlightedId == null ? -1 : ids.indexOf(mode.highlightedId);
      if (e.key === "Enter") {
        const row = current >= 0 ? loadedRows[current] : undefined;
        if (!row) return;
        e.preventDefault();
        if (!mode.rowState?.(row)?.disabled) mode.onActivate(row);
        return;
      }
      if (!loadedRows.length) return;
      e.preventDefault();
      const next = e.key === "ArrowDown"
        ? Math.min(loadedRows.length - 1, current + 1)
        : Math.max(0, current < 0 ? 0 : current - 1);
      mode.onHighlight(loadedRows[next]);
      const scroller = scrollRef.current;
      if (scroller) {
        const top = next * ROW_H;
        if (top < scroller.scrollTop) scroller.scrollTop = top;
        else if (top + ROW_H > scroller.scrollTop + scroller.clientHeight) {
          scroller.scrollTop = top + ROW_H - scroller.clientHeight;
        }
      }
      if (next >= loadedRows.length - 3 && hasMore) loadMore();
    };
    window.addEventListener("keydown", onKey);
    return () => window.removeEventListener("keydown", onKey);
  }, [picking, grouped, loadedRows, hasMore, loadMore]);

  // Virtual window: render only rows overlapping the viewport (± overscan), padding the scroller
  // above/below with the height of rows not drawn.
  const virtual = pageRows != null;
  const startIdx = virtual ? Math.max(0, Math.floor(scrollTop / ROW_H) - OVERSCAN) : 0;
  const endIdx = virtual
    ? Math.min(loadedRows.length, Math.ceil((scrollTop + viewportH) / ROW_H) + OVERSCAN)
    : loadedRows.length;
  const visibleRows = pageRows == null ? [] : loadedRows.slice(startIdx, endIdx);
  const padTop = startIdx * ROW_H;
  const padBottom = Math.max(0, (loadedRows.length - endIdx) * ROW_H);

  // The custom renderer's contract (see ListRendererProps): a stable descriptor slice plus
  // ref-stable open callbacks, so a memoized renderer isn't re-rendered by unrelated list state.
  const rendererList = useMemo(
    () => ({ kind, name, title: list.title, columns, canWrite }),
    [kind, name, list.title, columns, canWrite]
  );
  const rendererOpenUrl = useCallback(
    (row: EntityRecord) => (openable && entityRowId(row) ? `onno://${kind}/${name}/${entityRowId(row)}` : null),
    [openable, kind, name]
  );
  const rendererOpen = useCallback(
    (row: EntityRecord) => {
      const url = openable && entityRowId(row) ? `onno://${kind}/${name}/${entityRowId(row)}` : null;
      if (url) openRoute(url);
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
      if (e.defaultPrevented || !e.clipboardData || !openable || picking) return;
      if (isEditingTarget() || textSelected()) return;
      // The selection, else the hovered row — and only a row of THIS list.
      let rows: EntityRecord[];
      if (selected.size) {
        rows = loadedRows.filter((r) => {
          const id = entityRowId(r);
          return id != null && selected.has(id);
        });
      } else {
        const id = hoveredRowId(kind, name);
        const row = id ? loadedRows.find((r) => entityRowId(r) === id) : undefined;
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
        JSON.stringify({ kind, name, ids: rows.map((r) => entityRowId(r)!) })
      );
      toast.success(t("clipboard.copied", { count: rows.length }));
    };
    const onPaste = (e: ClipboardEvent) => {
      if (e.defaultPrevented || !e.clipboardData || !openable || picking) return;
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
  }, [kind, name, openable, picking, selected, loadedRows, columns, list.newUrl, reload, t]);

  // ⌘A selects the entire matching feed. Shift-arrow ranges follow the loaded visual rows.
  // Only handle shortcuts while this list is engaged (selected rows or a hovered row).
  useEffect(() => {
    if (!openable || picking) return;
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
          const id = r ? entityRowId(r) : null;
          if (id) out.push(id);
        }
        return out;
      };
      if (isSelectAll) {
        e.preventDefault();
        void selectAllMatching();
        return;
      }
      e.preventDefault();
      // The pivot the range grows from: the click anchor, else the hovered row, else the
      // selection's nearest edge in the direction of travel.
      let pivot: number | null = selAnchorRef.current;
      if (pivot == null && hoverId != null) {
        const i = loadedRows.findIndex((r) => entityRowId(r) === hoverId);
        pivot = i >= 0 ? i : null;
      }
      if (pivot == null) {
        const idxs: number[] = [];
        loadedRows.forEach((r, i) => {
          const id = entityRowId(r);
          if (id && selected.has(id)) idxs.push(i);
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
  }, [openable, picking, kind, name, selected, loadedRows, selectAllMatching]);

  // The row right-click menu: built-ins (Open/Duplicate/Copy link/Delete) plus the entity's record
  // commands — ungrouped ones as top-level items, grouped ones as submenus. With a multi-row
  // selection (and the click landing on a selected row) it switches to batch mode: commands run
  // over every selected id, and Delete becomes a two-step "Delete N".
  const rowMenuEl = rowMenu
    ? (() => {
        const batch = (rowMenu.batch || selected.size > 1) && selected.has(rowMenu.id);
        const ids = batch ? [...selected] : [rowMenu.id];
        const openBatch = () => {
          for (const id of ids) openRoute(rowOpenUrl(kind, name, id));
        };
        const flatCustom = recordCommands.filter((c) => !c.group);
        const submenus = rowSubmenus;
        // Cell-menu mode (a column's `cellMenu`): just the named group's commands, flat — the status
        // pill's right-click IS the status list.
        const onlyMenuActions = rowMenu.only
          ? (submenus.find((g) => g.label === rowMenu.only)?.commands ?? [])
          : null;
        const close = () => {
          setRowMenu(null);
          setArmedDelete(false);
        };
        const actionItem = (c: ListCommand) => {
          // Per-record state only makes sense against the one clicked row; a batch shows the
          // static descriptor (each row's own availability is checked server-side).
          const st = batch
            ? undefined
            : resolvedCommandStates[rowMenu.id]?.[c.key]
              ?? rowCommandState(rowMenu.row)?.[c.key];
          if (!batch && st?.visible === false) return null;
          const disabled = (!batch && st?.enabled === false) || pending.has(`batch:${c.key}`);
          return (
            <ContextMenuItem
              key={c.key}
              disabled={disabled}
              onSelect={() => {
                if (batch) void runBatchCommand(c, ids);
                else runCommand(c, rowMenu.id);
                close();
              }}
            >
              <Icon name={commandIcon(c, batch ? null : rowMenu.row)} size={16} />
              <span>{(!batch && st?.title) || c.title}</span>
            </ContextMenuItem>
          );
        };
        // Built-ins: batch = label + open + delete; single = open/duplicate/copy-link/delete — minus
        // the write items (edit, dup, delete) when the viewer can't write the entity.
        const itemCount = onlyMenuActions
          ? onlyMenuActions.length
          : (batch ? (canWrite ? 3 : 2) : canWrite ? 5 : 2) + flatCustom.length + submenus.length;
        const showDuplicate = canWrite && !builtInHidden("duplicate");
        const showDelete = canWrite && !builtInHidden("delete");
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
            {!onlyMenuActions && <ExtensionSlot name="entity.list.context-menu" context={{execute:async(key,input)=>{const c=allCommands.find(c=>c.key===key&&c.target!=="collection");if(!c)throw new Error("Unknown row command");if(batch)await runBatchCommand(c,[...selected],input as CommandFormValues,true);else await runCommand(c,rowMenu.id,input as CommandFormValues,true);},surface:"entity-list",kind,name,recordId:rowMenu.id,record:rowMenu.row,selectedIds:[...selected],permissions:{canWrite},refresh:reload,closeMenu:close,openRecord:(kind,name,id)=>openRoute(`onno://${kind}/${name}/${id}`)}} />}
            {onlyMenuActions ? (
              // Cell-menu mode: the pill's right-click IS the choice list — nothing else.
              onlyMenuActions.map(actionItem)
            ) : (
              <>
            {batch ? (
              <>
                <ContextMenuLabel>{t("list.selected", { count: selected.size })}</ContextMenuLabel>
                <ContextMenuItem
                  onSelect={() => {
                    openBatch();
                    close();
                  }}
                >
                  <ExternalLink className="text-muted-foreground" aria-hidden="true" />
                  <span>{t("action.open")} {ids.length}</span>
                </ContextMenuItem>
              </>
            ) : (
              <>
                <ContextMenuItem
                  onSelect={() => {
                    openRoute(rowMenu.url);
                    close();
                  }}
                >
                  <ExternalLink className="text-muted-foreground" aria-hidden="true" />
                  <span>{t("action.open")}</span>
                  <ContextMenuShortcut>{shortcutLabel({ key: "Enter", mod: true })}</ContextMenuShortcut>
                </ContextMenuItem>
                {showDuplicate ? (
                  <>
                    <ContextMenuItem
                      onSelect={() => {
                        openRoute(rowMenu.url + "/duplicate");
                        close();
                      }}
                    >
                      <Copy className="text-muted-foreground" aria-hidden="true" />
                      <span>{t("action.duplicate")}</span>
                      <ContextMenuShortcut>{shortcutLabel({ key: "d", mod: true, shift: true })}</ContextMenuShortcut>
                    </ContextMenuItem>
                  </>
                ) : null}
              </>
            )}
            {flatCustom.length || submenus.length ? <ContextMenuSeparator /> : null}
            {flatCustom.map(actionItem)}
            {submenus.map((g) => (
              <ContextMenuSub key={g.label} label={<span>{g.label}</span>} width={200}>
                {g.commands.map(actionItem)}
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
            {showDelete ? (
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
                      openRoute(rowDeleteUrl(rowMenu.url));
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
              </>
            )}
          </ContextMenuContent>
        );
      })()
    : null;

  const relocateToolbar = toolbarPlacement === "renderer" && customMode && !!CustomRenderer;
  const dockFilters = !!toolbarOptions?.filterSidebar && relocateToolbar && !touchToolbar && workspaceWidth >= 1100;
  const listToolbar = (
      <div data-list-toolbar="" className={relocateToolbar ? "shrink-0 min-w-0 border-b border-border/50 bg-card" : "mb-3 shrink-0 overflow-hidden rounded-card border border-border/70 bg-card"}>
      <div className="flex min-w-0 items-center gap-2 px-2.5 py-2">
        {/* title + host control + row count. The host-provided control (e.g. the register's
            Balance/Movements toggle) sits between the fixed title and the count, so a changing
            count (or the "…" while it loads) never shifts the control the user is clicking. */}
        <div className="mr-1 flex min-h-8 min-w-0 items-center gap-2">
          {showControl("title") && toolbarOptions?.title !== false && <h1 className="max-w-40 truncate whitespace-nowrap text-base font-semibold text-foreground">{list.title}</h1>}
          {headerExtra}
          {selected.size > 0 && <ExtensionSlot name="entity.list.selection" className="flex items-center gap-2" context={{execute:async(key,input)=>{const c=allCommands.find(c=>c.key===key&&c.target!=="collection");if(!c)throw new Error("Unknown selection command");await runBatchCommand(c,[...selected],input as CommandFormValues,true);},surface:"entity-list",kind,name,selectedIds:[...selected],permissions:{canWrite},refresh:reload,openRecord:(kind,name,id)=>openRoute(`onno://${kind}/${name}/${id}`)}} />}
          {showControl("count") && toolbarOptions?.count !== false && <span className="whitespace-nowrap text-xs tabular-nums text-muted-foreground">
            {countValue == null ? "…" : t("list.count", { count: countValue })}
          </span>}
          {selectingAll ? <span role="status" className="text-xs text-muted-foreground">{t("list.selectingAll")}</span> : null}
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

        {/* Flexible facet rail — the only horizontally scrolling zone. Keeping it min-w-0 lets it
            yield to both fixed edge clusters instead of forcing search/New beyond the card. The
            scroller carries a little padding (with negative margins compensating) so a chip's
            focus ring isn't clipped by overflow-x-auto; the relative wrapper anchors the
            left/right fades that signal chips hidden past either edge. */}
        <div className="relative flex min-w-0 flex-1 items-center">
          <div
            data-filter-rail=""
            ref={railRef}
            onScroll={measureRail}
            className="-mx-1 -my-1 flex w-full items-center gap-2 overflow-x-auto overscroll-x-contain px-1 py-1 [scrollbar-width:none] [&::-webkit-scrollbar]:hidden"
          >
          {selected.size > 0 && !mapMode && !customMode && !scheduleMode ? (
            <div role="group" aria-label={t("list.selectionActions")} className="flex shrink-0 items-center gap-2 border-r border-border pr-2">
              {canWrite && SelectionWidget ? <SelectionWidget ids={[...selected]} complete={() => { clearSelection(); reload(); }} /> : null}
              <Button size="toolbar" variant="subtle" onClick={() => {
                for (const id of selected) openRoute(rowOpenUrl(kind, name, id));
              }}><ExternalLink />{t("action.open")}</Button>
              {(list.selectionDownloads ?? []).filter(d => !builtInHidden(d.key)).map(d => (
                <Button key={d.key} size="toolbar" variant="subtle" disabled={pending.has(`download:${d.key}`)}
                  onClick={() => void runSelectionDownload(d, [...selected])}>
                  {pending.has(`download:${d.key}`) ? <Loader2 className="animate-spin" /> : <Icon name={downloadIcon(d.key)} size={16} />}
                  {d.title}
                </Button>
              ))}
              {recordCommands.map(c => (
                <Button key={c.key} size="toolbar" variant="subtle" disabled={pending.has(`batch:${c.key}`)}
                  onClick={() => { setArmedDelete(false); void runBatchCommand(c, [...selected]); }}>
                  {pending.has(`batch:${c.key}`) ? <Loader2 className="animate-spin" />
                    : <Icon name={commandIcon(c)} size={16} />}
                  {c.title}
                </Button>
              ))}
              {canWrite && !builtInHidden("delete") ? <Button size="toolbar" variant="subtle" className="hover:border-destructive/40 hover:bg-destructive/10 hover:text-destructive"
                disabled={pending.has("batch:__delete")} onClick={() => {
                  if (!armedDelete) { setArmedDelete(true); return; }
                  setArmedDelete(false); void runBatchDelete([...selected]);
                }}><Trash2 />{armedDelete ? t("batch.deleteConfirm", { n: selected.size }) : t("action.delete")}</Button> : null}
            </div>
          ) : null}
          {/* group-by (+ granularity for a date column) — a facet chip like the filters beside it.
              Hidden on the map (it fetches its own rows) and on a custom body (grouping renders the
              grouped table, which the custom renderer replaces). */}
          {!mapMode && !customMode && !scheduleMode && showControl("grouping") && groupable.length ? (
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
          filters.filter(f => showControl("filters") && (!toolbarOptions?.filterKeys || toolbarOptions.filterKeys.includes(f.key))).sort((a,b)=>toolbarOptions?.filterKeys ? toolbarOptions.filterKeys.indexOf(a.key)-toolbarOptions.filterKeys.indexOf(b.key) : 0).map((f) => {
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
            if (f.type === "numberRange") {
              return (
                <NumberRangeFacet
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
                  inline={mobileControls}
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
          </div>
          {/* rail-edge fades — chips dissolve into the toolbar instead of cutting off clean,
              signalling there's more to scroll. Only while that edge actually clips. The 1px
              outset matches the scroller's negative margin so the fade hugs its true edge. */}
          <div
            aria-hidden="true"
            className={cn(
              "pointer-events-none absolute inset-y-0 -left-1 z-10 w-16 bg-gradient-to-r from-card from-25% via-card/80 to-transparent transition-opacity duration-200",
              railEdges.left ? "opacity-100" : "opacity-0"
            )}
          />
          <div
            aria-hidden="true"
            className={cn(
              "pointer-events-none absolute inset-y-0 -right-1 z-10 w-16 bg-gradient-to-l from-card from-25% via-card/80 to-transparent transition-opacity duration-200",
              railEdges.right ? "opacity-100" : "opacity-0"
            )}
          />
        </div>

        {/* right cluster — search, view toggle, collection commands, New */}
        <div className="ml-auto flex shrink-0 flex-nowrap items-center gap-2">
          {/* search — a facet-grammar chip that expands inline into the search input (SearchChip) */}
          {!mobileControls && !mapMode && list.searchable && showControl("search") && toolbarOptions?.search !== false ? (
            <SearchChip label={t("list.searchLabel")} value={query} onCommit={setQuery} openWidth={constrainedActions ? 160 : 208} />
          ) : null}
          {showControl("views") && toolbarOptions?.viewSwitcher !== false && allowedViews.length > 1 ? (
            // Table plus whichever alternate bodies the list declares.
            <Segmented
              value={activeView}
              onChange={setView}
              options={[
                { value: "table" as const, icon: Table2, label: compact ? undefined : t("list.tableView"), ariaLabel: t("list.tableView") },
                ...(list.map
                  ? [{ value: "map" as const, icon: MapIcon, label: compact ? undefined : t("list.mapView"), ariaLabel: t("list.mapView") }]
                  : []),
                ...(list.calendar
                  ? [{ value: "calendar" as const, icon: CalendarDays, label: compact ? undefined : t("list.calendarView"), ariaLabel: t("list.calendarView") }]
                  : []),
                ...(list.timeline
                  ? [{ value: "timeline" as const, icon: GanttChart, label: compact ? undefined : t("list.timelineView"), ariaLabel: t("list.timelineView") }]
                  : []),
                ...(list.custom && CustomRenderer
                  ? [{
                      value: "custom" as const,
                      icon: LayoutGrid,
                      label: compact ? undefined : list.custom.label || t("list.customView"),
                      ariaLabel: list.custom.label || t("list.customView"),
                    }]
                  : []),
              ].filter(option => allowedViews.includes(option.value as ListView))}
            />
          ) : null}
          {showControl("extensions") && <ExtensionSlot name="entity.list.actions" className="flex items-center gap-2" context={{execute:async(key,input)=>{const c=allCommands.find(c=>c.key===key&&c.target==="collection");if(!c)throw new Error("Unknown list command");await runCommand(c,undefined,input as CommandFormValues,true);},surface:"entity-list",kind,name,selectedIds:[...selected],permissions:{canWrite},refresh:reload,openRecord:(kind,name,id)=>openRoute(`onno://${kind}/${name}/${id}`)}} />}
          {collectionCommands.filter(c => showControl("actions") && (!toolbarOptions?.actionKeys || toolbarOptions.actionKeys.includes(c.key))).map((c) => {
            const busy = pending.has(c.key);
            const primary = c.placement === "primary";
            return (
              <button
                key={c.key}
                type="button"
                onClick={() => runCommand(c)}
                disabled={busy}
                className={cn(
                  "inline-flex h-8 shrink-0 items-center gap-1.5 whitespace-nowrap rounded-control text-xs font-medium transition-colors disabled:cursor-not-allowed disabled:opacity-60",
                  primary ? "bg-primary text-primary-foreground hover:bg-primary/90" : "bg-secondary text-foreground hover:bg-accent",
                  compact ? "w-8 justify-center px-0" : "px-3"
                )}
                title={c.description || c.title}
                aria-label={c.title}
              >
                {busy ? (
                  <Loader2 className="size-4 animate-spin" aria-hidden="true" />
                ) : (
                  <Icon name={commandIcon(c)} size={16} />
                )}
                {compact ? null : c.title}
              </button>
            );
          })}
          {list.newUrl && showControl("create") ? (
            <button
              type="button"
              onClick={() => openRoute(list.newUrl!)}
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
          {canExport && !picking && !mapMode && !scheduleMode ? (
            <Popover open={exportOpen} onOpenChange={setExportOpen}>
              <PopoverTrigger asChild>
                <Button type="button" size="toolbar" variant="subtle" disabled={exportBusy}
                  title={t("list.export")} aria-label={t("list.export")}>
                  {exportBusy ? <Loader2 className="animate-spin" /> : <Icon name="download" size={16} />}
                  {!compact && t("list.export")}
                </Button>
              </PopoverTrigger>
              <PopoverContent align="end" className="flex min-w-24 flex-col gap-1 p-1">
                <Button type="button" size="toolbar" variant="subtle" onClick={() => void exportList("xlsx")}>XLSX</Button>
                <Button type="button" size="toolbar" variant="subtle" onClick={() => void exportList("csv")}>CSV</Button>
              </PopoverContent>
            </Popover>
          ) : null}
        </div>
      </div>
      {toolbarContent && <div className="border-t border-border/50 px-2.5 py-2">{toolbarContent}</div>}
      </div>

  );

  const filterTrigger = <Button variant="subtle" aria-label={t("list.filters")} aria-expanded={filterSheetOpen} onClick={dockFilters || toolbarOptions?.filterSidebar ? () => setFilterSheetOpen(value => !value) : undefined} className="relative size-9 shrink-0 rounded-field border-input bg-muted p-0"><ListFilter className="size-4" />{activeFilterCount + (queryParams?.eq?.length ?? 0) > 0 && <span className="absolute right-1 top-1 size-2 rounded-full bg-primary" />}</Button>;
  const filterContents = <div data-compact-filter-sheet="" className="onno-compact-controls w-full min-w-0">{listToolbar}</div>;
  const toolbarPresentation = mobileControls ? <div data-compact-list-toolbar="" className="onno-compact-controls min-w-0 w-full">
    <div className="flex items-center gap-2">
      {dockFilters && <Button variant="subtle" aria-label={t(sidebarCollapsed ? "list.showSidebar" : "list.hideSidebar")} title={t(sidebarCollapsed ? "list.showSidebar" : "list.hideSidebar")} aria-expanded={!sidebarCollapsed} aria-controls={sidebarId} onClick={() => setSidebarCollapsed(value => !value)} className="relative size-9 shrink-0 rounded-field border-input bg-muted p-0">
        {sidebarCollapsed ? <PanelLeftOpen className="size-4" /> : <PanelLeftClose className="size-4" />}
        {sidebarCollapsed && activeFilterCount + (queryParams?.eq?.length ?? 0) > 0 && <span className="absolute right-1 top-1 size-2 rounded-full bg-primary" />}
      </Button>}
      {list.searchable && showControl("search") && toolbarOptions?.search !== false && <div className="relative min-w-0 flex-1">
        <Search className="pointer-events-none absolute left-3 top-1/2 size-4 -translate-y-1/2 text-muted-foreground" />
        <Input aria-label={t("list.searchLabel")} placeholder={t("list.searchLabel")} value={query} onChange={event=>setQuery(event.target.value)} className="h-9 w-full border border-input bg-muted pl-9 text-base placeholder:text-sm shadow-none" />
      </div>}
      {dockFilters ? null : toolbarOptions?.filterSidebar ? <>
        {filterTrigger}
        <FacetSheet label={t("list.filters")} open={filterSheetOpen} onClose={() => setFilterSheetOpen(false)} footer={<SheetDoneButton onClick={() => setFilterSheetOpen(false)}>{t("list.done")}</SheetDoneButton>}>
          <div className="p-4">{filterContents}{toolbarFooter && <div className="mt-4 border-t pt-3">{toolbarFooter}</div>}</div>
        </FacetSheet>
      </> : <ResponsiveActionPanel label={t("list.filters")} open={filterSheetOpen} onOpenChange={setFilterSheetOpen} trigger={filterTrigger}>
        <div className="w-80 max-w-full max-h-[65vh] overflow-y-auto"><div className="px-2.5 pb-2 text-sm font-semibold">{t("list.filters")}</div>{filterContents}{toolbarFooter && <div className="mt-3 border-t pt-2">{toolbarFooter}</div>}</div>
      </ResponsiveActionPanel>}
    </div>
  </div> : listToolbar;
  const presentedToolbar = <div ref={setToolbarElement} data-toolbar-container="" className="min-w-0 w-full">{toolbarPresentation}</div>;

  return (
    // pointer-events:auto: a host wrapper may set pointer-events:none, which would disable hover,
    // row clicks and the right-click menu.
    // As its own route surface the list takes the full height from its top to the window bottom
    // (equal padding all round) and scrolls only its list body inside — so the page never scrolls.
    // Embedded in a page, it flows with the host page (which owns scrolling) and carries no
    // outer spacing: PageDivBuilder owns the gap between sibling blocks and columns. Keeping that
    // spacing out of the list lets its toolbar align with adjacent cards in composed layouts.
    <div
      ref={rootRef}
      className={cn(
        "pointer-events-auto flex min-h-0 flex-col",
        surfaceMode && "overflow-hidden",
        surfaceMode && ownsSurfacePadding && "p-4 sm:p-6"
      )}
      style={surfaceMode && surfaceH != null ? { height: surfaceH } : undefined}
    >
      {!relocateToolbar && presentedToolbar}

      {mapMode ? (
        // Only catalog/document lists ever declare a map config, so mapMode is never true for a
        // register — narrow the kind for the map view, which pages from /api/list/{kind}/{name}.
        <div className={cn(surfaceMode && "min-h-0 flex-1")}>
          <ListMapView kind={kind as "catalogs" | "documents"} name={name} config={list.map!} fill={surfaceMode} />
        </div>
      ) : scheduleMode ? (
        // Calendar / timeline (ListSpec.calendar / .timeline): the same feed and query as the grid
        // — search and filters apply — narrowed to the visible period. Only catalog/document
        // lists offer them (see `schedulable`).
        <div className={cn(surfaceMode && "min-h-0 flex-1")}>
          {calendarMode ? (
            <ListCalendarView kind={kind as "catalogs" | "documents"} name={name} feedBase={feedBase} query={scheduleQuery}
              config={list.calendar!} canWrite={canWrite} fill={surfaceMode} />
          ) : (
            <ListTimelineView kind={kind as "catalogs" | "documents"} name={name} feedBase={feedBase} query={scheduleQuery}
              config={list.timeline!} canWrite={canWrite} fill={surfaceMode} />
          )}
        </div>
      ) : customMode && CustomRenderer ? (
        // Custom body (the app's `list.custom`): its component draws the rows; the list keeps
        // ownership of toolbar state and the keyset feed; placement may be delegated. The renderer owns
        // its own look.
        <div className={cn("relative flex min-w-0", surfaceMode && "min-h-0 flex-1")}>
          {dockFilters && <div id={sidebarId} data-sidebar-collapsed={sidebarCollapsed} className="onno-filter-sidebar-resize" inert={sidebarCollapsed} aria-hidden={sidebarCollapsed}>
          <nav aria-label={list.title} data-filter-sidebar="" className="flex h-full w-52 shrink-0 flex-col overflow-hidden rounded-panel border border-border/60 bg-card">
            <div className="flex shrink-0 items-center border-b border-border/50 p-3">
              <h2 className="text-sm font-semibold">{list.title}</h2>
            </div>
            <div className="min-h-0 flex-1 overflow-y-auto p-2">{filterContents}</div>
            {toolbarFooter && <div className="shrink-0 border-t border-border/50 p-2">{toolbarFooter}</div>}
          </nav></div>}
          <div
            ref={scrollRef}
            onScroll={onScroll}
            className={cn("min-w-0 flex-1 overflow-auto", surfaceMode && "min-h-0")}
            style={surfaceMode ? undefined : { maxHeight: maxBodyH }}
          >
            {pageRows == null && !relocateToolbar ? (
              <div className="flex items-center justify-center py-10 text-sm text-muted-foreground">
                <Loader2 className="mr-2 size-4 animate-spin" aria-hidden="true" />
                {t("loading.generic")}
              </div>
            ) : loadedRows.length === 0 && !relocateToolbar ? (
              <div className="px-4 py-10 text-center text-sm text-muted-foreground">
                {debounced ? t("empty.noMatches") : t("empty.noRecords")}
              </div>
            ) : (
              <CustomRenderer selectedIds={rendererSelectedIds}
                setSelection={openable && !picking ? setRendererSelection : undefined}
                selectAll={openable && !picking ? selectAllMatching : undefined} clearSelection={openable && !picking ? clearSelection : undefined}
                selectingAll={selectingAll} allSelected={allSelectionState} loading={pageRows == null} toolbar={relocateToolbar ? presentedToolbar : undefined} rows={loadedRows} total={total} list={rendererList} open={rendererOpen} openUrl={rendererOpenUrl}
                hasMore={hasMore} loadingMore={loadingMore} loadMoreFailed={loadMoreFailed} loadMore={loadMore} />
            )}
            {loadingMore && !ownsLoadingMore ? (
              <div role="status" className={cn("flex items-center justify-center gap-2 py-3 text-xs text-muted-foreground", surfaceMode && "pointer-events-none absolute bottom-0 inset-x-0")}>
                <Loader2 className="size-4 animate-spin" aria-hidden="true" />
                {t("list.loadingMore")}
              </div>
            ) : null}
          </div>
        </div>
      ) : grouped ? (
        // Grouped view: collapsible GROUP BY headers with subtotals; a group's rows lazily load
        // through the same feed. Shares the toolbar's search/filters/sort via groupParamsBase.
        <GroupedList
          rowStyle={list.rowStyle}
          feedBase={feedBase}
          kind={kind}
          name={name}
          columns={columns}
          template={template}
          minTableWidth={minTableWidth}
          leftPad={leftPad}
          aggregates={aggregates}
          groupsExpanded={!!list.groupsExpanded}
          groupBy={groupBy}
          granularity={groupCol?.date ? granularity : ""}
          paramsBase={groupParamsBase}
          pageSize={pageSize}
          openable={openable}
          canWrite={canWrite}
          surfaceMode={surfaceMode}
          fill={list.fill === true}
          scrollCap={maxBodyH}
          scrollRef={scrollRef}
          selected={selected}
          setSelected={setSelected}
          clearSelection={clearSelection}
          selectionCheckboxes={showSelectionCheckboxes}
          allSelectionState={allSelectionState}
          selectingAll={selectingAll}
          toggleAllSelection={toggleAllSelection}
          openRowMenu={openRowMenu}
          selectMode={selectMode}
        />
      ) : (
      /* table card — one scroller for both axes; the header sticks to the top (so it scrolls
         horizontally in lock-step with the rows) and the inner min-width drives horizontal
         scroll when the card is narrower than the columns need. On a route surface the card sizes
         to its rows and only shrinks (min-h-0, no grow) when they overflow the list's remaining
         height — a short list ends at its last row instead of dragging an empty card to the window
         bottom; embedded, it caps at maxBodyH.

         The card chrome (radius, border, background) sits on THIS element rather than on the
         scroller below, so the rounded overflow-hidden box clips the scroller's chrome. A classic,
         space-occupying scrollbar is painted in the scroller's own gutter and `border-radius` on
         that same element does not clip it, so carrying both on one element squares off the corners
         the bar runs past. Overlay scrollbars (macOS default) hide the difference. */
      <div className={cn(
        "flex flex-col overflow-hidden rounded-card border border-border bg-card",
        surfaceMode && "min-h-0"
      )}>
        {/* the body scroller — the only thing that scrolls. Header sticks to its top; the inner
            min-width drives horizontal scroll when the card is narrower than the columns need. */}
        <div
          ref={scrollRef}
          onScroll={onScroll}
          className={cn(
            "overflow-auto overscroll-contain",
            surfaceMode && "min-h-0 flex-1"
          )}
          style={surfaceMode ? undefined : { maxHeight: maxBodyH }}
        >
          <div className="w-full" style={{ minWidth: minTableWidth }}>
            {/* sticky header */}
            <div
              className={cn("sticky top-0 z-10 grid items-center gap-3 rounded-t-card border-b border-border bg-card py-2.5", leftPad)}
              style={{ gridTemplateColumns: template }}
            >
              {showSelectionCheckboxes ? <ListSelectionCheckbox
                label={t("list.selectAll")}
                disabled={selectingAll || pageRows == null || total === 0}
                checked={allSelectionState}
                onChange={toggleAllSelection}
              /> : null}
              {columns.map((c) => {
                const active = sort.column === c.columnName;
                if (c.sortable === false) {
                  return (
                    <div key={c.columnName} className="flex min-w-0 items-center gap-1">
                      <span className="truncate text-xs font-medium text-muted-foreground">{c.label}</span>
                      <HintIcon text={c.hint} size={13} />
                    </div>
                  );
                }
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
              {rowButtonCommands.length ? <span aria-hidden="true" /> : null}
            </div>

            {/* body — a virtual window, with spacers standing in for off-screen rows. */}
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
                  {showSelectionCheckboxes ? <span aria-hidden="true" /> : null}
                  {columns.map((c) => (
                    <span key={c.columnName} className="h-3.5 w-2/3 rounded bg-muted/60" />
                  ))}
                  {rowButtonCommands.length ? <span aria-hidden="true" /> : null}
                </div>
              ))
            ) : (
              // Pad above/below with the height of rows outside the virtual window.
              <div style={{ paddingTop: padTop, paddingBottom: padBottom }}>
                {visibleRows.map((row, i) => {
                  const absIdx = startIdx + i;
                  const recordId = openable ? entityRowId(row) : null;
                  const url = recordId ? `onno://${kind}/${name}/${recordId}` : undefined;
                  const rowViewers = recordId ? viewersById.get(recordId) : undefined;
                  const isSelected = recordId != null && selected.has(recordId);
                  if (selectMode) {
                    // Select mode (#411): click highlights, double click activates; no record
                    // opening, row menu or batch selection, and no data-onno-row for the shell.
                    const pick = selectMode.rowState?.(row);
                    const highlighted = recordId != null && recordId === selectMode.highlightedId;
                    const tinted = rowStyleClass(entityRowTone(row, list.rowStyle));
                    return (
                      <div
                        key={absIdx - headOffset}
                        role="option"
                        aria-selected={highlighted}
                        aria-disabled={pick?.disabled || undefined}
                        data-onno-pick-row={recordId ?? ""}
                        data-onno-selected={highlighted ? "true" : undefined}
                        data-onno-focused={recordId != null && recordId === focusedId && !highlighted ? "true" : undefined}
                        title={pick?.reason || undefined}
                        onClick={() => selectMode.onHighlight(row)}
                        onDoubleClick={() => {
                          if (!pick?.disabled) selectMode.onActivate(row);
                        }}
                        onContextMenu={(e) => e.preventDefault()}
                        className={cn(
                          "relative grid cursor-pointer select-none items-center gap-3 border-b border-border/50 text-sm",
                          leftPad,
                          !highlighted && tinted,
                          absIdx % 2 === 1 && !highlighted && !tinted && "bg-muted/20",
                          pick?.disabled && "cursor-not-allowed opacity-55"
                        )}
                        style={{ minHeight: ROW_H, gridTemplateColumns: template }}
                      >
                        {columns.map((c) => {
                          const text = selectMode.cellText?.(row, c);
                          return text !== undefined
                            ? <span key={c.columnName} className="min-w-0 truncate text-muted-foreground">{text}</span>
                            : <ListCell key={c.columnName} row={row} col={c} />;
                        })}
                        <PickRowBadge pick={pick} />
                      </div>
                    );
                  }
                  return (
                    // Key by absolute row index: register (balance) rows have no _id, so an id-based
                    // key collides to the same value for every row and React mis-reconciles into
                    // ghost/duplicate rows. The loaded list is append-only per query, so the absolute
                    // index is a stable, correct key here.
                    <div
                      key={absIdx - headOffset}
                      data-onno-row={url}
                      data-onno-selected={isSelected ? "true" : undefined}
                      // "Open in list" (#410): the record the list was opened on.
                      data-onno-focused={recordId != null && recordId === focusedId ? "true" : undefined}
                      // "0" tells the shell's fallback row menu / shortcuts to hide write actions;
                      // omitted when writable so rows from older servers keep the full menu.
                      data-onno-row-writable={canWrite ? undefined : "0"}
                      // Shift-click extends the selection — suppress the browser's text-range drag.
                      onMouseDown={(e) => {
                        if (e.shiftKey && recordId) e.preventDefault();
                      }}
                      onClick={(e) => {
                        // ⌘/Ctrl toggles a row in and out; Shift selects the range from the last
                        // toggled row. Plain click opens the record — or, mid-selection, just
                        // drops the selection (so a stray click can't navigate away from it).
                        if (recordId && (e.metaKey || e.ctrlKey || e.shiftKey)) {
                          e.preventDefault();
                          setSelected((prev) => {
                            const next = new Set(prev);
                            if (e.shiftKey && selAnchorRef.current != null) {
                              const lo = Math.min(selAnchorRef.current, absIdx);
                              const hi = Math.max(selAnchorRef.current, absIdx);
                              for (let j = lo; j <= hi; j++) {
                                const r = loadedRows[j];
                                const id = r ? entityRowId(r) : null;
                                if (id) next.add(id);
                              }
                            } else {
                              if (next.has(recordId)) next.delete(recordId);
                              else next.add(recordId);
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
                        setFocusedId(null);
                        if (url) openRoute(url);
                      }}
                      onContextMenu={(e) => {
                        if (!url || !recordId) return; // register rows: keep the browser menu
                        // Yield to an active text selection, like the global row menu does.
                        const sel = window.getSelection();
                        if (sel && !sel.isCollapsed && sel.toString().trim()) return;
                        // preventDefault keeps the browser's own context menu closed.
                        e.preventDefault();
                        if (selected.size && !selected.has(recordId)) clearSelection();
                        setArmedDelete(false);
                        openRowMenu({ x: e.clientX, y: e.clientY, id: recordId, url, row });
                      }}
                      className={cn(
                        // Hover highlight is owned by the [data-onno-row]:hover rule in index.css.
                        // Register rows aren't clickable (no detail route), so no pointer cursor.
                        "relative grid items-center gap-3 border-b border-border/50 text-sm",
                        url && "cursor-pointer",
                        leftPad,
                        // Conditional formatting (`style`, `_style` for register compatibility)
                        // replaces the
                        // zebra stripe on tinted rows; selection still wins over both.
                        !isSelected && rowStyleClass(entityRowTone(row, list.rowStyle)),
                        absIdx % 2 === 1 && !isSelected && !rowStyleClass(entityRowTone(row, list.rowStyle)) && "bg-muted/20",
                        isSelected && "bg-primary/10"
                      )}
                      style={{ minHeight: ROW_H, gridTemplateColumns: template }}
                    >
                      {showSelectionCheckboxes ? <ListSelectionCheckbox
                        label={t("list.selectRow", { row: String(row.description ?? row._description ?? row[columns[0]?.columnName] ?? absIdx + 1) })}
                        disabled={!recordId}
                        checked={isSelected}
                        onChange={checked => {
                          if (!recordId) return;
                          selAnchorRef.current = absIdx;
                          setSelected(prev => selectLoaded(prev, [recordId], checked));
                        }}
                      /> : null}
                      {columns.map((c) =>
                        c.cellMenu ? (
                          // A column's `cellMenu`: right-clicking THIS cell (e.g. the status pill)
                          // opens just its submenu's choices at the cursor — one click closer
                          // than row-menu → submenu. The rest of the row keeps the full menu.
                          <span
                            key={c.columnName}
                            className="flex min-w-0 items-center"
                            onContextMenu={(e) => {
                              e.preventDefault();
                              e.stopPropagation();
                              const rid = entityRowId(row)!;
                              openRowMenu({
                                x: e.clientX,
                                y: e.clientY,
                                id: rid,
                                url: url ?? "",
                                row,
                                only: c.cellMenu,
                              });
                            }}
                          >
                            <ListCell row={row} col={c} />
                          </span>
                        ) : (
                          <ListCell key={c.columnName} row={row} col={c} />
                        )
                      )}
                      {rowViewers ? (
                        <div className="pointer-events-none absolute left-0 top-1/2 z-10 flex w-11 -translate-y-1/2 items-center justify-end pr-1.5">
                          <PresenceAvatars viewers={rowViewers} size={16} max={3} overlap />
                        </div>
                      ) : null}
                      {rowButtonCommands.length ? (
                        <div className="flex items-center justify-end gap-1">
                          {rowButtonCommands.map((c) => {
                            // Per-record state: the server attaches each row's {visible, enabled,
                            // title} under `_actions`; the icon may be a function of the row.
                            const st =
                              resolvedCommandStates[entityRowId(row) ?? ""]?.[c.key]
                              ?? rowCommandState(row)?.[c.key];
                            if (st?.visible === false) return null;
                            const icon = commandIcon(c, row);
                            const label = st?.title ?? c.title;
                            const busy = pending.has(`${c.key}:${entityRowId(row)}`);
                            const disabled = busy || st?.enabled === false;
                            return (
                              <button
                                key={c.key}
                                type="button"
                                disabled={disabled}
                                onClick={(e) => {
                                  e.stopPropagation();
                                  runCommand(c, entityRowId(row)!);
                                }}
                                className="inline-flex size-7 items-center justify-center rounded-control text-muted-foreground hover:bg-accent hover:text-foreground disabled:cursor-not-allowed disabled:opacity-60"
                                title={label}
                                aria-label={label}
                              >
                                {busy ? (
                                  <Loader2 className="size-[15px] animate-spin" aria-hidden="true" />
                                ) : (
                                  <Icon name={icon} size={15} />
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
            {/* A slim "loading more" row while the next keyset window is in flight. */}
            {loadingMore ? (
              <div className="flex items-center justify-center gap-2 border-t border-border/50 py-3 text-xs text-muted-foreground">
                <Loader2 className="size-4 animate-spin" aria-hidden="true" />
                {t("list.loadingMore")}
              </div>
            ) : null}
          </div>
        </div>
      </div>
      )}
      {rowMenuEl}
      {formPrompt ? (
        <CommandFormDialog
          title={formPrompt.command.title}
          description={formPrompt.command.description}
          fields={formPrompt.command.form ?? []}
          defaultsSource={
            // Server-computed opening values (defaults): single-record opens only — a batch has
            // no one record to compute against, so it keeps the static defaults.
            formPrompt.command.dynamicForm && !formPrompt.ids
              ? { kind, name, key: formPrompt.command.key, id: formPrompt.id }
              : undefined
          }
          onClose={() => setFormPrompt(null)}
          onSubmit={(values) =>
            formPrompt.ids
              ? runBatchCommand(formPrompt.command, formPrompt.ids, values, true)
              : Promise.resolve(runCommand(formPrompt.command, formPrompt.id, values, true))
          }
        />
      ) : null}
    </div>
  );
}
