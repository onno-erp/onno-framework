import { useEffect, useRef, useState } from "react";
import { ChevronsUpDown, Plus, Search } from "lucide-react";
import { api } from "@/lib/api";
import { cn, toSnakeCase } from "@/lib/utils";
import type { EntityRecord } from "@/lib/types";
import { Popover, PopoverContent, PopoverTrigger } from "@/components/ui/popover";
import { FacetSheet, useTouchLayout } from "@/components/ui/facet-sheet";
import { Avatar, AvatarImage, AvatarFallback } from "@/components/ui/avatar";
import { useMessages } from "@/providers/messages-provider";
import {
  cancelQuickCreate,
  claimQuickCreated,
  QUICK_CREATED_EVENT,
  requestQuickCreate,
} from "@/lib/quick-create";

interface RefSelectProps {
  /** The ref target's registered logical name (catalog or document). */
  targetName: string;
  /** Whether the target is a catalog or a document; drives which endpoints we hit. */
  refKind?: "catalog" | "document";
  /** Optional target column shown as a secondary line under each option's name (issue #184). */
  secondaryField?: string;
  /**
   * Resolved cascading predicate (refFilter with ${...} already substituted) sent to the
   * typeahead's ?filter=, so only compatible records are offered. Undefined = unfiltered.
   */
  filter?: string;
  value?: string;
  onChange: (id: string) => void;
}

function initials(name: string | undefined): string {
  if (!name) return "?";
  const parts = name.trim().split(/[\s._-]+/).filter(Boolean);
  if (parts.length >= 2) return (parts[0][0] + parts[1][0]).toUpperCase();
  return (name.trim().slice(0, 2) || "?").toUpperCase();
}

function displayOf(item: EntityRecord): string {
  const desc = item._description as string | undefined;
  if (desc && desc.trim()) return desc;
  // Catalogs label by code; documents have no code/description, so fall back to number.
  return (
    (item._code as string) ?? (item._number as string) ?? (item._id as string) ?? ""
  );
}

/**
 * A searchable ref picker backed by the server-side catalog typeahead, so it never
 * loads the whole catalog (a 2000-row Clients list stays on the server). Opens a popover
 * with a search box + capped results; the selected record's label is fetched by id so it
 * shows even when it isn't in the current result page. "+ New" is pinned at the top so
 * it's always reachable regardless of how many matches there are.
 */
export function RefSelect({ targetName, refKind = "catalog", secondaryField, filter, value, onChange }: RefSelectProps) {
  const t = useMessages();
  const name = toSnakeCase(targetName);
  const isDocument = refKind === "document";
  const [open, setOpen] = useState(false);
  const [query, setQuery] = useState("");
  const [items, setItems] = useState<EntityRecord[]>([]);
  const [selected, setSelected] = useState<EntityRecord | null>(null);
  const [loading, setLoading] = useState(false);
  const touchLayout = useTouchLayout();
  const kind = isDocument ? "documents" : "catalogs";

  // "+ New" quick-create hand-off. The result arrives as module state + a window event (not a
  // callback — this component can be remounted, or unmounted outright in single-pane layouts,
  // while the create form is open). We claim it either live (event listener) or on mount after
  // a remount; the claim is gated on our request token or, post-remount, on being an empty
  // same-target picker (see quick-create.ts).
  const requestTokenRef = useRef<number | null>(null);
  const latest = useRef({ value, onChange });
  latest.current = { value, onChange };
  useEffect(() => {
    const claim = () => {
      const cur = latest.current;
      const id = claimQuickCreated(kind, name, requestTokenRef.current, !cur.value);
      // Claims are idempotent (the slot lives until its TTL so a remounted picker can re-claim
      // a value the remount wiped) — only fire onChange when it actually changes the field.
      if (id && id !== cur.value) cur.onChange(id);
    };
    claim(); // remount case: the result may already be waiting
    window.addEventListener(QUICK_CREATED_EVENT, claim);
    return () => window.removeEventListener(QUICK_CREATED_EVENT, claim);
  }, [kind, name]);

  // Resolve the current value's label (it may not be in the result page).
  useEffect(() => {
    if (!value) {
      setSelected(null);
      return;
    }
    if (selected && selected._id === value) return;
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
      const run = isDocument
        ? api.searchDocument(name, query, 30, filter)
        : api.searchCatalog(name, query, 30, filter);
      run
        .then((r) => !cancelled && setItems(r))
        .catch(() => {})
        .finally(() => !cancelled && setLoading(false));
      return () => {
        cancelled = true;
      };
    }, 180);
    return () => clearTimeout(t);
  }, [open, query, name, isDocument, filter]);

  const pick = (item: EntityRecord) => {
    // A manual pick supersedes any outstanding "+ New" hand-off.
    cancelQuickCreate(kind, name);
    setSelected(item);
    onChange(item._id as string);
    setOpen(false);
  };

  const addNew = () => {
    setOpen(false);
    // Open the target's full new-form (a side pane in the islands layout) so every required
    // field is available. Register the hand-off first: when that form saves, the new record's
    // id lands straight in this field instead of the user re-finding it in the picker.
    requestTokenRef.current = requestQuickCreate(kind, name);
    window.dispatchEvent(new CustomEvent("onno:action", { detail: `onno://${kind}/${name}/new` }));
  };

  const trigger = (
    <button
      type="button"
      onClick={() => setOpen(true)}
      className="flex h-9 w-full min-w-0 items-center justify-between gap-2 overflow-hidden rounded-md border border-input bg-muted px-3 py-2 text-sm shadow-sm transition-colors hover:bg-accent focus:outline-none focus:ring-1 focus:ring-ring"
    >
      {selected ? (
        <RefRow item={selected} />
      ) : (
        <span className="min-w-0 truncate text-muted-foreground">{t("form.select", { name: targetName })}</span>
      )}
      <ChevronsUpDown className="size-4 shrink-0 text-muted-foreground" aria-hidden="true" />
    </button>
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
      onAddNew={addNew}
    />
  );

  if (touchLayout) {
    return (
      <>
        {trigger}
        {open ? (
          <FacetSheet label={t("form.select", { name: targetName })} onClose={() => setOpen(false)}>
            {content}
          </FacetSheet>
        ) : null}
      </>
    );
  }

  return (
    <Popover open={open} onOpenChange={setOpen}>
      <PopoverTrigger asChild>
        {trigger}
      </PopoverTrigger>
      <PopoverContent
        align="start"
        // z-[70] clears the action-form modal (z-[60]) so the picker floats above it, not behind;
        // harmless in the entity form, which isn't a portal-stacked overlay.
        className="z-[70] w-[var(--radix-popover-trigger-width)] overflow-hidden p-0"
      >
        {content}
      </PopoverContent>
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
}: {
  targetName: string;
  secondaryField?: string;
  value?: string;
  query: string;
  onQueryChange: (value: string) => void;
  items: EntityRecord[];
  loading: boolean;
  onPick: (item: EntityRecord) => void;
  onAddNew: () => void;
}) {
  const t = useMessages();
  return (
    <>
      {/* "+ New" pinned to the top so it's always reachable. */}
      <button
        type="button"
        onClick={onAddNew}
        className="flex w-full items-center gap-2 border-b px-3 py-2 text-sm font-medium text-foreground transition-colors hover:bg-accent"
      >
        <Plus className="size-4 text-muted-foreground" aria-hidden="true" />
        {t("ref.new", { name: targetName })}
      </button>
      <SearchBox value={query} onChange={onQueryChange} />
      <div className="max-h-64 overflow-y-auto py-1 sm:max-h-64">
        {items.length === 0 ? (
          <div className="px-3 py-6 text-center text-sm text-muted-foreground">
            {loading ? t("loading.searching") : t("empty.noMatches")}
          </div>
        ) : (
          items.map((item) => (
            <button
              key={item._id as string}
              type="button"
              onClick={() => onPick(item)}
              className={cn(
                "flex w-full items-center px-3 py-1.5 text-left text-sm transition-colors hover:bg-accent",
                item._id === value && "bg-accent/60"
              )}
            >
              <RefRow item={item} secondary={secondaryField} />
            </button>
          ))
        )}
      </div>
    </>
  );
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

function RefRow({ item, secondary }: { item: EntityRecord; secondary?: string }) {
  const display = displayOf(item);
  const avatarUrl =
    (item.avatar_url as string | undefined) ??
    (item._avatar as string | undefined) ??
    (item.avatarUrl as string | undefined) ??
    undefined;
  const code = item._code as string | undefined;
  // The disambiguating secondary line (e.g. a phone), shown under the name in the options list.
  const sub = secondary ? item[secondary] : undefined;
  const subText = sub == null ? "" : String(sub);
  return (
    <span className="inline-flex min-w-0 flex-1 items-center gap-2" title={code ? `${display} · ${code}` : display}>
      {avatarUrl ? (
        <Avatar className="h-5 w-5 text-[9px]">
          <AvatarImage src={avatarUrl} alt={display} />
          <AvatarFallback>{initials(display)}</AvatarFallback>
        </Avatar>
      ) : null}
      <span className="flex min-w-0 flex-col">
        <span className="truncate">{display}</span>
        {subText ? <span className="truncate text-xs text-muted-foreground">{subText}</span> : null}
      </span>
    </span>
  );
}
