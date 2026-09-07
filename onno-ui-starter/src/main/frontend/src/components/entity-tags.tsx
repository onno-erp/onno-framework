import { Badge } from "./ui/badge";
import { ContextMenuCheckboxItem } from "./ui/context-menu";
import { useCallback, useEffect, useState } from "react";
import { Plus, X } from "lucide-react";
import { Button } from "./ui/button";
import { Input } from "./ui/input";
import { Popover, PopoverContent, PopoverTrigger } from "./ui/popover";
import { subscribeUiEvents } from "@/lib/ui-event-bus";

export type Tag = { id: string; name: string; color: string };
export type EntityTagsProps = { kind: "catalogs" | "documents"; name: string; id: string; readOnly?: boolean };
async function request<T>(url: string, method = "GET", body?: unknown): Promise<T> {
  const token = document.cookie.split(";").map(v => v.trim()).find(v => v.startsWith("XSRF-TOKEN="))?.slice(11);
  const response = await fetch(url, { method, credentials: "same-origin", headers: { "Content-Type": "application/json", ...(token ? { "X-XSRF-TOKEN": decodeURIComponent(token) } : {}) }, ...(body ? { body: JSON.stringify(body) } : {}) });
  if (!response.ok) { const error = await response.json().catch(() => ({})); throw new Error(error.message || error.detail || "Could not update tags"); }
  const text = await response.text(); return text ? JSON.parse(text) : undefined;
}
export function EntityTags({ kind, name, id, readOnly = false }: EntityTagsProps) {
  const base = `/api/tags/${kind}/${encodeURIComponent(name)}`;
  const [assigned, setAssigned] = useState<Tag[]>([]);
  const [library, setLibrary] = useState<Tag[]>([]);
  const [query, setQuery] = useState("");
  const [open, setOpen] = useState(false);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState("");
  const load = useCallback(async () => {
    const [tags, all] = await Promise.all([request<Tag[]>(`${base}/${id}`), request<Tag[]>(base)]);
    setAssigned(tags); setLibrary(all);
  }, [base, id]);
  useEffect(() => {
    let alive = true;
    setAssigned([]); setLibrary([]); setError("");
    const refresh = () => { if (alive) void load().catch(e => { if (alive) setError(e.message); }); };
    refresh();
    const local = () => refresh();
    window.addEventListener("onno-tags-changed", local);
    const unsubscribe = subscribeUiEvents(event => { if (event.type === "updated" && (event.id === id || event.entityType === "tag")) refresh(); });
    return () => { alive = false; unsubscribe(); window.removeEventListener("onno-tags-changed", local); };
  }, [load, id]);
  const mutate = async (work: () => Promise<unknown>) => {
    setBusy(true); setError("");
    try { await work(); await load(); window.dispatchEvent(new Event("onno-tags-changed")); setQuery(""); }
    catch (e) { setError((e as Error).message); }
    finally { setBusy(false); }
  };
  const matching = library.filter(tag => !assigned.some(value => value.id === tag.id) && tag.name.toLowerCase().includes(query.trim().toLowerCase()));
  return <div className="space-y-2">
    <div className="flex flex-wrap items-center gap-1.5">
      {assigned.map(tag => <Badge key={tag.id} color={tag.color} variant="secondary" className="max-w-full gap-1 rounded-pill py-0.5 pl-2.5 pr-1 text-xs font-medium">
        <span className="truncate">{tag.name}</span>
        {!readOnly && <button type="button" className="inline-flex size-5 shrink-0 items-center justify-center rounded-full text-current opacity-75 hover:bg-black/10 hover:opacity-100 focus-visible:outline focus-visible:outline-2 focus-visible:outline-current disabled:opacity-40" disabled={busy} aria-label={`Remove tag ${tag.name}`} onClick={() => void mutate(() => request(`${base}/${id}/${tag.id}`, "DELETE"))}><X size={12} /></button>}
      </Badge>)}
      {!assigned.length && readOnly && <span className="text-xs text-muted-foreground">No tags</span>}
      {!readOnly && <Popover open={open} onOpenChange={value => { setOpen(value); if (value) void load().catch(e => setError(e.message)); }}>
        <PopoverTrigger asChild><Button variant="ghost" size="toolbar" className="h-7 px-2 font-normal text-muted-foreground hover:text-foreground" disabled={busy}><Plus size={14} />Add tag</Button></PopoverTrigger>
        <PopoverContent className="w-60 space-y-2 rounded-card p-2" align="start">
          <Input className="h-8 rounded-field text-center shadow-none" autoFocus aria-label="Search tags" placeholder="Search tags…" value={query} maxLength={80} onChange={event => setQuery(event.target.value)} />
          <div className="max-h-48 overflow-y-auto">
            {matching.map(tag => <Button key={tag.id} variant="ghost" size="toolbar" className="h-8 w-full justify-center gap-2.5 rounded-field px-3 font-normal hover:bg-muted hover:text-foreground" disabled={busy} onClick={() => void mutate(() => request(`${base}/${id}/${tag.id}`, "POST"))}><span className="size-2 shrink-0 rounded-full" style={{ backgroundColor: tag.color }} />{tag.name}</Button>)}
            {!matching.length && <p className="text-xs text-muted-foreground">{query ? "No matching tags." : "No tags available. Add tags in the Tags catalog."}</p>}
          </div>
          {error && <p role="alert" className="text-xs text-destructive">{error}</p>}
        </PopoverContent>
      </Popover>}
    </div>
    {error && !open && <p role="alert" className="text-xs text-destructive">{error}</p>}
  </div>;
}

/** Direct catalog-tag toggles for a context-menu flyout. */
export function EntityTagMenu({ kind, name, id, readOnly = false }: EntityTagsProps) {
  const base = `/api/tags/${kind}/${encodeURIComponent(name)}`;
  const [library, setLibrary] = useState<Tag[]>([]);
  const [assigned, setAssigned] = useState<Tag[]>([]);
  const [loading, setLoading] = useState(true);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState("");
  useEffect(() => {
    let active = true;
    Promise.all([request<Tag[]>(base), request<Tag[]>(`${base}/${id}`)])
      .then(([all, selected]) => { if (active) { setLibrary(all); setAssigned(selected); } })
      .catch(e => { if (active) setError(e.message); })
      .finally(() => { if (active) setLoading(false); });
    return () => { active = false; };
  }, [base, id]);
  return <div className="max-h-64 overflow-y-auto">
    {loading && <p role="status" className="px-3 py-2 text-xs text-muted-foreground">Loading tags…</p>}
    {!loading && !library.length && !error && <p className="px-3 py-2 text-xs text-muted-foreground">No tags in the catalog</p>}
    {library.map(tag => {
      const checked = assigned.some(selected => selected.id === tag.id);
      return <ContextMenuCheckboxItem key={tag.id} role="menuitemcheckbox" aria-checked={checked}
        checked={checked} disabled={readOnly || busy} onSelect={() => {
          setBusy(true); setError("");
          void request(`${base}/${id}/${tag.id}`, checked ? "DELETE" : "POST")
            .then(() => request<Tag[]>(`${base}/${id}`))
            .then(selected => { setAssigned(selected); window.dispatchEvent(new Event("onno-tags-changed")); })
            .catch(e => setError(e.message)).finally(() => setBusy(false));
        }}><span className="size-2 shrink-0 rounded-full" style={{ backgroundColor: tag.color }} />{tag.name}</ContextMenuCheckboxItem>;
    })}
    {error && <p role="alert" className="px-3 py-2 text-xs text-destructive">{error}</p>}
  </div>;
}
