import { useState, useSyncExternalStore } from "react";
import { createPortal } from "react-dom";
import {
  CircleCheck,
  Ellipsis,
  Pencil,
  RotateCcw,
  Trash2,
  type LucideIcon,
} from "lucide-react";
import { Popover, PopoverContent, PopoverTrigger } from "@/components/ui/popover";
import { cn } from "@/lib/utils";

/**
 * Bridges DivKit's {@code div-custom} block of type {@code onec-actions-menu} to a
 * React overflow (⋯) dropdown. The server emits the menu-placed detail-header actions
 * as plain items ({@code label / icon / url / danger}); this renders a kebab trigger +
 * dropdown and dispatches each item's {@code onec://} url through the same host event
 * the inline buttons use ({@code onec:action}). Mirrors the form/icon bridges: a live
 * custom element registers in a store, and {@link ActionsMenuPortals} (mounted inside
 * the app's Router/providers) portals the React menu into it.
 */

type MenuItem = { label: string; icon?: string; url: string; danger?: boolean };
type Mount = { id: number; el: HTMLElement; items: MenuItem[] };

let mounts: Mount[] = [];
const listeners = new Set<() => void>();
let seq = 0;

function emit() {
  for (const l of listeners) l();
}
function subscribe(listener: () => void): () => void {
  listeners.add(listener);
  return () => listeners.delete(listener);
}
function getSnapshot(): Mount[] {
  return mounts;
}

class OnecActionsMenuElement extends HTMLElement {
  private readonly _id = ++seq;
  private _items: MenuItem[] = [];

  // DivKit assigns custom_props.items as a property (live array, not a string attribute).
  set items(value: MenuItem[] | null) {
    this._items = Array.isArray(value) ? value : [];
    this.sync();
  }
  get items(): MenuItem[] {
    return this._items;
  }

  connectedCallback() {
    this.applyHostStyle();
    this.sync();
  }
  disconnectedCallback() {
    if (mounts.some((m) => m.el === this)) {
      mounts = mounts.filter((m) => m.el !== this);
      emit();
    }
  }

  // DivKit renders a custom block with no action as a collapsed, inline host with
  // pointer-events:none (so clicks fall through to parents). That swallows the menu's
  // clicks, so re-assert a proper interactive box. !important + re-applying on every
  // sync() keeps it from being clobbered after DivKit configures the element.
  private applyHostStyle() {
    this.style.setProperty("display", "inline-flex", "important");
    this.style.setProperty("width", "38px", "important");
    this.style.setProperty("height", "34px", "important");
    this.style.setProperty("pointer-events", "auto", "important");
  }

  private sync() {
    if (!this.isConnected) return;
    this.applyHostStyle();
    const existing = mounts.find((m) => m.el === this);
    if (existing) existing.items = this._items;
    else mounts = [...mounts, { id: this._id, el: this, items: this._items }];
    mounts = [...mounts];
    emit();
  }
}

let defined = false;
export function defineActionsMenuElement() {
  if (defined || typeof customElements === "undefined") return;
  if (!customElements.get("onec-actions-menu")) {
    customElements.define("onec-actions-menu", OnecActionsMenuElement);
  }
  defined = true;
}
defineActionsMenuElement();

/** The DivKit {@code customComponents} entry: custom_type → element tag. */
export const ACTIONS_MENU_CUSTOM_COMPONENTS = new Map<string, { element: string }>([
  ["onec-actions-menu", { element: "onec-actions-menu" }],
]);

// The handful of header-action glyphs (kebab-case lucide names the server sends).
const ICONS: Record<string, LucideIcon> = {
  "circle-check": CircleCheck,
  "rotate-ccw": RotateCcw,
  pencil: Pencil,
  "trash-2": Trash2,
};

function fire(url: string) {
  window.dispatchEvent(new CustomEvent("onec:action", { detail: url }));
}

function ActionsMenu({ items }: { items: MenuItem[] }) {
  const [open, setOpen] = useState(false);
  if (items.length === 0) return null;
  return (
    <Popover open={open} onOpenChange={setOpen}>
      <PopoverTrigger asChild>
        <button
          type="button"
          aria-label="More actions"
          className="inline-flex h-full w-full items-center justify-center rounded-lg border border-border bg-secondary text-foreground transition-colors hover:bg-accent"
        >
          <Ellipsis className="size-4" aria-hidden="true" />
        </button>
      </PopoverTrigger>
      <PopoverContent align="end" className="w-48 p-1">
        {items.map((it) => {
          const Icon = it.icon ? ICONS[it.icon] : undefined;
          return (
            <button
              key={it.url}
              type="button"
              onClick={() => {
                setOpen(false);
                fire(it.url);
              }}
              className={cn(
                "flex w-full items-center gap-2 rounded-md px-2.5 py-1.5 text-left text-sm transition-colors hover:bg-accent",
                it.danger ? "text-destructive hover:text-destructive" : "text-foreground"
              )}
            >
              {Icon ? <Icon className="size-4" aria-hidden="true" /> : null}
              {it.label}
            </button>
          );
        })}
      </PopoverContent>
    </Popover>
  );
}

/** Portals every live {@code <onec-actions-menu>} to its React dropdown. Mount once, inside the Router. */
export function ActionsMenuPortals() {
  const list = useSyncExternalStore(subscribe, getSnapshot);
  return (
    <>
      {list.map((m) => createPortal(<ActionsMenu items={m.items} />, m.el, String(m.id)))}
    </>
  );
}
