import { useSyncExternalStore } from "react";
import { createPortal } from "react-dom";
import { HintIcon } from "@/components/ui/hint-icon";
import { IslandErrorBoundary } from "@/lib/island-error-boundary";

/**
 * Bridges DivKit's {@code div-custom} blocks of type {@code onno-hint} to a hoverable "?" help
 * glyph. Raw-DivKit surfaces (the read-only detail view, native count/metric dashboard cards) emit
 * an {@code onno-hint} node carrying {@code text/color/size}; DivKit instantiates the {@link
 * OnnoHintElement} custom element and assigns those as properties, and {@link HintPortals} (mounted
 * high in the tree) renders a {@link HintIcon} into each. React-island surfaces (form, list,
 * widgets) use {@link HintIcon} directly and don't go through this bridge.
 *
 * <p>This mirrors {@code icon-bridge} exactly — the same store/portal pattern DivKit needs to host
 * a React component inside a custom block.</p>
 */

type Mount = {
  id: number;
  el: HTMLElement;
  text: string;
  color?: string;
  size?: number;
};

// A new array reference is published on every change so useSyncExternalStore sees it.
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

class OnnoHintElement extends HTMLElement {
  private readonly _id = ++seq;
  private _text = "";
  private _color: string | undefined;
  private _size: number | undefined;

  // DivKit assigns each custom_props key as a property on the element.
  set text(value: string) {
    this._text = value ?? "";
    this.sync();
  }
  get text(): string {
    return this._text;
  }
  set color(value: string | undefined) {
    this._color = value;
    this.sync();
  }
  get color(): string | undefined {
    return this._color;
  }
  set size(value: number | string | undefined) {
    this._size = typeof value === "number" ? value : value ? Number(value) : undefined;
    this.sync();
  }
  get size(): number | undefined {
    return this._size;
  }

  connectedCallback() {
    this.sync();
  }

  disconnectedCallback() {
    if (mounts.some((m) => m.el === this)) {
      mounts = mounts.filter((m) => m.el !== this);
      emit();
    }
  }

  private sync() {
    if (!this.isConnected || !this._text) return;
    const existing = mounts.find((m) => m.el === this);
    if (existing) {
      existing.text = this._text;
      existing.color = this._color;
      existing.size = this._size;
    } else {
      mounts = [
        ...mounts,
        { id: this._id, el: this, text: this._text, color: this._color, size: this._size },
      ];
    }
    mounts = [...mounts];
    emit();
  }
}

let defined = false;

/** Register the custom element once, before any DivKit content renders. */
export function defineHintElement() {
  if (defined || typeof customElements === "undefined") return;
  if (!customElements.get("onno-hint")) {
    customElements.define("onno-hint", OnnoHintElement);
  }
  defined = true;
}

defineHintElement();

/** The DivKit {@code customComponents} map: custom_type → element tag. */
export const HINT_CUSTOM_COMPONENTS = new Map<string, { element: string }>([
  ["onno-hint", { element: "onno-hint" }],
]);

/**
 * Portals every live {@code <onno-hint>} to its {@link HintIcon}. Mount once, high in the tree
 * (alongside {@code IconPortals}).
 */
export function HintPortals() {
  const list = useSyncExternalStore(subscribe, getSnapshot);
  return (
    <>
      {list.map((m) =>
        createPortal(
          <IslandErrorBoundary label="hint">
            <HintIcon text={m.text} color={m.color} size={m.size} />
          </IslandErrorBoundary>,
          m.el,
          String(m.id)
        )
      )}
    </>
  );
}
