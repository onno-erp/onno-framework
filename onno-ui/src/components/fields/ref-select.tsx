import { openRoute } from "../../lib/navigation";
import { useEffect, useRef, useState, type ReactNode } from "react";
import { ChevronsUpDown, ExternalLink, List, Plus, Search, X } from "lucide-react";
import { api } from "../../lib/api";
import { cn, toSnakeCase } from "../../lib/utils";
import { logicalEntityKey } from "../../lib/entity-keys";
import type { EntityRecord, RefOptionSearch } from "../../lib/types";
import type { WhereClause } from "../../lib/measure";
import { Popover, PopoverContent, PopoverTrigger } from "../ui/popover";
import { selectTriggerClassName } from "../ui/select";
import { FacetSheet, useTouchLayout } from "../ui/facet-sheet";
import { Avatar, AvatarImage, AvatarFallback } from "../ui/avatar";
import { RefPickerDialog } from "./ref-picker-dialog";
import { useMessages } from "../../providers/messages-provider";
import { useQuickCreate } from "../../lib/quick-create";

export interface RefSelectProps {
  /** The ref target's registered logical name (catalog or document). */
  targetName: string;
  /** Whether the target is a catalog or a document; drives which endpoints we hit. */
  refKind?: "catalog" | "document";
  /** Optional target column shown as a secondary line under each option's name (issue #184). */
  secondaryField?: string;
  /**
   * Which target records the picker offers (a cascading field's `{ supplier: <id> }`), sent with each
   * search and kept by the "Show all…" dialog. Undefined = unfiltered.
   */
  where?: WhereClause;
  value?: string | null;
  /**
   * Whether an existing value may be reset to null: an inline clear button in the field plus the
   * "Clear selection" row in the options. Required fields leave this false; entity forms pass the
   * resolved `refClearButton` metadata (#409).
   */
  clearable?: boolean;
  /**
   * Show an inline button that opens the selected record (`onno://{kind}/{name}/{id}`). Entity
   * forms pass the resolved `refOpenButton` metadata (#409); other hosts (action dialogs, related
   * lists) leave it off so a click never navigates away from them.
   */
  openButton?: boolean;
  /**
   * The field's owner (ownerKind/ownerName, selecting the field's server-side PickerOptions) and the
   * live form/row context those options read. Without an owner the options stay undecorated.
   */
  optionContext?: Omit<RefOptionSearch, "targetKind" | "targetName" | "query" | "limit" | "where">;
  /** Sibling-row selections to disable immediately in this picker. */
  excludedIds?: string[];
  /** The field's label, titling the "Show all…" picker dialog (#411); defaults to the target's title. */
  fieldLabel?: string;
  /**
   * Offer "Show all…" below the options (#411), opening the target's full list in a picker
   * dialog. On by default; a polymorphic ref turns it off (one dialog cannot span its targets).
   */
  showAll?: boolean;
  /**
   * Offer "+ New" at the top of the options, opening the target's new-record form. On by default;
   * a widget whose user cannot create the target (or that edits outside a form) turns it off.
   */
  allowCreate?: boolean;
  /** Disables the field: the trigger does not open and the open/clear buttons are hidden. */
  disabled?: boolean;
  /** Empty-field text; defaults to "Select {targetName}". */
  placeholder?: string;
  /**
   * The current value's label when the host already knows it: shown at once instead of waiting for
   * the record fetch, and kept if the user may not read the target record itself.
   */
  valueLabel?: string | null;
  /** The trigger's id, so a `<Label htmlFor>` names the field. */
  id?: string;
  "aria-label"?: string;
  "aria-labelledby"?: string;
  /** Extra trigger classes, merged over the select-trigger look (e.g. a compact chip). */
  className?: string;
  onChange: (id: string | null) => void;
}

function initials(name: string | undefined): string {
  if (!name) return "?";
  const parts = name.trim().split(/[\s._-]+/).filter(Boolean);
  if (parts.length >= 2) return (parts[0][0] + parts[1][0]).toUpperCase();
  return (name.trim().slice(0, 2) || "?").toUpperCase();
}

function displayOf(item: EntityRecord): string {
  const desc = item.description as string | undefined;
  if (desc && desc.trim()) return desc;
  // Catalogs label by code; documents have no code/description, so fall back to number.
  return (
    (item.code as string) ??
    (item.number as string) ??
    (item.id as string) ?? ""
  );
}

function optionId(item: EntityRecord): string {
  return String(item.id ?? "");
}

/**
 * A searchable ref picker backed by the server-side catalog typeahead, so it never
 * loads the whole catalog (a 2000-row Clients list stays on the server). Opens a popover
 * with a search box + capped results; the selected record's label is fetched by id so it
 * shows even when it isn't in the current result page. "+ New" and, for nullable fields with a
 * value, "Clear selection" are pinned at the top so they stay reachable regardless of the results.
 */
export function RefSelect({
  targetName,
  refKind = "catalog",
  secondaryField,
  where,
  value,
  clearable = false,
  openButton = false,
  optionContext,
  excludedIds = [],
  fieldLabel,
  showAll: showAllEnabled = true,
  allowCreate = true,
  disabled = false,
  placeholder,
  valueLabel,
  id,
  "aria-label": ariaLabel,
  "aria-labelledby": ariaLabelledBy,
  className,
  onChange,
}: RefSelectProps) {
  const t = useMessages();
  const name = toSnakeCase(targetName);
  const isDocument = refKind === "document";
  const [open, setOpen] = useState(false);
  const [query, setQuery] = useState("");
  const [items, setItems] = useState<EntityRecord[]>([]);
  const [selected, setSelected] = useState<EntityRecord | null>(null);
  const [loading, setLoading] = useState(false);
  // "Show all…" (#411): the full-list picker dialog, opened from the bottom of the options.
  const [pickerOpen, setPickerOpen] = useState(false);
  const [pickerQuery, setPickerQuery] = useState("");
  const touchLayout = useTouchLayout();
  const kind = isDocument ? "documents" : "catalogs";
  const contextKey = JSON.stringify(optionContext ?? {});
  const whereKey = where ? JSON.stringify(where) : "";

  // "+ New" quick-create hand-off. The result arrives as the shell's delivered slot (not a
  // callback — this component can be remounted, or unmounted outright in single-pane layouts,
  // while the create form is open). We claim it either live (the slot changes) or on mount after
  // a remount; the claim is gated on our request token or, post-remount, on being an empty
  // same-target picker (see quick-create.ts).
  const quickCreate = useQuickCreate();
  const requestTokenRef = useRef<number | null>(null);
  const latest = useRef({ value, onChange });
  latest.current = { value, onChange };
  useEffect(() => {
    const cur = latest.current;
    const id = quickCreate.claim(kind, name, requestTokenRef.current, !cur.value);
    // Claims are idempotent (the slot lives until its TTL so a remounted picker can re-claim
    // a value the remount wiped) — only fire onChange when it actually changes the field.
    if (id && id !== cur.value) {
      cur.onChange(id);
      rememberPick(isDocument, targetName, id);
    }
  }, [kind, name, quickCreate.delivered]); // eslint-disable-line react-hooks/exhaustive-deps

  // Resolve the current value's label (it may not be in the result page).
  useEffect(() => {
    if (!value) {
      setSelected(null);
      return;
    }
    if (selected && optionId(selected) === value) return;
    let cancelled = false;
    const fetchOne = isDocument ? api.getDocument(name, value) : api.getCatalogItem(name, value);
    fetchOne.then((r) => !cancelled && setSelected(r)).catch(() => {});
    return () => {
      cancelled = true;
    };
  }, [value, name, isDocument]); // eslint-disable-line react-hooks/exhaustive-deps

  // Debounced server-side search while the popover is open.
  useEffect(() => {
    if (!open) return;
    setLoading(true);
    const t = setTimeout(() => {
      let cancelled = false;
      const run = api.searchRefOptions({
        targetKind: isDocument ? "document" : "catalog",
        targetName,
        ownerKind: optionContext?.ownerKind,
        ownerName: optionContext?.ownerName,
        query,
        limit: 30,
        where,
        fieldPath: optionContext?.fieldPath ?? "",
        formValues: optionContext?.formValues ?? {},
        section: optionContext?.section,
        rowIndex: optionContext?.rowIndex,
        rowValues: optionContext?.rowValues,
        documentId: optionContext?.documentId,
      });
      run
        .then((r) => !cancelled && setItems(r))
        .catch(() => {})
        .finally(() => !cancelled && setLoading(false));
      return () => {
        cancelled = true;
      };
    }, 180);
    return () => clearTimeout(t);
  }, [open, query, name, targetName, isDocument, whereKey, contextKey]); // eslint-disable-line react-hooks/exhaustive-deps

  const pick = (item: EntityRecord) => {
    // A manual pick supersedes any outstanding "+ New" hand-off.
    quickCreate.cancel(kind, name);
    setSelected(item);
    onChange(optionId(item));
    setOpen(false);
    rememberPick(isDocument, targetName, optionId(item));
  };

  const addNew = () => {
    setOpen(false);
    // Open the target's full new-form (a side pane in the islands layout) so every required
    // field is available. Register the hand-off first: when that form saves, the new record's
    // id lands straight in this field instead of the user re-finding it in the picker.
    requestTokenRef.current = quickCreate.request(kind, name);
    openRoute(`onno://${kind}/${name}/new`);
  };

  const showAll = () => {
    setPickerQuery(query);
    setOpen(false);
    setPickerOpen(true);
  };

  // A dialog pick takes the dropdown's path: the same value, the same "recent" bookkeeping.
  const pickFromDialog = (id: string, row: EntityRecord) => {
    quickCreate.cancel(kind, name);
    setSelected(row);
    onChange(id);
    rememberPick(isDocument, targetName, id);
  };

  const clear = () => {
    quickCreate.cancel(kind, name);
    setSelected(null);
    onChange(null);
    setOpen(false);
  };

  // Same hand-off the "+ New" row uses: the host routes onno:// links (a side pane in the islands
  // layout, the record route elsewhere).
  const openSelected = () => {
    if (!value) return;
    openRoute(`onno://${kind}/${name}/${value}`);
  };

  const showOpen = openButton && !!value && !disabled;
  const showClear = clearable && !!value && !disabled;
  const inlineButtons = (showOpen ? 1 : 0) + (showClear ? 1 : 0);
  const setOpenUnlessDisabled = (next: boolean) => setOpen(next && !disabled);

  const trigger = (
    <button
      type="button"
      id={id}
      aria-label={ariaLabel}
      aria-labelledby={ariaLabelledBy}
      disabled={disabled}
      onClick={() => setOpenUnlessDisabled(true)}
      // text-left: a button centers text by default, and the selected row stretches full width.
      className={cn(selectTriggerClassName, "min-w-0 gap-2 overflow-hidden text-left transition-colors hover:bg-accent", className)}
    >
      {selected ? (
        <RefRow item={selected} />
      ) : value && valueLabel ? (
        <span className="min-w-0 flex-1 truncate">{valueLabel}</span>
      ) : (
        <span className="min-w-0 truncate text-muted-foreground">{placeholder ?? t("form.select", { name: targetName })}</span>
      )}
      {/* Keeps the label clear of the inline buttons overlaid just left of the chevron. */}
      {inlineButtons > 0 ? (
        <span data-onno-inline-gap="" aria-hidden="true" className={cn("shrink-0", inlineButtons === 1 ? "w-6" : "w-[50px]")} />
      ) : null}
      <ChevronsUpDown className="size-4 shrink-0 text-muted-foreground" aria-hidden="true" />
    </button>
  );

  // Open / clear sit beside the trigger rather than inside it (a button may not contain buttons),
  // overlaid just left of the chevron so the field keeps one control's footprint. The trigger
  // spans the whole field, so the popover still anchors to (and sizes from) the full width.
  const overlay = inlineButtons === 0 ? null : (
    <span className="absolute inset-y-0 right-8 flex items-center gap-0.5">
      {showOpen ? (
        <InlineButton label={t("ref.open")} onClick={openSelected}>
          <ExternalLink className="size-3.5" aria-hidden="true" />
        </InlineButton>
      ) : null}
      {showClear ? (
        <InlineButton label={t("ref.clearValue")} onClick={clear}>
          <X className="size-3.5" aria-hidden="true" />
        </InlineButton>
      ) : null}
    </span>
  );
  const withOverlay = (control: ReactNode) =>
    overlay ? (
      <div className="relative min-w-0">
        {control}
        {overlay}
      </div>
    ) : (
      control
    );

  const content = (
    <RefSelectOptions
      targetName={targetName}
      secondaryField={secondaryField}
      value={value}
      query={query}
      onQueryChange={setQuery}
      items={items}
      loading={loading}
      onPick={pick}
      onAddNew={allowCreate ? addNew : undefined}
      onClear={clearable && value ? clear : undefined}
      onShowAll={showAllEnabled ? showAll : undefined}
      excludedIds={excludedIds}
    />
  );

  const picker = (
    <RefPickerDialog
      open={pickerOpen}
      onClose={() => setPickerOpen(false)}
      fieldLabel={fieldLabel ?? ""}
      targetKind={isDocument ? "document" : "catalog"}
      targetName={targetName}
      where={where}
      optionContext={optionContext}
      excludedIds={excludedIds}
      value={value ?? null}
      initialQuery={pickerQuery}
      onPick={pickFromDialog}
    />
  );

  if (touchLayout) {
    return (
      <>
        {withOverlay(trigger)}
        <FacetSheet open={open} label={t("form.select", { name: targetName })} onClose={() => setOpen(false)}>
          {content}
        </FacetSheet>
        {picker}
      </>
    );
  }

  return (
    <Popover open={open} onOpenChange={setOpenUnlessDisabled}>
      {withOverlay(<PopoverTrigger asChild>{trigger}</PopoverTrigger>)}
      <PopoverContent
        align="start"
        // z-[70] clears the action-form modal (z-[60]) so the picker floats above it, not behind;
        // harmless in the entity form, which isn't a portal-stacked overlay. The minimum width keeps
        // the search box and names readable under a compact trigger (a widget's chip).
        className="z-[70] w-[var(--radix-popover-trigger-width)] min-w-64 max-w-[calc(100vw-2rem)] overflow-hidden p-0"
      >
        {content}
      </PopoverContent>
      {picker}
    </Popover>
  );
}

function RefSelectOptions({
  targetName,
  secondaryField,
  value,
  query,
  onQueryChange,
  items,
  loading,
  onPick,
  onAddNew,
  onClear,
  onShowAll,
  excludedIds,
}: {
  targetName: string;
  secondaryField?: string;
  value?: string | null;
  query: string;
  onQueryChange: (value: string) => void;
  items: EntityRecord[];
  loading: boolean;
  onPick: (item: EntityRecord) => void;
  /** Absent when the host turned creation off ({@code allowCreate={false}}). */
  onAddNew?: () => void;
  onClear?: () => void;
  onShowAll?: () => void;
  excludedIds: string[];
}) {
  const t = useMessages();
  return (
    <>
      {/* "+ New" pinned to the top so it's always reachable. */}
      {onAddNew ? (
        <button
          type="button"
          onClick={onAddNew}
          className="flex w-full items-center gap-2 border-b px-3 py-2 text-sm font-medium text-foreground transition-colors hover:bg-accent"
        >
          <Plus className="size-4 text-muted-foreground" aria-hidden="true" />
          {t("ref.new", { name: targetName })}
        </button>
      ) : null}
      {onClear ? (
        <button
          type="button"
          onClick={onClear}
          className="flex w-full items-center gap-2 border-b px-3 py-2 text-sm text-muted-foreground transition-colors hover:bg-accent hover:text-foreground"
        >
          <X className="size-4" aria-hidden="true" />
          {t("form.clearSelection")}
        </button>
      ) : null}
      <SearchBox value={query} onChange={onQueryChange} />
      <div className="max-h-64 overflow-y-auto py-1 sm:max-h-64">
        {items.length === 0 ? (
          <div className="px-3 py-6 text-center text-sm text-muted-foreground">
            {loading ? t("loading.searching") : t("empty.noMatches")}
          </div>
        ) : (
          items.map((item, index) => {
            const id = optionId(item);
            // The server leads an empty-query search with the user's recent picks (#409): head
            // them with a "Recent" label and rule them off from the ordinary options.
            const recent = item._recent === true;
            const firstRecent = recent && index === 0;
            const firstAfterRecent = !recent && index > 0 && items[index - 1]._recent === true;
            const excluded = excludedIds.includes(id);
            const disabled = excluded || item._optionDisabled === true;
            const reason = excluded
              ? t("ref.selectedElsewhere")
              : String(item._optionReason ?? "");
            const badge = excluded
              ? t("ref.alreadySelected")
              : String(item._optionBadge ?? "");
            return (
              <div key={id} className={cn(firstAfterRecent && "mt-1 border-t pt-1")}>
              {firstRecent ? (
                <div className="px-3 pb-1 pt-1.5 text-xs font-medium text-muted-foreground">
                  {t("ref.recent")}
                </div>
              ) : null}
              <button
                type="button"
                data-onno-recent={recent ? "" : undefined}
                disabled={disabled}
                title={disabled && reason ? reason : undefined}
                onClick={() => onPick(item)}
                className={cn(
                  "flex w-full items-center px-3 py-1.5 text-left text-sm transition-colors hover:bg-accent",
                  id === value && "bg-accent/60",
                  disabled && "cursor-not-allowed opacity-55 hover:bg-transparent"
                )}
              >
                <RefRow
                  item={item}
                  secondary={secondaryField}
                  badge={badge}
                  tone={excluded ? "neutral" : String(item._optionTone ?? "neutral")}
                  badgeColor={excluded ? "" : String(item._optionColor ?? "")}
                  reason={reason}
                />
              </button>
              </div>
            );
          })
        )}
      </div>
      {onShowAll ? (
        // "Show all…" (#411): the full list with columns, filters and folders, in a dialog.
        <button
          type="button"
          onClick={onShowAll}
          data-onno-ref-show-all=""
          className="flex w-full items-center gap-2 border-t px-3 py-2 text-sm text-muted-foreground transition-colors hover:bg-accent hover:text-foreground"
        >
          <List className="size-4" aria-hidden="true" />
          {t("ref.showAll")}
        </button>
      ) : null}
    </>
  );
}

/** A small icon button overlaid on the picker field (open the record, clear the value). */
function InlineButton({ label, onClick, children }: { label: string; onClick: () => void; children: ReactNode }) {
  return (
    <button
      type="button"
      aria-label={label}
      title={label}
      onClick={(event) => {
        event.stopPropagation();
        onClick();
      }}
      className="flex size-6 items-center justify-center rounded-control text-muted-foreground transition-colors hover:bg-accent hover:text-foreground focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring"
    >
      {children}
    </button>
  );
}

/**
 * Tell the server this record was just picked so it leads the next empty-query search (#409).
 * Fire-and-forget: a failed remember must never disturb the pick itself.
 */
function rememberPick(isDocument: boolean, targetName: string, id: string) {
  if (!id) return;
  try {
    api.rememberRefPick({ targetKind: isDocument ? "document" : "catalog", targetName, id }).catch(() => {});
  } catch {
    // A synchronous failure (e.g. an API client stub without this call) is ignored the same way.
  }
}

function SearchBox({ value, onChange }: { value: string; onChange: (v: string) => void }) {
  const t = useMessages();
  const ref = useRef<HTMLInputElement>(null);
  useEffect(() => {
    // Focus the search input when the popover mounts.
    const id = requestAnimationFrame(() => ref.current?.focus());
    return () => cancelAnimationFrame(id);
  }, []);
  return (
    <div className="flex items-center gap-2 border-b px-3">
      <Search className="size-4 shrink-0 text-muted-foreground" aria-hidden="true" />
      <input
        ref={ref}
        value={value}
        onChange={(e) => onChange(e.target.value)}
        placeholder={t("list.search")}
        className="h-9 w-full bg-transparent text-sm outline-none placeholder:text-muted-foreground"
      />
    </div>
  );
}

function RefRow({
  item,
  secondary,
  badge,
  tone = "neutral",
  badgeColor,
  reason,
}: {
  item: EntityRecord;
  secondary?: string;
  badge?: string;
  tone?: string;
  badgeColor?: string;
  reason?: string;
}) {
  const display = displayOf(item);
  const avatarUrl =
    (item.avatarUrl as string | undefined) ??
    undefined;
  // A target catalog's `color` attribute (same convention the list uses for FieldColor pills):
  // render a small dot so colored options — statuses, tags — read the same here as on the board.
  const color = ((item.color as string | undefined) ?? "").trim();
  const code = item.code as string | undefined;
  // The disambiguating secondary line (e.g. a phone), shown under the name in the options list.
  const sub = secondary ? item[logicalEntityKey(secondary)] : undefined;
  const subText = sub == null ? "" : String(sub);
  const toneClass: Record<string, string> = {
    neutral: "bg-muted text-muted-foreground",
    success: "bg-emerald-500/15 text-emerald-700 dark:text-emerald-400",
    warning: "bg-amber-500/15 text-amber-700 dark:text-amber-400",
    danger: "bg-red-500/15 text-red-700 dark:text-red-400",
    accent: "bg-primary/15 text-primary",
  };
  return (
    <span className="inline-flex min-w-0 flex-1 items-center gap-2" title={code ? `${display} · ${code}` : display}>
      {avatarUrl ? (
        <Avatar className="h-5 w-5 text-[9px]">
          <AvatarImage src={avatarUrl} alt={display} />
          <AvatarFallback>{initials(display)}</AvatarFallback>
        </Avatar>
      ) : color ? (
        <span
          className="size-2.5 shrink-0 rounded-full"
          style={{ backgroundColor: color }}
          aria-hidden="true"
        />
      ) : null}
      <span className="flex min-w-0 flex-1 flex-col">
        <span className="truncate">{display}</span>
        {subText ? <span className="truncate text-xs text-muted-foreground">{subText}</span> : null}
        {reason ? <span className="truncate text-xs text-muted-foreground">{reason}</span> : null}
      </span>
      {badge ? (
        <span
          className={cn(
            "ml-auto shrink-0 rounded-control px-2 py-0.5 text-[11px] font-medium",
            !badgeColor && (toneClass[tone] ?? toneClass.neutral)
          )}
          style={badgeColor ? { backgroundColor: badgeColor, color: "#fff" } : undefined}
        >
          {badge}
        </span>
      ) : null}
    </span>
  );
}
