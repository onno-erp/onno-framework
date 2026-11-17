import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import { Dialog, Heading, Modal, ModalOverlay } from "react-aria-components";
import { Filter, Loader2, Search, X } from "lucide-react";
import { api } from "../../lib/api";
import { cn } from "../../lib/utils";
import type { EntityRecord, RefOptionDecorations, RefOptionSearch } from "../../lib/types";
import type { WhereClause } from "../../lib/measure";
import { Button } from "../ui/button";
import { DialogPortalContext, useOutsidePressGuard } from "../ui/dialog-portal";
import { useTouchLayout } from "../ui/facet-sheet";
import {
  EntityListView,
  entityRowId,
  type ListColumn,
  type ListDescriptor,
  type ListSelectMode,
  type PickRowState,
} from "../lists/entity-list";
import {
  FolderBreadcrumbs,
  FolderTree,
  isFolder,
  loadFolderPaths,
  rowLabel,
  type Crumb,
} from "../lists/folder-browser";
import { useMessages } from "../../providers/messages-provider";

/** The picker descriptor: an ordinary list descriptor plus whether the target has folders. */
type PickerDescriptor = ListDescriptor & { hierarchical?: boolean };

export interface RefPickerDialogProps {
  open: boolean;
  onClose: () => void;
  /** The field's label, for the dialog title. */
  fieldLabel: string;
  targetKind: "catalog" | "document";
  targetName: string;
  /** Which target records the field offers: a fixed constraint the dialog never lets the user lift. */
  where?: WhereClause;
  /**
   * The field's owner and live form/row context, exactly as the dropdown sends them. With an owner,
   * each window is resolved through /api/ref-options/decorate (the field's PickerOptions).
   */
  optionContext?: Omit<RefOptionSearch, "targetKind" | "targetName" | "query" | "limit" | "where">;
  /** Ids selected in sibling rows of a uniqueWithinSection column: shown, but not selectable. */
  excludedIds?: string[];
  /** The field's current value: the dialog opens on it (in its folder) with it highlighted. */
  value?: string | null;
  /** Text already typed in the dropdown, carried into the dialog's search. */
  initialQuery?: string;
  onPick: (id: string, row: EntityRecord) => void;
}

/** The client-computed "Location" column a search across folders adds (#411). */
const LOCATION_COLUMN = "__location";

/** Both predicates, or whichever is present. */
function andWhere(a: WhereClause | undefined, b: WhereClause | undefined): WhereClause | undefined {
  return a && b ? { and: [a, b] } : a ?? b;
}

/**
 * The "Show all…" picker (#411): the target's full list in select mode, opened from a reference
 * field's dropdown. It offers exactly what the dropdown would: the field's `where` is a fixed
 * feed constraint, the server's record scope and deletion mark apply, the field's PickerOptions hide
 * and disable rows per window, and values picked in sibling rows stay unselectable. A
 * hierarchical catalog adds a folder tree and breadcrumbs; a search looks across every folder.
 */
export function RefPickerDialog(props: RefPickerDialogProps) {
  const { open, onClose } = props;
  const touch = useTouchLayout();
  const [portalContainer, setPortalContainer] = useState<HTMLDivElement | null>(null);
  const shouldCloseOnInteractOutside = useOutsidePressGuard(open, portalContainer);
  if (!open) return null;
  return (
    <ModalOverlay
      isOpen
      isDismissable
      shouldCloseOnInteractOutside={shouldCloseOnInteractOutside}
      onOpenChange={(next) => { if (!next) onClose(); }}
      className={cn(
        "fixed inset-0 z-[70] flex items-center justify-center bg-black/50 backdrop-blur-[1px]",
        touch ? "p-0" : "p-4"
      )}
    >
      <Modal
        className={cn(
          "flex w-full flex-col text-card-foreground outline-none",
          touch ? "h-dvh max-w-none" : "h-[min(46rem,88dvh)] max-w-5xl"
        )}
      >
        <DialogPortalContext.Provider value={portalContainer}>
          <PickerBody {...props} touch={touch} />
          <div ref={setPortalContainer} className="contents" />
        </DialogPortalContext.Provider>
      </Modal>
    </ModalOverlay>
  );
}

function PickerBody({
  onClose,
  fieldLabel,
  targetKind,
  targetName,
  where,
  optionContext,
  excludedIds = [],
  value,
  initialQuery = "",
  onPick,
  touch,
}: RefPickerDialogProps & { touch: boolean }) {
  const t = useMessages();
  const kind = targetKind === "document" ? "documents" : "catalogs";
  const whereKey = where ? JSON.stringify(where) : "";
  const [descriptor, setDescriptor] = useState<PickerDescriptor | null>(null);
  const [failed, setFailed] = useState(false);
  const [query, setQuery] = useState(initialQuery);
  const [debouncedQuery, setDebouncedQuery] = useState(initialQuery);
  const [highlighted, setHighlighted] = useState<EntityRecord | null>(null);
  const [highlightedId, setHighlightedId] = useState<string | null>(value ?? null);
  const [path, setPath] = useState<Crumb[] | null>(null);
  const [focusId, setFocusId] = useState<string | null>(value ?? null);
  const [listKey, setListKey] = useState(0);
  const hierarchical = descriptor?.hierarchical === true && kind === "catalogs";

  useEffect(() => {
    const id = window.setTimeout(() => setDebouncedQuery(query), 250);
    return () => window.clearTimeout(id);
  }, [query]);

  // The target's list descriptor, then (for a folder tree) the path to the current value.
  useEffect(() => {
    let cancelled = false;
    api.getPickerList<PickerDescriptor>(kind, targetName)
      .then(async (d) => {
        if (cancelled) return;
        const root: Crumb = { id: null, label: d.title };
        if (d.hierarchical && kind === "catalogs") {
          // One request for the value's whole path; the dialog opens in the value's folder.
          const paths = value ? await loadFolderPaths(targetName, [value]).catch(() => new Map<string, Crumb[]>()) : null;
          const own = value ? paths?.get(value) : undefined;
          setPath([root, ...(own ? own.slice(0, -1) : [])]);
        } else {
          setPath([root]);
        }
        if (!cancelled) setDescriptor(d);
      })
      .catch(() => { if (!cancelled) setFailed(true); });
    return () => { cancelled = true; };
  }, [kind, targetName]); // eslint-disable-line react-hooks/exhaustive-deps

  const folderId = path && path.length ? path[path.length - 1].id : null;
  const searching = debouncedQuery.trim() !== "";
  const searchingRef = useRef(false);
  searchingRef.current = hierarchical && searching;

  // ---- locations: a search spans every folder, so each hit shows where it lives ----
  const [locations, setLocations] = useState<Record<string, string>>({});
  const locationsRequested = useRef(new Set<string>());
  const locate = useCallback((rows: EntityRecord[]) => {
    const ids = rows.map(entityRowId).filter((id): id is string => !!id && !locationsRequested.current.has(id));
    if (!ids.length) return;
    ids.forEach((id) => locationsRequested.current.add(id));
    loadFolderPaths(targetName, ids)
      .then((paths) => setLocations((prev) => {
        const next = { ...prev };
        for (const id of ids) {
          // The folders above the record; a top-level record lives in the root.
          const crumbs = paths.get(id)?.slice(0, -1) ?? [];
          next[id] = crumbs.map((c) => c.label).join(" / ");
        }
        return next;
      }))
      .catch(() => {});
  }, [targetName]);

  // ---- decorations: which loaded rows the dropdown would offer, and how ----
  const [decorations, setDecorations] = useState<Record<string, { offered: boolean; d?: RefOptionDecorations["decorations"][string] }>>({});
  const requested = useRef(new Set<string>());
  const contextRef = useRef(optionContext);
  contextRef.current = optionContext;
  // Only a field with an owner can have PickerOptions; without one the rows are offered as listed.
  const decorated = !!optionContext?.ownerName;
  const highlightedIdRef = useRef(highlightedId);
  highlightedIdRef.current = highlightedId;
  const onRowsLoaded = useCallback((rows: EntityRecord[]) => {
    // The value the dialog opened on is highlighted before its row is known: adopt it on arrival.
    const opened = highlightedIdRef.current;
    if (opened) {
      const row = rows.find((r) => entityRowId(r) === opened);
      if (row) setHighlighted((prev) => prev ?? row);
    }
    if (searchingRef.current) locate(rows);
    if (!decorated) return;
    const ids = rows.map(entityRowId).filter((id): id is string => !!id && !requested.current.has(id));
    if (!ids.length) return;
    ids.forEach((id) => requested.current.add(id));
    const ctx = contextRef.current;
    api.decorateRefOptions({
      targetKind, targetName, ownerKind: ctx?.ownerKind, ownerName: ctx?.ownerName, where,
      fieldPath: ctx?.fieldPath ?? "", formValues: ctx?.formValues ?? {}, section: ctx?.section,
      rowIndex: ctx?.rowIndex, rowValues: ctx?.rowValues, documentId: ctx?.documentId, ids,
    }).then((res) => {
      const offered = new Set(res.offered);
      setDecorations((prev) => {
        const next = { ...prev };
        for (const id of ids) next[id] = { offered: offered.has(id), d: res.decorations[id] };
        return next;
      });
    }).catch(() => {
      // Undecidable: keep them unselectable rather than offer what the dropdown might not.
      setDecorations((prev) => {
        const next = { ...prev };
        for (const id of ids) next[id] = { offered: false };
        return next;
      });
    });
  }, [decorated, whereKey, targetKind, targetName, locate]); // eslint-disable-line react-hooks/exhaustive-deps

  const rowState = useCallback((row: EntityRecord): PickRowState | undefined => {
    const id = entityRowId(row);
    if (!id) return { disabled: true };
    const folderBadge = hierarchical && isFolder(row) ? t("ref.folder") : undefined;
    if (excludedIds.includes(id)) {
      return { disabled: true, reason: t("ref.selectedElsewhere"), badge: t("ref.alreadySelected") };
    }
    if (decorated) {
      const entry = decorations[id];
      if (!entry) return { disabled: true, badge: folderBadge }; // still resolving
      if (!entry.offered) return { hidden: true };
      const d = entry.d;
      if (d) return { disabled: d.disabled, reason: d.reason, badge: d.badge ?? folderBadge, tone: d.tone, color: d.color };
    }
    return folderBadge ? { badge: folderBadge } : undefined;
  }, [decorated, decorations, excludedIds, hierarchical, t]);

  // ---- navigation ----
  const openFolder = useCallback((crumbs: Crumb[]) => {
    setPath(crumbs);
    // The highlight belonged to the folder just left (or entered): nothing is selected anew.
    setHighlightedId(null);
    setHighlighted(null);
    setFocusId(null);
    setQuery("");
    setDebouncedQuery("");
    setListKey((k) => k + 1);
  }, []);

  const pick = useCallback((row: EntityRecord) => {
    const id = entityRowId(row);
    if (!id) return;
    onPick(id, row);
    onClose();
  }, [onPick, onClose]);

  const activate = useCallback((row: EntityRecord) => {
    if (hierarchical && isFolder(row) && path) {
      const id = entityRowId(row);
      if (!id) return;
      if (searching) {
        // A folder found by search: its own place in the tree comes from the path lookup.
        void loadFolderPaths(targetName, [id])
          .then((paths) => openFolder([path[0], ...(paths.get(id) ?? [{ id, label: rowLabel(row) }])]))
          .catch(() => openFolder([path[0], { id, label: rowLabel(row) }]));
      } else {
        openFolder([...path, { id, label: rowLabel(row) }]);
      }
      return;
    }
    pick(row);
  }, [hierarchical, path, searching, targetName, openFolder, pick]);

  const selectMode: ListSelectMode = useMemo(() => ({
    highlightedId,
    onHighlight: (row) => {
      setHighlightedId(entityRowId(row));
      setHighlighted(row);
    },
    onActivate: activate,
    rowState,
    onRowsLoaded,
    query: debouncedQuery,
    cellText: (row: EntityRecord, col: ListColumn) =>
      col.columnName === LOCATION_COLUMN ? locations[entityRowId(row) ?? ""] ?? "" : undefined,
  }), [highlightedId, activate, rowState, onRowsLoaded, debouncedQuery, locations]);

  const list = useMemo<ListDescriptor | null>(() => {
    if (!descriptor || !path) return null;
    // The field's `where` always applies; folder navigation adds the folder, a search spans all.
    const folderClause: WhereClause | undefined = hierarchical && !searching ? { parent: folderId ?? null } : undefined;
    // A search across folders adds a "Location" column (the hit's folder path), so same-named
    // records in different folders can be told apart.
    const columns = hierarchical && searching
      ? [...descriptor.columns, { columnName: LOCATION_COLUMN, label: t("ref.location"), width: "", sortable: false }]
      : descriptor.columns;
    return {
      ...descriptor,
      columns,
      embedded: true,
      fill: true,
      newUrl: null,
      canWrite: false,
      commands: [],
      baseWhere: andWhere(where, folderClause),
      defaultGroupBy: undefined,
      focus: focusId ?? undefined,
    };
  }, [descriptor, path, hierarchical, searching, folderId, whereKey, focusId, t]); // eslint-disable-line react-hooks/exhaustive-deps

  const highlightedState = highlighted ? rowState(highlighted) : undefined;
  const canPick = !!highlighted && entityRowId(highlighted) === highlightedId && !highlightedState?.disabled
    && !highlightedState?.hidden;
  const title = t("ref.pickTitle", { name: fieldLabel || descriptor?.title || targetName });
  const searchRef = useRef<HTMLInputElement | null>(null);

  return (
    <Dialog
      data-onno-picker=""
      className={cn(
        "flex min-h-0 flex-1 flex-col overflow-hidden border border-border bg-card shadow-2xl outline-none",
        touch ? "rounded-none" : "rounded-card"
      )}
    >
      <header className="flex shrink-0 items-center gap-3 border-b border-border px-5 py-3">
        <Heading slot="title" className="min-w-0 flex-1 truncate text-base font-semibold text-foreground">{title}</Heading>
        <Button type="button" variant="ghost" size="icon" onClick={onClose} aria-label={t("action.close")}
          className="size-8 shrink-0 text-muted-foreground hover:text-foreground">
          <X className="size-4" aria-hidden="true" />
        </Button>
      </header>

      <div className="flex shrink-0 flex-col gap-2 border-b border-border px-5 py-3">
        <label className="relative flex items-center">
          <Search className="pointer-events-none absolute left-3 size-4 text-muted-foreground" aria-hidden="true" />
          <input
            ref={searchRef}
            autoFocus
            value={query}
            onChange={(e) => setQuery(e.target.value)}
            placeholder={`${t("list.searchLabel")}…`}
            aria-label={t("list.searchLabel")}
            className="h-9 w-full rounded-field border border-input bg-transparent pl-9 pr-3 text-sm outline-none focus-visible:ring-2 focus-visible:ring-ring"
          />
        </label>
        {hierarchical && path ? (
          <FolderBreadcrumbs path={path} onOpen={openFolder} dimmed={searching} />
        ) : null}
        {where ? (
          <p className="flex items-center gap-1.5 text-xs text-muted-foreground" data-onno-picker-filter="">
            <Filter className="size-3.5 shrink-0" aria-hidden="true" />
            {t("ref.pickFiltered")}
          </p>
        ) : null}
      </div>

      <div className="flex min-h-0 flex-1">
        {hierarchical && path && !touch ? (
          <FolderTree
            catalog={targetName}
            root={path[0]}
            path={path}
            onOpen={openFolder}
            className="w-60 shrink-0 border-r border-border p-2"
          />
        ) : null}
        {/* The list sits inset like the search box above it, never flush with the dialog edge or
            the folder tree (the embedded list widget leaves its gutter to its host). */}
        <div
          className={cn(
            "flex min-h-0 min-w-0 flex-1 flex-col pb-4 pt-3",
            touch ? "px-3" : hierarchical ? "pl-4 pr-5" : "px-5"
          )}
          data-onno-picker-list=""
        >
          {failed ? (
            <p className="p-6 text-sm text-muted-foreground">{t("ref.pickEmpty")}</p>
          ) : list ? (
            <EntityListView
              key={listKey}
              list={list}
              selectMode={selectMode}
              toolbarOptions={{ title: false, search: false, viewSwitcher: false }}
            />
          ) : (
            <div className="flex flex-1 items-center justify-center text-muted-foreground">
              <Loader2 className="size-5 animate-spin" aria-hidden="true" />
            </div>
          )}
        </div>
      </div>

      <footer className="flex shrink-0 items-center justify-end gap-2 border-t border-border px-5 py-3">
        <Button type="button" variant="outline" onClick={onClose}>{t("action.cancel")}</Button>
        <Button type="button" disabled={!canPick} onClick={() => { if (highlighted) pick(highlighted); }}>
          {t("ref.pick")}
        </Button>
      </footer>
    </Dialog>
  );
}
