import { useSyncExternalStore } from "react";
import { createPortal } from "react-dom";
import { EntityCommentsWidget, type CommentTarget } from "@/components/entity-comments-widget";

/**
 * Bridges DivKit's {@code div-custom} block of type {@code onno-comments} to the React
 * {@link EntityCommentsWidget}. The server emits the block on every catalog/document detail
 * surface, carrying only the entity's {@code {kind, name, id}} triple as {@code custom_props.target};
 * the widget loads and posts the thread itself from {@code /api/comments/...}. Mirrors the form
 * bridge: a live custom element registers in a store, and {@link CommentsPortals} (mounted inside
 * the app's providers) portals the React panel into it.
 */

type Mount = { id: number; el: HTMLElement; target: CommentTarget };

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

class OnnoCommentsElement extends HTMLElement {
  private readonly _id = ++seq;
  private _target: CommentTarget | null = null;

  // DivKit assigns custom_props.target as a property (live object, not a string attribute).
  set target(value: CommentTarget | null) {
    this._target = value;
    this.sync();
  }
  get target(): CommentTarget | null {
    return this._target;
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
    if (!this.isConnected || !this._target) return;
    const existing = mounts.find((m) => m.el === this);
    if (existing) existing.target = this._target;
    else mounts = [...mounts, { id: this._id, el: this, target: this._target }];
    mounts = [...mounts];
    emit();
  }
}

let defined = false;
export function defineCommentsElement() {
  if (defined || typeof customElements === "undefined") return;
  if (!customElements.get("onno-comments")) {
    customElements.define("onno-comments", OnnoCommentsElement);
  }
  defined = true;
}
defineCommentsElement();

/** The DivKit {@code customComponents} entry: custom_type → element tag. */
export const COMMENTS_CUSTOM_COMPONENTS = new Map<string, { element: string }>([
  ["onno-comments", { element: "onno-comments" }],
]);

/** Portals each mounted {@code onno-comments} element to a React comments panel. */
export function CommentsPortals() {
  const live = useSyncExternalStore(subscribe, getSnapshot);
  return (
    <>
      {live.map((m) =>
        createPortal(<EntityCommentsWidget target={m.target} />, m.el, String(m.id))
      )}
    </>
  );
}
