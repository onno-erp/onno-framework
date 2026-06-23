import { useEffect, useRef, useState } from "react";
import { ChevronsUpDown, Plus, Search } from "lucide-react";
import { api } from "@/lib/api";
import { cn, toSnakeCase } from "@/lib/utils";
import type { EntityRecord } from "@/lib/types";
import { Popover, PopoverContent, PopoverTrigger } from "@/components/ui/popover";
import { Avatar, AvatarImage, AvatarFallback } from "@/components/ui/avatar";
import { useMessages } from "@/providers/messages-provider";

interface RefSelectProps {
  /** The ref target's registered logical name (catalog or document). */
  targetName: string;
  /** Whether the target is a catalog or a document; drives which endpoints we hit. */
  refKind?: "catalog" | "document";
  /** Optional target column shown as a secondary line under each option's name (issue #184). */
  secondaryField?: string;
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
export function RefSelect({ targetName, refKind = "catalog", secondaryField, value, onChange }: RefSelectProps) {
  const t = useMessages();
  const name = toSnakeCase(targetName);
  const isDocument = refKind === "document";
  const [open, setOpen] = useState(false);
  const [query, setQuery] = useState("");
  const [items, setItems] = useState<EntityRecord[]>([]);
  const [selected, setSelected] = useState<EntityRecord | null>(null);
  const [loading, setLoading] = useState(false);

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
      const run = isDocument ? api.searchDocument(name, query, 30) : api.searchCatalog(name, query, 30);
      run
        .then((r) => !cancelled && setItems(r))
        .catch(() => {})
        .finally(() => !cancelled && setLoading(false));
      return () => {
        cancelled = true;
      };
    }, 180);
    return () => clearTimeout(t);
  }, [open, query, name, isDocument]);

  const pick = (item: EntityRecord) => {
    setSelected(item);
    onChange(item._id as string);
    setOpen(false);
  };

  const addNew = () => {
    setOpen(false);
    // Open the target's full new-form (a side pane in the islands layout) so every
    // required field is available; the user returns and picks the new record.
    const kind = isDocument ? "documents" : "catalogs";
    window.dispatchEvent(new CustomEvent("onno:action", { detail: `onno://${kind}/${name}/new` }));
  };

  return (
    <Popover open={open} onOpenChange={setOpen}>
      <PopoverTrigger asChild>
        <button
          type="button"
          className="flex h-9 w-full items-center justify-between gap-2 rounded-md border border-input bg-muted px-3 py-2 text-sm shadow-sm transition-colors hover:bg-accent focus:outline-none focus:ring-1 focus:ring-ring"
        >
          {selected ? (
            <RefRow item={selected} />
          ) : (
            <span className="text-muted-foreground">Select {targetName}…</span>
          )}
          <ChevronsUpDown className="size-4 shrink-0 text-muted-foreground" aria-hidden="true" />
        </button>
      </PopoverTrigger>
      <PopoverContent
        align="start"
        className="w-[var(--radix-popover-trigger-width)] overflow-hidden p-0"
      >
        {/* "+ New" pinned to the top so it's always reachable. */}
        <button
          type="button"
          onClick={addNew}
          className="flex w-full items-center gap-2 border-b px-3 py-2 text-sm font-medium text-foreground transition-colors hover:bg-accent"
        >
          <Plus className="size-4 text-muted-foreground" aria-hidden="true" />
          {t("ref.new", { name: targetName })}
        </button>
        <SearchBox value={query} onChange={setQuery} />
        <div className="max-h-64 overflow-y-auto py-1">
          {items.length === 0 ? (
            <div className="px-3 py-6 text-center text-sm text-muted-foreground">
              {loading ? t("loading.searching") : t("empty.noMatches")}
            </div>
          ) : (
            items.map((item) => (
              <button
                key={item._id as string}
                type="button"
                onClick={() => pick(item)}
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
      </PopoverContent>
    </Popover>
  );
}

function SearchBox({ value, onChange }: { value: string; onChange: (v: string) => void }) {
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
        placeholder="Search…"
        className="h-9 w-full bg-transparent text-sm outline-none placeholder:text-muted-foreground"
      />
    </div>
  );
}

function RefRow({ item, secondary }: { item: EntityRecord; secondary?: string }) {
  const display = displayOf(item);
  const avatarUrl = (item.avatar_url as string | undefined) ?? undefined;
  const code = item._code as string | undefined;
  // The disambiguating secondary line (e.g. a phone), shown under the name in the options list.
  const sub = secondary ? item[secondary] : undefined;
  const subText = sub == null ? "" : String(sub);
  return (
    <span className="inline-flex min-w-0 items-center gap-2" title={code ? `${display} · ${code}` : display}>
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
