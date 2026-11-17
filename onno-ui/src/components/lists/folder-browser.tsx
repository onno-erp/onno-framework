import { useRequestScope } from "../../hooks/use-request-scope";
import { useCallback, useEffect, useState } from "react";
import { ChevronDown, ChevronRight, Folder, FolderOpen, Loader2 } from "lucide-react";
import { api } from "../../lib/api";
import { cn } from "../../lib/utils";
import type { EntityRecord } from "../../lib/types";
import { entityRowId } from "./entity-list";
import { useMessages } from "../../providers/messages-provider";

/**
 * Folder browsing for hierarchical catalogs (#411): a lazily expanded folder tree, breadcrumbs for
 * the current folder, and path lookups. The reference picker dialog composes them beside its list;
 * any list over a hierarchical catalog can reuse them. Every piece reads through the record-scoped
 * catalog hierarchy API (`/children?folders=true`, `/tree?ids=`), so it shows only what the viewer
 * may read.
 */

/** One step of a folder path; a null id is the catalog root. */
export type Crumb = { id: string | null; label: string };

/** The human label of a catalog row (description, else number or code). */
export function rowLabel(row: EntityRecord): string {
  return String(row.description ?? row._description ?? row.number ?? row._number ?? row.code ?? entityRowId(row) ?? "");
}

/** Whether a catalog row is a folder. */
export function isFolder(row: EntityRecord): boolean {
  return row.folder === true || row._is_folder === true;
}

/**
 * The path to each of {@code ids} in one request: for every record the viewer may read, the crumbs
 * from the first readable ancestor down to the record itself (the root crumb not included). A
 * record whose ancestor is out of scope starts its path at the first one the viewer can read.
 */
export async function loadFolderPaths(catalog: string, ids: string[]): Promise<Map<string, Crumb[]>> {
  const out = new Map<string, Crumb[]>();
  if (!ids.length) return out;
  const tree = await api.getCatalogPaths(catalog, ids);
  const walk = (nodes: EntityRecord[], above: Crumb[]) => {
    for (const node of nodes) {
      const id = entityRowId(node);
      if (!id) continue;
      const path = [...above, { id, label: rowLabel(node) }];
      out.set(id, path);
      const children = node.children;
      if (Array.isArray(children)) walk(children as EntityRecord[], path);
    }
  };
  walk(tree, []);
  return out;
}

/** The breadcrumbs of the current folder; any crumb opens that level. */
export function FolderBreadcrumbs({ path, onOpen, dimmed = false }: {
  path: Crumb[];
  onOpen: (crumbs: Crumb[]) => void;
  /** Faded while a search spans every folder. */
  dimmed?: boolean;
}) {
  const t = useMessages();
  return (
    <nav aria-label={t("ref.folders")} className={cn("flex min-w-0 flex-wrap items-center gap-1 text-sm", dimmed && "opacity-50")}>
      {path.map((crumb, i) => (
        <span key={crumb.id ?? "root"} className="flex min-w-0 items-center gap-1">
          {i > 0 ? <ChevronRight className="size-3.5 shrink-0 text-muted-foreground" aria-hidden="true" /> : null}
          <button
            type="button"
            onClick={() => onOpen(path.slice(0, i + 1))}
            aria-current={i === path.length - 1 ? "location" : undefined}
            className={cn(
              "max-w-56 truncate rounded-control px-1.5 py-0.5 hover:bg-accent",
              i === path.length - 1 ? "font-medium text-foreground" : "text-muted-foreground"
            )}
          >
            {crumb.label}
          </button>
        </span>
      ))}
    </nav>
  );
}

/**
 * The folder sidebar: folders only, expanded lazily one level at a time. A folder shows an expand
 * arrow only when the server says it holds a readable sub-folder; opening a folder hands its full
 * crumb path to {@code onOpen}.
 */
export function FolderTree({ catalog, root, path, onOpen, className }: {
  catalog: string;
  /** The root crumb (the catalog's title). */
  root: Crumb;
  /** The current folder's path; every folder on it is shown expanded. */
  path: Crumb[];
  onOpen: (crumbs: Crumb[]) => void;
  className?: string;
}) {
  const t = useMessages();
  const requests = useRequestScope(catalog);
  const [children, setChildren] = useState<Record<string, EntityRecord[] | "loading">>({});
  const [nextCursors, setNextCursors] = useState<Record<string, string | null>>({});
  const [loadingMore, setLoadingMore] = useState<string | null>(null);
  const [expanded, setExpanded] = useState<Set<string>>(() => new Set(["root"]));
  const current = path[path.length - 1]?.id ?? null;

  const load = useCallback((parent: string | null) => {
    const key = parent ?? "root";
    const current = requests.capture();
    setChildren((prev) => (prev[key] ? prev : { ...prev, [key]: "loading" }));
    api.getCatalogChildFolders(catalog, parent)
      .then((page) => {
        if (!current()) return;
        setChildren((prev) => ({ ...prev, [key]: page.rows }));
        setNextCursors(prev => ({ ...prev, [key]: page.nextCursor }));
      })
      .catch(() => { if (current()) setChildren((prev) => ({ ...prev, [key]: [] })); });
  }, [catalog, requests]);

  useEffect(() => {
    setChildren({}); setNextCursors({}); setLoadingMore(null); setExpanded(new Set(["root"]));
  }, [catalog]);

  useEffect(() => {
    setExpanded((prev) => {
      const next = new Set(prev);
      for (const c of path) next.add(c.id ?? "root");
      return next;
    });
  }, [path]);
  useEffect(() => {
    for (const key of expanded) {
      if (children[key] === undefined) load(key === "root" ? null : key);
    }
  }, [expanded, children, load]);

  const loadMore = async (key: string) => {
    const cursor = nextCursors[key];
    if (!cursor || loadingMore) return;
    const current = requests.capture();
    setLoadingMore(key);
    try {
      const page = await api.getCatalogChildFolders(catalog, key === "root" ? null : key, cursor);
      if (!current()) return;
      setChildren(previous => {
        const old = previous[key];
        const rows = Array.isArray(old) ? old : [];
        const have = new Set(rows.map(entityRowId));
        return { ...previous, [key]: [...rows, ...page.rows.filter(row => !have.has(entityRowId(row)))] };
      });
      setNextCursors(previous => ({ ...previous, [key]: page.nextCursor }));
    } catch { /* Keep this cursor available for an explicit retry. */ }
    finally { if (current()) setLoadingMore(null); }
  };

  const toggle = (key: string) =>
    setExpanded((prev) => {
      const next = new Set(prev);
      if (next.has(key)) next.delete(key); else next.add(key);
      return next;
    });

  const renderNode = (crumbs: Crumb[], depth: number, hasChildFolders: boolean) => {
    const node = crumbs[crumbs.length - 1];
    const key = node.id ?? "root";
    const kids = children[key];
    const expandable = hasChildFolders && !(Array.isArray(kids) && kids.length === 0);
    const isOpen = expandable && expanded.has(key);
    const active = node.id === current;
    return (
      <li key={key} role="treeitem" aria-expanded={expandable ? isOpen : undefined} aria-selected={active}>
        <div className={cn("flex items-center gap-1 rounded-control pr-2", active ? "bg-primary/10 text-foreground" : "hover:bg-accent")}
          style={{ paddingLeft: 4 + depth * 14 }}>
          {expandable ? (
            <button type="button" onClick={() => toggle(key)} aria-label={node.label} data-onno-tree-toggle=""
              className="flex size-5 shrink-0 items-center justify-center text-muted-foreground" tabIndex={-1}>
              {isOpen && kids === "loading" ? <Loader2 className="size-3 animate-spin" aria-hidden="true" />
                : isOpen ? <ChevronDown className="size-3.5" aria-hidden="true" />
                : <ChevronRight className="size-3.5" aria-hidden="true" />}
            </button>
          ) : (
            <span className="size-5 shrink-0" aria-hidden="true" />
          )}
          <button type="button" onClick={() => onOpen(crumbs)}
            className={cn("flex min-w-0 flex-1 items-center gap-1.5 py-1 text-left text-sm", active && "font-medium")}>
            {isOpen ? <FolderOpen className="size-4 shrink-0 text-muted-foreground" aria-hidden="true" />
              : <Folder className="size-4 shrink-0 text-muted-foreground" aria-hidden="true" />}
            <span className="truncate">{node.label}</span>
          </button>
        </div>
        {isOpen && Array.isArray(kids) && kids.length ? (
          <ul role="group">
            {kids.map((row) => {
              const id = entityRowId(row);
              return id ? renderNode([...crumbs, { id, label: rowLabel(row) }], depth + 1, row.hasChildFolders === true) : null;
            })}
            {nextCursors[key] && <li><button type="button" disabled={loadingMore !== null}
              onClick={() => void loadMore(key)} className="px-4 py-1 text-sm">Load more</button></li>}
          </ul>
        ) : null}
      </li>
    );
  };

  return (
    <aside aria-label={t("ref.folders")} className={cn("overflow-y-auto", className)} data-onno-folder-tree="">
      {/* The root always offers its top-level folders (an empty answer then hides the arrow). */}
      <ul role="tree">{renderNode([root], 0, true)}</ul>
    </aside>
  );
}
