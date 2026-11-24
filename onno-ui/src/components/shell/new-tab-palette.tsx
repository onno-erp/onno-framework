import { useEffect, useId, useMemo, useRef, useState, type KeyboardEvent } from "react";
import { ArrowBigUp, CornerDownLeft, Plus, Search, type LucideIcon } from "lucide-react";
import { Popover, PopoverContent, PopoverTrigger } from "../ui/popover";
import { Icon } from "../../lib/icons";
import { shortcutLabel } from "../../lib/keybindings";
import type { Translate } from "../../lib/messages";
import { filterDestinations, type NewTabDestination } from "../../lib/new-tab-destinations";
import { placementForClick, type OpenPlacement } from "../../lib/open-placement";
import { cn } from "../../lib/utils";

/** The key that opens the palette for the focused island (⌘T / Ctrl+T belongs to the browser). */
export const NEW_TAB_SHORTCUT = { key: "t", alt: true } as const;

type NewTabPaletteProps = {
  open: boolean;
  onOpenChange: (open: boolean) => void;
  destinations: readonly NewTabDestination[];
  /** Routes already open in some tab; picking one switches to it instead of opening it twice. */
  openPaths: ReadonlySet<string>;
  /** `new-tab` for a plain pick, `new-island` when Shift was held. */
  onPick: (path: string, placement: Exclude<OpenPlacement, "tab">) => void;
  t: Translate;
};

const GROUP_TITLE = { screens: "workspace.newTab.screens", create: "workspace.newTab.create" } as const;

/**
 * The "+" at the end of an island's tab strip: a searchable list of every screen and New form the
 * viewer can reach. It means the same thing in every island and always has something to open.
 * Enter (or a click) opens the choice as a new tab of that island, Shift+Enter beside it.
 */
export function NewTabPalette({ open, onOpenChange, destinations, openPaths, onPick, t }: NewTabPaletteProps) {
  const [query, setQuery] = useState("");
  const [active, setActive] = useState(0);
  const listRef = useRef<HTMLDivElement | null>(null);
  const id = useId();
  const matches = useMemo(() => filterDestinations(destinations, query), [destinations, query]);
  const current = matches[Math.min(active, matches.length - 1)];

  // Every opening starts from the full list.
  useEffect(() => {
    if (!open) return;
    setQuery("");
    setActive(0);
  }, [open]);

  useEffect(() => {
    if (!open || !current) return;
    listRef.current?.querySelector(`[data-path="${CSS.escape(current.path)}"]`)?.scrollIntoView({ block: "nearest" });
  }, [open, current]);

  const pick = (destination: NewTabDestination, placement: OpenPlacement) => {
    onOpenChange(false);
    onPick(destination.path, placement === "new-island" ? "new-island" : "new-tab");
  };

  const onKeyDown = (event: KeyboardEvent<HTMLInputElement>) => {
    if (event.key === "ArrowDown" || event.key === "ArrowUp") {
      event.preventDefault();
      if (matches.length === 0) return;
      const step = event.key === "ArrowDown" ? 1 : -1;
      setActive((index) => (Math.min(index, matches.length - 1) + step + matches.length) % matches.length);
    } else if (event.key === "Enter" && current) {
      event.preventDefault();
      pick(current, placementForClick(event));
    }
  };

  const optionId = (destination: NewTabDestination) => `${id}-${matches.indexOf(destination)}`;

  return (
    <Popover open={open} onOpenChange={onOpenChange}>
      <PopoverTrigger asChild>
        <button
          type="button"
          aria-label={t("workspace.newTab")}
          title={`${t("workspace.newTab")} (${shortcutLabel(NEW_TAB_SHORTCUT)})`}
          data-new-tab
          className={cn(
            "grid size-7 shrink-0 place-items-center rounded-md text-muted-foreground transition hover:bg-foreground/10 hover:text-foreground",
            open && "bg-foreground/10 text-foreground",
          )}
        >
          <Plus className="size-4" aria-hidden="true" />
        </button>
      </PopoverTrigger>
      <PopoverContent align="start" sideOffset={8} className="w-80 overflow-hidden p-0" data-new-tab-palette>
        <label className="flex items-center gap-2 border-b px-3">
          <Search className="size-4 shrink-0 text-muted-foreground" aria-hidden="true" />
          <input
            role="combobox"
            aria-expanded="true"
            aria-controls={`${id}-list`}
            aria-activedescendant={current ? optionId(current) : undefined}
            aria-label={t("workspace.newTab.search")}
            placeholder={t("workspace.newTab.search")}
            autoComplete="off"
            spellCheck={false}
            value={query}
            onChange={(event) => {
              setQuery(event.target.value);
              setActive(0);
            }}
            onKeyDown={onKeyDown}
            className="h-10 min-w-0 flex-1 bg-transparent text-sm outline-none placeholder:text-muted-foreground"
          />
        </label>
        <div ref={listRef} id={`${id}-list`} role="listbox" aria-label={t("workspace.newTab")} className="max-h-80 overflow-y-auto p-1">
          {matches.length === 0 ? (
            <p className="px-3 py-6 text-center text-sm text-muted-foreground">{t("workspace.newTab.empty")}</p>
          ) : null}
          {matches.map((destination, index) => {
            const selected = destination === current;
            const startsGroup = index === 0 || matches[index - 1].group !== destination.group;
            return (
              <div key={destination.path}>
                {startsGroup ? (
                  <div role="presentation" className="px-2 pb-1 pt-2 text-xs font-medium text-muted-foreground">
                    {t(GROUP_TITLE[destination.group])}
                  </div>
                ) : null}
                <div
                  id={optionId(destination)}
                  role="option"
                  aria-selected={selected}
                  data-path={destination.path}
                  className={cn(
                    "flex h-8 cursor-pointer items-center gap-2 rounded-field px-2 text-sm",
                    selected ? "bg-accent text-accent-foreground" : "text-foreground",
                  )}
                  onMouseMove={() => setActive(index)}
                  // Keep the caret in the search field: the list is driven from there.
                  onMouseDown={(event) => event.preventDefault()}
                  onClick={(event) => pick(destination, placementForClick(event))}
                >
                  <span className="grid size-4 shrink-0 place-items-center text-muted-foreground" aria-hidden="true">
                    {/* A New form reads as "create", whatever icon its entity has. */}
                    {destination.group === "create"
                      ? <Plus className="size-[15px]" />
                      : <Icon name={destination.icon ?? "circle"} size={15} />}
                  </span>
                  <span className="min-w-0 flex-1 truncate">{destination.label}</span>
                  {openPaths.has(destination.path) ? (
                    <span className="shrink-0 rounded-control bg-foreground/10 px-1.5 py-0.5 text-[11px] text-muted-foreground">
                      {t("workspace.newTab.alreadyOpen")}
                    </span>
                  ) : destination.hint ? (
                    <span className="max-w-[45%] shrink-0 truncate text-xs text-muted-foreground">{destination.hint}</span>
                  ) : null}
                </div>
              </div>
            );
          })}
        </div>
        <div className="flex items-center gap-4 border-t px-3 py-2 text-xs text-muted-foreground">
          <Hint keys={[CornerDownLeft]} label={t("workspace.newTab")} />
          <Hint keys={[ArrowBigUp, CornerDownLeft]} label={t("workspace.openBeside")} />
        </div>
      </PopoverContent>
    </Popover>
  );
}

/** A footer shortcut: one keycap per key, then what it does. */
function Hint({ keys, label }: { keys: LucideIcon[]; label: string }) {
  return (
    <span className="flex items-center gap-1.5">
      <span className="flex items-center gap-0.5" aria-hidden="true">
        {keys.map((Key, index) => (
          <kbd
            key={index}
            className="grid size-5 place-items-center rounded-[5px] border border-border bg-muted text-muted-foreground shadow-[0_1px_0_hsl(var(--border))]"
          >
            <Key className="size-3" strokeWidth={2.25} />
          </kbd>
        ))}
      </span>
      {label}
    </span>
  );
}
