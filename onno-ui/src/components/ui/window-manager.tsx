import {
  createContext,
  useCallback,
  useContext,
  useEffect,
  useId,
  useLayoutEffect,
  useRef,
  useState,
  useSyncExternalStore,
  type KeyboardEvent as ReactKeyboardEvent,
  type PointerEvent as ReactPointerEvent,
  type ReactNode,
} from "react";
import { createPortal } from "react-dom";
import { ArrowDownRight, Maximize2, Minimize2, Minus, PictureInPicture2, X } from "lucide-react";
import { cn } from "../../lib/utils";
import { useMessages } from "../../providers/messages-provider";
import { Button } from "./button";

/**
 * Non-modal application windows: the Gmail-style compose box docked in the corner, a floating
 * panel the user drags and resizes while working with the rest of the app, minimised to a title
 * bar or maximised over the page.
 *
 * Unlike `DialogShell` a window never blocks the page and never traps focus. Windows live in one
 * shell-level layer (above the page, below popovers and modal dialogs), so they keep their place
 * and size while the user navigates, and every window's position is known to the others (docked
 * and minimised windows line up along the bottom edge).
 *
 * Two ways to open one:
 *
 * - `<FloatingWindow open title="…">…</FloatingWindow>` — owned by a component. Its content is
 *   ordinary children (same React context and state as the owner); it closes when the owner
 *   unmounts.
 * - `windows.open(id, { title, content })` — owned by the app. It stays open across navigation
 *   until closed; `content` renders inside the shell, with the app's providers.
 */

export type WindowState = "floating" | "docked" | "minimized" | "maximized";

/** How a window looks and behaves; shared by `<FloatingWindow>` and `windows.open`. */
export interface WindowSpec {
  /** Title bar text; also the window's accessible name. */
  title: ReactNode;
  /** A small glyph before the title. */
  icon?: ReactNode;
  /** Where it opens (default `"floating"`). A persisted window reopens as the user left it. */
  initialState?: Exclude<WindowState, "minimized">;
  /** Size of a floating or docked window, in px (default 560 × 520). */
  width?: number;
  height?: number;
  /** The smallest a floating window may be resized to (default 320 × 200). */
  minWidth?: number;
  minHeight?: number;
  /** Top-left of a floating window when it first opens (default: centred, cascaded). */
  x?: number;
  y?: number;
  /** Drag the edges to resize a floating window (default true). */
  resizable?: boolean;
  /** Offer minimise / dock-to-corner / maximise in the title bar (each default true). */
  minimizable?: boolean;
  dockable?: boolean;
  maximizable?: boolean;
  /** Show the close button (default true). */
  closable?: boolean;
  /**
   * Remember the window's state, position and size under this key (in this browser), so it
   * reopens where the user left it. Omit for a window that always opens fresh.
   */
  persist?: string;
  /** Extra controls in the title bar, before the window buttons. */
  headerActions?: ReactNode;
  /** A footer pinned under the content (typically the window's primary actions). */
  footer?: ReactNode;
  /** Pad the content area (default true). Turn off for content that lays itself out edge to edge. */
  padded?: boolean;
  /** Classes for the content area. */
  bodyClassName?: string;
  /** Marks the window's root element, for tests and styling. */
  "data-testid"?: string;
}

/** What content inside a window can do to it (`useWindow()`). */
export interface WindowControls {
  id: string;
  state: WindowState;
  setState: (state: WindowState) => void;
  close: () => void;
  focus: () => void;
}

/** One open window, as `useOpenWindows()` lists them. */
export interface WindowInfo {
  id: string;
  state: WindowState;
}

/** `windows.open` options: the spec plus the content and a close guard. */
export interface WindowOptions extends WindowSpec {
  content: ReactNode | ((window: WindowControls) => ReactNode);
  /** Runs before the window closes from its close button; return `false` to keep it open. */
  onClose?: () => boolean | void;
}

export interface WindowHandle {
  readonly id: string;
  close(): void;
  focus(): void;
  setState(state: WindowState): void;
  update(patch: Partial<WindowOptions>): void;
}

type Geometry = { x: number; y: number; width: number; height: number };

type Entry = {
  id: string;
  state: WindowState;
  /** The state minimise/maximise returns to. */
  restore: "floating" | "docked";
  /** Floating position and size; null until first placed. */
  geometry: Geometry | null;
  z: number;
  openedAt: number;
  persist?: string;
  /** Set for app-owned windows (`windows.open`); component-owned ones render themselves. */
  options?: WindowOptions;
};

const DEFAULT_WIDTH = 560;
const DEFAULT_HEIGHT = 520;
const DEFAULT_MIN_WIDTH = 320;
const DEFAULT_MIN_HEIGHT = 200;
const TITLE_BAR = 44;
const MINIMIZED_WIDTH = 280;
/** The most of the screen height a docked window takes. */
const DOCKED_MAX_SHARE = 0.7;
const EDGE = 16;
const GAP = 12;
const STORAGE_PREFIX = "onno.window.";

// ---- store ------------------------------------------------------------------------------------

let entries: Entry[] = [];
let zCounter = 1;
let openCounter = 0;
let layerElement: HTMLElement | null = null;
const listeners = new Set<() => void>();

function emit() {
  for (const listener of listeners) listener();
}
function subscribe(listener: () => void) {
  listeners.add(listener);
  return () => { listeners.delete(listener); };
}
function snapshot() {
  return entries;
}
function find(id: string) {
  return entries.find(entry => entry.id === id);
}
function patch(id: string, change: (entry: Entry) => Partial<Entry>) {
  let changed = false;
  entries = entries.map(entry => {
    if (entry.id !== id) return entry;
    changed = true;
    return { ...entry, ...change(entry) };
  });
  if (changed) emit();
}

type Saved = { state?: WindowState; restore?: "floating" | "docked"; geometry?: Geometry | null };

function readSaved(key: string | undefined): Saved | null {
  if (!key) return null;
  try {
    const raw = window.localStorage.getItem(STORAGE_PREFIX + key);
    return raw ? JSON.parse(raw) as Saved : null;
  } catch {
    return null;
  }
}
function save(entry: Entry | undefined) {
  if (!entry?.persist) return;
  try {
    window.localStorage.setItem(STORAGE_PREFIX + entry.persist,
      JSON.stringify({ state: entry.state, restore: entry.restore, geometry: entry.geometry } satisfies Saved));
  } catch {
    // Private mode or full storage: the window simply opens fresh next time.
  }
}

function register(id: string, spec: WindowSpec, options?: WindowOptions) {
  const existing = find(id);
  if (existing) {
    patch(id, () => ({ options, z: ++zCounter }));
    return;
  }
  const saved = readSaved(spec.persist);
  const initial = spec.initialState ?? "floating";
  const state = saved?.state && saved.state !== "minimized" ? saved.state : initial;
  const restore = saved?.restore ?? (initial === "docked" ? "docked" : "floating");
  entries = [...entries, {
    id, state, restore, geometry: saved?.geometry ?? null, z: ++zCounter, openedAt: ++openCounter,
    persist: spec.persist, options,
  }];
  emit();
}

function unregister(id: string) {
  if (!find(id)) return;
  entries = entries.filter(entry => entry.id !== id);
  emit();
}

function setWindowState(id: string, state: WindowState) {
  patch(id, entry => ({
    state,
    restore: state === "floating" || state === "docked" ? state : entry.restore,
    z: ++zCounter,
  }));
  save(find(id));
}

function focusWindow(id: string) {
  const entry = find(id);
  if (!entry || entry.z === zCounter) return;
  patch(id, () => ({ z: ++zCounter }));
}

/**
 * Open and control app-owned windows from anywhere — a command result, a list row, a page
 * button. Opening an id that is already open brings that window forward (and replaces its
 * content), so `windows.open("compose:" + id, …)` never stacks duplicates.
 */
export const windows = {
  open(id: string, options: WindowOptions): WindowHandle {
    register(id, options, options);
    const entry = find(id);
    if (entry?.state === "minimized") setWindowState(id, entry.restore);
    return handle(id);
  },
  close(id: string) {
    unregister(id);
  },
  focus(id: string) {
    const entry = find(id);
    if (entry?.state === "minimized") setWindowState(id, entry.restore);
    else focusWindow(id);
  },
  setState(id: string, state: WindowState) {
    setWindowState(id, state);
  },
  update(id: string, change: Partial<WindowOptions>) {
    patch(id, entry => entry.options ? { options: { ...entry.options, ...change } } : {});
  },
  isOpen(id: string) {
    return !!find(id);
  },
  list(): WindowInfo[] {
    return entries.map(({ id, state }) => ({ id, state }));
  },
};

function handle(id: string): WindowHandle {
  return {
    id,
    close: () => windows.close(id),
    focus: () => windows.focus(id),
    setState: state => windows.setState(id, state),
    update: change => windows.update(id, change),
  };
}

/** Every open window and its state, live — for a task bar or a "N drafts open" badge. */
export function useOpenWindows(): readonly WindowInfo[] {
  const list = useSyncExternalStore(subscribe, snapshot, snapshot);
  return list.map(({ id, state }) => ({ id, state }));
}

const WindowContext = createContext<WindowControls | null>(null);

/** Inside a window's content: its state and controls. Throws outside a window. */
export function useWindow(): WindowControls {
  const controls = useContext(WindowContext);
  if (!controls) throw new Error("useWindow() must be called inside a window");
  return controls;
}

// ---- layout -----------------------------------------------------------------------------------

const viewportListeners = new Set<() => void>();
let viewport = { width: 1280, height: 800 };
function readViewport() {
  if (typeof window === "undefined") return viewport;
  const next = { width: window.innerWidth, height: window.innerHeight };
  if (next.width !== viewport.width || next.height !== viewport.height) viewport = next;
  return viewport;
}
function subscribeViewport(listener: () => void) {
  viewportListeners.add(listener);
  const onResize = () => { for (const each of viewportListeners) each(); };
  if (viewportListeners.size === 1) window.addEventListener("resize", onResize);
  return () => {
    viewportListeners.delete(listener);
    if (!viewportListeners.size) window.removeEventListener("resize", onResize);
  };
}
function useViewport() {
  return useSyncExternalStore(subscribeViewport, readViewport, readViewport);
}

const PHONE_MAX_WIDTH = 640;

function clamp(value: number, min: number, max: number) {
  return Math.min(Math.max(value, min), Math.max(min, max));
}

/** Keeps a floating window's title bar reachable on the current screen. */
function fit(geometry: Geometry, view: { width: number; height: number }, min: { width: number; height: number }): Geometry {
  const width = clamp(geometry.width, Math.min(min.width, view.width), view.width);
  const height = clamp(geometry.height, Math.min(min.height, view.height), view.height);
  return {
    width,
    height,
    x: clamp(geometry.x, 0, view.width - width),
    y: clamp(geometry.y, 0, view.height - TITLE_BAR),
  };
}

type Box = { left: number; top: number; width: number; height: number };

/** Where a window sits on screen, from its state and the windows sharing the bottom row. */
function layout(entry: Entry, spec: WindowSpec, all: Entry[], view: { width: number; height: number }): Box {
  const phone = view.width < PHONE_MAX_WIDTH;
  const height = spec.height ?? DEFAULT_HEIGHT;

  if (entry.state === "maximized" || (phone && entry.state !== "minimized")) {
    const inset = phone ? 0 : GAP;
    return { left: inset, top: inset, width: view.width - inset * 2, height: view.height - inset * 2 };
  }

  if (entry.state === "docked" || entry.state === "minimized") {
    // The bottom row, right to left in opening order, like Gmail's compose windows.
    const row = all.filter(each => each.state === "docked" || each.state === "minimized")
      .sort((a, b) => a.openedAt - b.openedAt);
    let right = phone ? 0 : EDGE;
    for (const each of row) {
      const eachWidth = phone ? view.width : each.state === "minimized"
        ? MINIMIZED_WIDTH : Math.min(widthOf(each, spec, entry), view.width - EDGE * 2);
      if (each.id === entry.id) {
        // A docked window leaves most of a short screen to the page it sits beside.
        const boxHeight = entry.state === "minimized" ? TITLE_BAR
          : Math.min(height, Math.round(view.height * DOCKED_MAX_SHARE), view.height - EDGE * 2);
        const bottom = phone ? row.filter(e => e.state === "minimized").indexOf(entry) * TITLE_BAR : 0;
        return {
          left: Math.max(phone ? 0 : GAP, view.width - right - eachWidth),
          top: view.height - boxHeight - bottom,
          width: eachWidth,
          height: boxHeight,
        };
      }
      if (!phone) right += eachWidth + GAP;
    }
  }

  const min = { width: spec.minWidth ?? DEFAULT_MIN_WIDTH, height: spec.minHeight ?? DEFAULT_MIN_HEIGHT };
  const geometry = entry.geometry ?? initialGeometry(entry, spec, all, view);
  const fitted = fit(geometry, view, min);
  return { left: fitted.x, top: fitted.y, width: fitted.width, height: fitted.height };
}

// Docked windows of other owners keep their own requested width; the spec at hand only knows its
// own, so the store remembers each docked window's width when it renders.
const dockedWidths = new Map<string, number>();
function widthOf(each: Entry, spec: WindowSpec, current: Entry) {
  if (each.id === current.id) return spec.width ?? DEFAULT_WIDTH;
  return dockedWidths.get(each.id) ?? DEFAULT_WIDTH;
}

function initialGeometry(entry: Entry, spec: WindowSpec, all: Entry[], view: { width: number; height: number }): Geometry {
  const width = Math.min(spec.width ?? DEFAULT_WIDTH, view.width - EDGE * 2);
  const height = Math.min(spec.height ?? DEFAULT_HEIGHT, view.height - EDGE * 2);
  const cascade = all.filter(each => each.state === "floating" && each.openedAt < entry.openedAt).length % 6;
  return {
    width,
    height,
    x: spec.x ?? Math.round((view.width - width) / 2) + cascade * 28,
    y: spec.y ?? Math.max(EDGE, Math.round((view.height - height) / 3)) + cascade * 28,
  };
}

// ---- frame ------------------------------------------------------------------------------------

type Edge = "n" | "s" | "e" | "w" | "ne" | "nw" | "se" | "sw";
const EDGES: { edge: Edge; className: string }[] = [
  { edge: "n", className: "inset-x-2 -top-1 h-2 cursor-ns-resize" },
  { edge: "s", className: "inset-x-2 -bottom-1 h-2 cursor-ns-resize" },
  { edge: "e", className: "inset-y-2 -right-1 w-2 cursor-ew-resize" },
  { edge: "w", className: "inset-y-2 -left-1 w-2 cursor-ew-resize" },
  { edge: "ne", className: "-right-1 -top-1 size-3 cursor-nesw-resize" },
  { edge: "sw", className: "-bottom-1 -left-1 size-3 cursor-nesw-resize" },
  { edge: "nw", className: "-left-1 -top-1 size-3 cursor-nwse-resize" },
  { edge: "se", className: "-bottom-1 -right-1 size-3 cursor-nwse-resize" },
];

function WindowFrame({ id, spec, onClose, children }: {
  id: string;
  spec: WindowSpec;
  onClose: () => void;
  children: ReactNode;
}) {
  const t = useMessages();
  const titleId = useId();
  const all = useSyncExternalStore(subscribe, snapshot, snapshot);
  const view = useViewport();
  const entry = all.find(each => each.id === id);
  const frameRef = useRef<HTMLElement | null>(null);

  const width = spec.width ?? DEFAULT_WIDTH;
  useLayoutEffect(() => {
    dockedWidths.set(id, width);
    return () => { dockedWidths.delete(id); };
  }, [id, width]);

  // A new window takes focus unless its content already moved it inside.
  useEffect(() => {
    const frame = requestAnimationFrame(() => {
      const root = frameRef.current;
      if (root && !root.contains(document.activeElement)) root.focus({ preventScroll: true });
    });
    return () => cancelAnimationFrame(frame);
  }, []);

  const box = entry ? layout(entry, spec, all, view) : null;
  const controls: WindowControls = {
    id,
    state: entry?.state ?? "floating",
    setState: state => setWindowState(id, state),
    close: onClose,
    focus: () => windows.focus(id),
  };

  const drag = useRef<{ edge: Edge | "move"; pointerX: number; pointerY: number; start: Geometry } | null>(null);
  const min = { width: spec.minWidth ?? DEFAULT_MIN_WIDTH, height: spec.minHeight ?? DEFAULT_MIN_HEIGHT };

  const beginDrag = (edge: Edge | "move") => (event: ReactPointerEvent<HTMLElement>) => {
    if (!entry || !box || event.button !== 0 || view.width < PHONE_MAX_WIDTH) return;
    if (edge === "move" && (event.target as HTMLElement).closest("button, a, input, select, textarea, [role='combobox']")) return;
    if (entry.state === "maximized" || entry.state === "minimized") return;
    event.preventDefault();
    const start = { x: box.left, y: box.top, width: box.width, height: box.height };
    // Dragging a docked window lifts it out of the corner where it is.
    if (entry.state === "docked") patch(id, () => ({ state: "floating", restore: "floating", geometry: start }));
    drag.current = { edge, pointerX: event.clientX, pointerY: event.clientY, start };
    event.currentTarget.setPointerCapture?.(event.pointerId);
  };
  const moveDrag = (event: ReactPointerEvent<HTMLElement>) => {
    const current = drag.current;
    if (!current) return;
    const dx = event.clientX - current.pointerX;
    const dy = event.clientY - current.pointerY;
    const { start, edge } = current;
    let next: Geometry = { ...start };
    if (edge === "move") next = { ...start, x: start.x + dx, y: start.y + dy };
    else {
      if (edge.includes("e")) next.width = Math.max(min.width, start.width + dx);
      if (edge.includes("s")) next.height = Math.max(min.height, start.height + dy);
      if (edge.includes("w")) {
        next.width = Math.max(min.width, start.width - dx);
        next.x = start.x + start.width - next.width;
      }
      if (edge.includes("n")) {
        next.height = Math.max(min.height, start.height - dy);
        next.y = start.y + start.height - next.height;
      }
    }
    patch(id, () => ({ geometry: fit(next, view, min) }));
  };
  const endDrag = (event: ReactPointerEvent<HTMLElement>) => {
    if (!drag.current) return;
    drag.current = null;
    event.currentTarget.releasePointerCapture?.(event.pointerId);
    save(find(id));
  };

  if (!entry || !box) return null;
  const { state } = entry;
  const phone = view.width < PHONE_MAX_WIDTH;
  const minimized = state === "minimized";
  const resizable = spec.resizable !== false && state === "floating" && !phone;
  const toggleMaximize = () => setWindowState(id, state === "maximized" ? entry.restore : "maximized");
  const onKeyDown = (event: ReactKeyboardEvent) => {
    // Escape belongs to whatever is inside (a menu, a picker); it never leaks to the page below.
    if (event.key === "Escape") event.stopPropagation();
  };

  return (
    <WindowContext.Provider value={controls}>
      <section
        ref={frameRef}
        role="dialog"
        aria-modal="false"
        aria-labelledby={titleId}
        tabIndex={-1}
        data-onno-window=""
        data-window-id={id}
        data-window-state={state}
        data-testid={spec["data-testid"]}
        onPointerDownCapture={() => focusWindow(id)}
        onFocusCapture={() => focusWindow(id)}
        onKeyDown={onKeyDown}
        style={{ left: box.left, top: box.top, width: box.width, height: box.height, zIndex: entry.z }}
        className={cn(
          "pointer-events-auto fixed flex flex-col overflow-hidden border border-border bg-card text-card-foreground shadow-2xl outline-none",
          phone && !minimized ? "rounded-none border-0" : state === "docked" || minimized ? "rounded-t-panel rounded-b-none" : "rounded-panel",
        )}
      >
        <header
          onPointerDown={beginDrag("move")}
          onPointerMove={moveDrag}
          onPointerUp={endDrag}
          onPointerCancel={endDrag}
          onDoubleClick={event => {
            if (spec.maximizable === false || phone || minimized) return;
            if ((event.target as HTMLElement).closest("button")) return;
            toggleMaximize();
          }}
          onClick={event => {
            if (minimized && !(event.target as HTMLElement).closest("button")) setWindowState(id, entry.restore);
          }}
          className={cn(
            "flex h-11 shrink-0 select-none items-center gap-2 border-b border-border bg-muted/40 pl-3 pr-1.5",
            state === "floating" && !phone && "cursor-grab active:cursor-grabbing",
            minimized && "cursor-pointer border-b-0",
          )}
        >
          {spec.icon ? <span className="flex size-5 shrink-0 items-center justify-center text-muted-foreground">{spec.icon}</span> : null}
          <h2 id={titleId} className="min-w-0 flex-1 truncate text-sm font-semibold">{spec.title}</h2>
          {!minimized && spec.headerActions}
          <div className="flex shrink-0 items-center">
            {spec.minimizable !== false ? (
              <WindowButton label={minimized ? t("window.restore") : t("window.minimize")}
                onClick={() => setWindowState(id, minimized ? entry.restore : "minimized")}>
                {minimized ? <Maximize2 className="size-3.5" /> : <Minus className="size-4" />}
              </WindowButton>
            ) : null}
            {spec.dockable !== false && !phone && !minimized && state !== "maximized" ? (
              <WindowButton label={state === "docked" ? t("window.float") : t("window.dock")}
                onClick={() => setWindowState(id, state === "docked" ? "floating" : "docked")}>
                {state === "docked" ? <PictureInPicture2 className="size-3.5" /> : <ArrowDownRight className="size-4" />}
              </WindowButton>
            ) : null}
            {spec.maximizable !== false && !phone && !minimized ? (
              <WindowButton label={state === "maximized" ? t("window.restore") : t("window.maximize")} onClick={toggleMaximize}>
                {state === "maximized" ? <Minimize2 className="size-3.5" /> : <Maximize2 className="size-3.5" />}
              </WindowButton>
            ) : null}
            {spec.closable !== false ? (
              <WindowButton label={t("action.close")} onClick={onClose}>
                <X className="size-4" />
              </WindowButton>
            ) : null}
          </div>
        </header>
        {/* Minimised content stays mounted (hidden), so typing, scroll and uploads survive. */}
        <div hidden={minimized}
          className={cn("flex min-h-0 flex-1 flex-col overflow-auto", spec.padded !== false && "px-4 py-3", spec.bodyClassName)}>
          {children}
        </div>
        {spec.footer && !minimized ? (
          <footer className="flex shrink-0 flex-wrap items-center gap-2 border-t border-border bg-card px-4 py-3">{spec.footer}</footer>
        ) : null}
        {resizable ? EDGES.map(({ edge, className }) => (
          <div key={edge} aria-hidden="true" data-window-resize={edge}
            onPointerDown={beginDrag(edge)} onPointerMove={moveDrag} onPointerUp={endDrag} onPointerCancel={endDrag}
            className={cn("absolute z-10 touch-none", className)} />
        )) : null}
      </section>
    </WindowContext.Provider>
  );
}

function WindowButton({ label, onClick, children }: { label: string; onClick: () => void; children: ReactNode }) {
  return (
    <Button type="button" variant="ghost" size="icon" aria-label={label} title={label}
      onClick={event => { event.stopPropagation(); onClick(); }}
      className="size-8 text-muted-foreground hover:text-foreground">
      {children}
    </Button>
  );
}

// ---- public components ------------------------------------------------------------------------

/**
 * A non-modal window owned by the component that renders it. Children keep the owner's context
 * and state; the window closes when `open` turns false or the owner unmounts.
 *
 *   <FloatingWindow open={open} onOpenChange={setOpen} title="Notes" initialState="docked" persist="notes">
 *     <NotesEditor />
 *   </FloatingWindow>
 */
export function FloatingWindow({ id: givenId, open = true, onOpenChange, children, ...spec }: WindowSpec & {
  /** A stable id keeps the window's place across remounts; default: one per component instance. */
  id?: string;
  open?: boolean;
  onOpenChange?: (open: boolean) => void;
  children?: ReactNode;
}) {
  const generated = useId();
  const id = givenId ?? `window${generated}`;
  const layer = useSyncExternalStore(subscribeLayer, readLayer, readLayer);
  const persist = spec.persist;
  const initialState = spec.initialState;

  useLayoutEffect(() => {
    if (!open) return;
    register(id, { ...spec, persist, initialState });
    return () => unregister(id);
    // The spec's other fields are read live while rendering; only identity and opening matter here.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [id, open, persist, initialState]);

  const close = useCallback(() => onOpenChange?.(false), [onOpenChange]);
  if (!open || typeof document === "undefined") return null;
  const frame = <WindowFrame id={id} spec={spec} onClose={close}>{children}</WindowFrame>;
  // Outside the shell (a test, an embedded widget) there is no layer: stack on the body instead.
  return createPortal(layer ? frame : <div className={LAYER_CLASS}>{frame}</div>, layer ?? document.body);
}

const LAYER_CLASS = "pointer-events-none fixed inset-0 z-[45] isolate";

const layerListeners = new Set<() => void>();
function subscribeLayer(listener: () => void) {
  layerListeners.add(listener);
  return () => { layerListeners.delete(listener); };
}
function readLayer() {
  return layerElement;
}

/**
 * The shell's window layer: renders app-owned windows and hosts component-owned ones. Mounted
 * once by the app shell; apps never render it themselves.
 */
export function WindowLayer() {
  const [element, setElement] = useState<HTMLDivElement | null>(null);
  const all = useSyncExternalStore(subscribe, snapshot, snapshot);

  useLayoutEffect(() => {
    layerElement = element;
    for (const listener of layerListeners) listener();
    return () => {
      if (layerElement === element) layerElement = null;
      for (const listener of layerListeners) listener();
    };
  }, [element]);

  // Signing out (the shell unmounting) closes app-owned windows; they belong to the session.
  useEffect(() => () => {
    entries = entries.filter(entry => !entry.options);
    emit();
  }, []);

  return (
    <div ref={setElement} data-onno-window-layer="" className={LAYER_CLASS}>
      {element ? all.filter(entry => entry.options).map(entry => {
        const options = entry.options!;
        const close = () => {
          if (options.onClose?.() === false) return;
          windows.close(entry.id);
        };
        return (
          <AppWindow key={entry.id} id={entry.id} options={options} onClose={close} />
        );
      }) : null}
    </div>
  );
}

function AppWindow({ id, options, onClose }: { id: string; options: WindowOptions; onClose: () => void }) {
  const { content, onClose: _guard, ...spec } = options;
  void _guard;
  return (
    <WindowFrame id={id} spec={spec} onClose={onClose}>
      {typeof content === "function" ? <WindowContent render={content} /> : content}
    </WindowFrame>
  );
}

function WindowContent({ render }: { render: (window: WindowControls) => ReactNode }) {
  return <>{render(useWindow())}</>;
}

/** Test seam: forget every window (vitest runs share the module). */
export function resetWindowsForTests() {
  entries = [];
  zCounter = 1;
  openCounter = 0;
  dockedWidths.clear();
  emit();
}
