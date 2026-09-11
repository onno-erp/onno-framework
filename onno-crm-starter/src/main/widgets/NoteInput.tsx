import { text as t, useTranslate } from "@onno/widget-sdk";
import { Button, Popover, PopoverAnchor, PopoverContent, Textarea, useEffect, useRef, useState } from "@onno/widget-sdk";
import type { ClipboardEvent, KeyboardEvent } from "react";

type Reference = {kind: "catalogs" | "documents"; name: string; id: string; display: string; entity: string; avatarUrl?: string};
type Pick = Reference & {marker: "@" | "#"};
function serialize(text: string, picks: Pick[]) {
  return picks.reduce((body, pick) => body.replace(`${pick.marker}${pick.display}`,
    () => `${pick.marker}[${pick.display.replace(/]/g, "")}](${pick.kind}/${pick.name}/${pick.id})`), text);
}

/** Inbox input: the existing note field with a small reference picker, no separate comment panel. */
export function NoteInput({value, disabled, onChange, onSubmit}: {
  value: string; disabled: boolean; onChange: (text: string, body: string) => void; onSubmit: () => void;
}) {
  const input = useRef<HTMLTextAreaElement>(null);
  const picks = useRef<Pick[]>([]);
  const current = useRef(value); current.current = value;
  const mounted = useRef(true);
  useEffect(() => { mounted.current = true; return () => {mounted.current = false;}; }, []);
  const caret = useRef<number | null>(null);
  const [query, setQuery] = useState<{marker: "@" | "#"; text: string; start: number; end: number} | null>(null);
  const [options, setOptions] = useState<Reference[]>([]);
  const [active, setActive] = useState(0);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState(false);
  const change = (text: string) => onChange(text, serialize(text, picks.current));
  useEffect(() => { if (!value) { picks.current = []; setQuery(null); } }, [value]);
  useEffect(() => {
    if (caret.current !== null) { input.current?.setSelectionRange(caret.current, caret.current); caret.current = null; }
  }, [value]);
  useEffect(() => {
    const controller = new AbortController();
    setOptions([]); setActive(0); setError(false);
    if (!query) { setLoading(false); return; }
    setLoading(true);
    const timer = setTimeout(() => {
      const params = new URLSearchParams({q: query.text});
      if (query.marker === "@") params.set("kind", "people");
      void fetch(`/api/mentions?${params}`, {credentials: "same-origin", signal: controller.signal})
        .then(response => { if (!response.ok) throw new Error(); return response.json(); })
        .then(rows => { if (!controller.signal.aborted) setOptions(rows); })
        .catch(() => { if (!controller.signal.aborted) setError(true); })
        .finally(() => { if (!controller.signal.aborted) setLoading(false); });
    }, 140);
    return () => { controller.abort(); clearTimeout(timer); };
  }, [query]);
  const locate = (text: string, end: number) => {
    const match = /(?:^|\s)([@#])([^\s@#]*)$/.exec(text.slice(0, end));
    setQuery(match ? {marker: match[1] as "@" | "#", text: match[2], start: end - match[2].length - 1, end} : null);
  };
  const choose = (option: Reference) => {
    if (!query) return;
    const inserted = `${query.marker}${option.display} `;
    const next = value.slice(0, query.start) + inserted + value.slice(query.end);
    const pick = {...option, marker: query.marker};
    if (serialize(next, [...picks.current, pick]).length > 8000) return;
    picks.current.push(pick); caret.current = query.start + inserted.length;
    change(next); setQuery(null); input.current?.focus();
  };
  const paste = (event: ClipboardEvent<HTMLTextAreaElement>) => {
    const text = event.clipboardData.getData("text/plain");
    const links = [...text.matchAll(/(?:https?:\/\/[^\s/]+)?\/ui\/(catalogs|documents)\/([a-z0-9_]+)\/([0-9a-f-]{36})/gi)]
      .filter(match => !match[0].startsWith("http") || match[0].startsWith(window.location.origin + "/ui/"));
    if (!links.length) return;
    event.preventDefault();
    const snapshot = value, start = event.currentTarget.selectionStart, end = event.currentTarget.selectionEnd;
    void (async () => {
      let converted = text; const added: Pick[] = [];
      for (const link of links) {
        try {
          const response = await fetch(`/api/mentions/resolve?${new URLSearchParams({kind:link[1], name:link[2], id:link[3]})}`, {credentials:"same-origin"});
          if (!response.ok) continue;
          const ref = await response.json();
          if (!ref.readable || !ref.display) continue;
          const marker = ref.person ? "@" : "#";
          added.push({...ref, marker}); converted = converted.replace(link[0], `${marker}${ref.display}`);
        } catch { /* Keep unresolved links as pasted text. */ }
      }
      if (!mounted.current || current.current !== snapshot) return;
      const next = snapshot.slice(0,start) + converted + snapshot.slice(end);
      if (serialize(next, [...picks.current, ...added]).length > 8000) return;
      picks.current.push(...added); caret.current = start + converted.length; change(next);
    })();
  };
  const keyDown = (event: KeyboardEvent<HTMLTextAreaElement>) => {
    if (query) {
      if (event.key === "Escape") { event.preventDefault(); setQuery(null); return; }
      if (options.length && ["ArrowDown", "ArrowUp", "Enter", "Tab"].includes(event.key)) {
        event.preventDefault();
        if (event.key === "Enter" || event.key === "Tab") choose(options[active]);
        else setActive(index => (index + (event.key === "ArrowDown" ? 1 : -1) + options.length) % options.length);
        return;
      }
    }
    if (event.key === "Enter" && (event.metaKey || event.ctrlKey)) { event.preventDefault(); onSubmit(); }
  };
  return <Popover open={!!query} onOpenChange={(open: boolean) => { if (!open) setQuery(null); }}>
    <PopoverContent side="top" align="start" sideOffset={8} className="w-[var(--radix-popover-trigger-width)] max-w-[calc(100vw-32px)] p-1.5"
      onOpenAutoFocus={(event: Event) => event.preventDefault()} onCloseAutoFocus={(event: Event) => event.preventDefault()}>
      {loading || error || !options.length ? <p role="status" className="px-2 py-2 text-xs text-muted-foreground">{loading ? "Searching…" : error ? t("crm.note.referencesError") : t("crm.note.noMatches")}</p> :
        <div role="listbox" aria-label={t("crm.note.references")} className="max-h-48 overflow-y-auto">
          {options.map((option, index) => <Button variant="ghost" type="button" role="option" aria-selected={index === active}
            key={`${option.kind}/${option.name}/${option.id}`} onMouseDown={event => {event.preventDefault(); choose(option);}}
            onMouseEnter={() => setActive(index)}
            className={`h-auto whitespace-normal font-normal flex w-full items-center gap-2 rounded-field px-2 py-2 text-left text-xs ${index === active ? "bg-accent text-accent-foreground" : "hover:bg-accent/50"}`}>
            {option.avatarUrl && <img src={option.avatarUrl} alt="" className="size-6 rounded-full object-cover" />}
            <span className="min-w-0 flex-1 truncate">{option.display}</span><span className="text-muted-foreground">{option.entity}</span>
          </Button>)}
        </div>}
    </PopoverContent>
    <PopoverAnchor asChild><div><Textarea ref={input} value={value} disabled={disabled} maxLength={8000}
      aria-label={t("crm.note.write")} placeholder={t("crm.note.placeholder")}
      onChange={(event: {target: HTMLTextAreaElement}) => {change(event.target.value); locate(event.target.value, event.target.selectionStart);}}
      onKeyDown={keyDown} onPaste={paste} onBlur={() => setQuery(null)}
      className="min-h-20 resize-none border-0 bg-transparent dark:bg-transparent px-2 shadow-none focus-visible:outline-none focus-visible:ring-0 focus-visible:ring-offset-0 focus-visible:shadow-none" />
  </div></PopoverAnchor></Popover>;
}
