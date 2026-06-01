import { forwardRef, useEffect, useImperativeHandle, useRef } from "react";
import {
  render,
  createGlobalVariablesController,
  createVariable,
} from "@divkitframework/divkit/client";
import { WIDGET_CUSTOM_COMPONENTS } from "@/lib/widget-bridge";

type DivJson = Parameters<typeof render>[0]["json"];
type Instance = ReturnType<typeof render>;
type VarController = ReturnType<typeof createGlobalVariablesController>;
type Patch = Parameters<Instance["applyPatch"]>[0];

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
  applyDelta: (delta: Delta) => void;
  // Current values of the form's f_<field> variables, keyed by field name.
  readForm: () => Record<string, string>;
};

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
  onAction: (action: { url?: string }) => void;
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
      if (delta.changes?.length && instanceRef.current) {
        instanceRef.current.applyPatch({ patch: { changes: delta.changes } } as Patch);
        // Patched rows are fresh DOM — re-stretch them to fill the table width.
        if (hostRef.current) stretchTables(hostRef.current);
      }
    },
    readForm() {
      const ctrl = varsRef.current;
      const out: Record<string, string> = {};
      if (!ctrl) return out;
      for (const v of ctrl.list()) {
        const name = v.getName();
        if (name.startsWith("f_")) {
          out[name.slice(2)] = String((v as { getValue(): unknown }).getValue() ?? "");
        }
      }
      return out;
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
      // Bridge div-custom blocks (charts, calendars, kanban) to their React widgets.
      customComponents: WIDGET_CUSTOM_COMPONENTS,
      onCustomAction: (action) => actionRef.current(action as { url?: string }),
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

  return <div ref={hostRef} />;
});
