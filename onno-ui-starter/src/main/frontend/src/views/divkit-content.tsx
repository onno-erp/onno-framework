import { forwardRef, useEffect, useImperativeHandle, useRef } from "react";
import {
  render,
  createGlobalVariablesController,
  createVariable,
} from "@divkitframework/divkit/client";
import { WIDGET_CUSTOM_COMPONENTS } from "@/lib/widget-bridge";
import { FORM_CUSTOM_COMPONENTS } from "@/lib/form-bridge";
import { LOGIN_FORM_CUSTOM_COMPONENTS } from "@/lib/login-form-bridge";
import { ICON_CUSTOM_COMPONENTS } from "@/lib/icon-bridge";
import { HINT_CUSTOM_COMPONENTS } from "@/lib/hint-bridge";
import { ACTIONS_MENU_CUSTOM_COMPONENTS } from "@/lib/actions-menu-bridge";
import { LIST_CUSTOM_COMPONENTS } from "@/lib/list-bridge";
import { CONSTANTS_CUSTOM_COMPONENTS } from "@/lib/constants-bridge";
import { ACTIONS_BAR_CUSTOM_COMPONENTS } from "@/lib/actions-bar-bridge";
import { COMMENTS_CUSTOM_COMPONENTS } from "@/lib/comments-bridge";
import { GEO_CUSTOM_COMPONENTS } from "@/lib/geo-bridge";

// All div-custom blocks the content can host: dashboard widgets, the entity form, the login
// form, icons, help hints, the detail-header overflow menu, the virtualized list grid, the
// settings editor, page-level action button sections, the detail-page comments thread, and the
// read-only map for a .widget("map") field.
const CUSTOM_COMPONENTS = new Map([
  ...WIDGET_CUSTOM_COMPONENTS,
  ...FORM_CUSTOM_COMPONENTS,
  ...LOGIN_FORM_CUSTOM_COMPONENTS,
  ...ICON_CUSTOM_COMPONENTS,
  ...HINT_CUSTOM_COMPONENTS,
  ...ACTIONS_MENU_CUSTOM_COMPONENTS,
  ...LIST_CUSTOM_COMPONENTS,
  ...CONSTANTS_CUSTOM_COMPONENTS,
  ...ACTIONS_BAR_CUSTOM_COMPONENTS,
  ...COMMENTS_CUSTOM_COMPONENTS,
  ...GEO_CUSTOM_COMPONENTS,
]);

type DivJson = Parameters<typeof render>[0]["json"];
type Instance = ReturnType<typeof render>;
type VarController = ReturnType<typeof createGlobalVariablesController>;
type Patch = Parameters<Instance["applyPatch"]>[0];
type Extensions = NonNullable<Parameters<typeof render>[0]["extensions"]>;

// A DivKit extension the list builder attaches to each clickable row (Components
// .tableItems → "row"). DivKit calls mountView with the rendered element, so we stamp
// the row's onno:// action url on the DOM. That single hook drives two host features
// the DivKit JSON can't express: a hover highlight (CSS on [data-onno-row]) and a
// right-click Open/Edit menu (divkit-view reads data-onno-row off the closest element).
class RowExtension {
  private readonly url: string;
  constructor(params: object) {
    const url = (params as { url?: unknown }).url;
    this.url = typeof url === "string" ? url : "";
  }
  mountView(node: HTMLElement) {
    if (this.url) node.dataset.onnoRow = this.url;
  }
  unmountView(node: HTMLElement) {
    delete node.dataset.onnoRow;
  }
}

const CONTENT_EXTENSIONS: Extensions = new Map([["row", RowExtension]]);

// The content endpoint envelope: a DivKit card plus an optional seed for the
// variables the card binds — numbers (e.g. the list count) or strings (form fields).
export type ContentCard = {
  templates?: unknown;
  card?: unknown;
  vars?: Record<string, number | string>;
} | null;

// A live update: a div-patch (replace the children of nodes by id) and/or new
// values for streamed variables.
export type Delta = {
  changes?: { id: string; items: unknown[] }[];
  vars?: Record<string, number>;
};

export type ContentHandle = {
  // Applies a live delta in place. Returns false when the row div-patch could not be
  // cleanly applied (target missing/broken) so the caller can fall back to a full
  // re-render instead of leaving the surface wiped.
  applyDelta: (delta: Delta) => boolean;
};

// An action emitted by a content card (an onno:// url the host routes).
export type ContentAction = { url?: string };

/**
 * Renders the per-route content card through DivKit's core instance (the React
 * wrapper destroys + recreates on every prop change and hides the instance). One
 * instance lives per surface identity ({@code surfaceKey} = route/theme/viewport);
 * a fresh full card (navigation / initial load) remounts it, but live updates go
 * through {@link ContentHandle#applyDelta} — the DivKit-native {@code applyPatch}
 * for changed regions and variable {@code setValue} for streamed values — so they
 * apply in place with no remount, flash, or scroll loss.
 */
/**
 * A wide table is a horizontal DivKit gallery (so it scrolls); its rows size to
 * content, so when the table is narrower than the island they don't reach the right
 * edge. DivKit can't express "fill the parent, but overflow-and-scroll when wider"
 * and exposes no DOM hook, so we set it here: the row stack and each row get
 * {@code min-width: 100%}. Percent keeps it resize-reactive — when content is wider
 * the floor is ignored and the gallery scrolls as before.
 */
function stretchTables(host: HTMLElement): void {
  host.querySelectorAll<HTMLElement>("*").forEach((el) => {
    const ov = getComputedStyle(el).overflowX;
    if (ov !== "auto" && ov !== "scroll") return; // the gallery scroller
    // The rows live in the flex-column with the most flex-row children (header + data
    // rows); shallower wrappers have only one, so pick the richest one.
    let stack: HTMLElement | null = null;
    let bestRows = 0;
    el.querySelectorAll<HTMLElement>("*").forEach((node) => {
      const cs = getComputedStyle(node);
      if (cs.display !== "flex" || cs.flexDirection !== "column") return;
      const rowKids = [...node.children].filter(
        (c) => getComputedStyle(c as HTMLElement).flexDirection === "row"
      ).length;
      if (rowKids > bestRows) {
        bestRows = rowKids;
        stack = node;
      }
    });
    // Cast: `stack` is only ever assigned inside the forEach callback above, which
    // TS doesn't track, so it narrows the binding to `null` without this.
    const rowsStack = stack as HTMLElement | null;
    if (!rowsStack) return;
    // width:max-content lets the stack grow past the gallery (so wide tables scroll
    // and every row spans the full content width); min-width:100% floors it at the
    // gallery width so a narrow table fills the island. Rows inherit the same floor.
    rowsStack.style.width = "max-content";
    rowsStack.style.minWidth = "100%";
    for (const child of rowsStack.children) (child as HTMLElement).style.minWidth = "100%";
  });
}

export const DivKitContent = forwardRef<ContentHandle, {
  surfaceKey: string;
  card: ContentCard;
  theme: "light" | "dark";
  onAction: (action: ContentAction) => void;
}>(function DivKitContent({ surfaceKey, card, theme, onAction }, ref) {
  const hostRef = useRef<HTMLDivElement | null>(null);
  const instanceRef = useRef<Instance | null>(null);
  const varsRef = useRef<VarController | null>(null);
  const actionRef = useRef(onAction);
  actionRef.current = onAction;

  useImperativeHandle(ref, () => ({
    applyDelta(delta) {
      const ctrl = varsRef.current;
      if (delta.vars && ctrl) {
        for (const [name, value] of Object.entries(delta.vars)) {
          const existing = ctrl.getVariable(name) as { setValue(v: bigint): void } | undefined;
          if (existing) existing.setValue(BigInt(value));
          else ctrl.setVariable(createVariable(name, "integer", BigInt(value)));
        }
      }
      if (delta.changes?.length) {
        if (!instanceRef.current) return false;
        // Transactional mode: DivKit rejects the whole patch (returns false) if any
        // target id is missing or the replacement is shape-broken, rather than
        // partially applying it and wiping the rows. The caller re-renders on false.
        const ok = instanceRef.current.applyPatch(
          { patch: { mode: "transactional", changes: delta.changes } } as Patch);
        // Patched rows are fresh DOM — re-stretch them to fill the table width.
        if (ok && hostRef.current) stretchTables(hostRef.current);
        return ok;
      }
      return true;
    },
  }), []);

  // Mount a fresh instance when the surface (route/theme) changes and a card is
  // present. Live updates never change `card` (they use applyDelta), so the
  // instance persists and is patched in place.
  useEffect(() => {
    const host = hostRef.current;
    if (!host || !card) return;
    const ctrl = createGlobalVariablesController();
    for (const [name, value] of Object.entries(card.vars ?? {})) {
      if (typeof value === "number") {
        ctrl.setVariable(createVariable(name, "integer", BigInt(value)));
      } else {
        ctrl.setVariable(createVariable(name, "string", String(value)));
      }
    }
    varsRef.current = ctrl;
    const inst = render({
      target: host,
      id: `content:${surfaceKey}`,
      json: { templates: card.templates ?? {}, card: card.card } as DivJson,
      theme,
      // "touch" so horizontal galleries (wide tables) scroll by wheel/trackpad with no
      // desktop prev/next arrow buttons overlaid on the table.
      platform: "touch",
      globalVariablesController: ctrl,
      // Bridge div-custom blocks (charts, calendars, kanban, entity form) to React.
      customComponents: CUSTOM_COMPONENTS,
      extensions: CONTENT_EXTENSIONS,
      onCustomAction: (action) => actionRef.current(action as ContentAction),
    });
    instanceRef.current = inst;
    // Re-stretch tables whenever the host lays out or the island is resized. Setting
    // the same min-width/width is idempotent, so this settles after one pass.
    stretchTables(host);
    const ro = new ResizeObserver(() => stretchTables(host));
    ro.observe(host);
    return () => {
      ro.disconnect();
      inst.$destroy();
      instanceRef.current = null;
      varsRef.current = null;
    };
  }, [surfaceKey, theme, card]);

  return <div ref={hostRef} className="onno-surface" />;
});
