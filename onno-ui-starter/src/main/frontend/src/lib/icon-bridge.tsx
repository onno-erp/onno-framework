import { Suspense, lazy, useSyncExternalStore, type ComponentType } from "react";
import { createPortal } from "react-dom";
import dynamicIconImports from "lucide-react/dynamicIconImports";
import { Circle } from "lucide-react";
import { IslandErrorBoundary } from "@/lib/island-error-boundary";

/**
 * Bridges DivKit's {@code div-custom} blocks of type {@code onno-icon} to lucide icons,
 * rendered by name. The server (ShellLayoutBuilder) emits nav/account glyphs as
 * {@code onno-icon} nodes carrying {@code name/color/size}; DivKit instantiates the
 * {@link OnnoIconElement} custom element and assigns those as properties, and {@link
 * IconPortals} (mounted high in the tree) renders the matching lucide icon into each.
 *
 * <p>Why this exists: nav icons used to be {@code div-image} pointing at pre-generated
 * SVGs under {@code public/icons}. Any authored icon name outside that hand-curated,
 * manually-regenerated set 404'd and rendered <em>blank</em> with no signal. Rendering
 * from lucide by name means every one of its ~1500 icons works with no build step, and
 * an unknown name degrades to a visible fallback glyph instead of nothing.</p>
 */

type IconProps = { color?: string; size?: number | string };
type IconLoader = () => Promise<{ default: ComponentType<IconProps> }>;

// dynamicIconImports maps every kebab-case lucide name to a lazy import of that icon.
const IMPORTS = dynamicIconImports as unknown as Record<string, IconLoader>;
const lazyCache = new Map<string, ComponentType<IconProps>>();

// lucide renamed many icons; the old names are still what most people (and our older
// layouts/docs) type, but they aren't keys in dynamicIconImports — so without this they'd
// silently fall back to the circle. Map the well-known deprecated names to current ones.
const ALIASES: Record<string, string> = {
  home: "house",
  "bar-chart": "chart-column",
  "bar-chart-2": "chart-column",
  "bar-chart-3": "chart-column",
  "bar-chart-4": "chart-column",
  "bar-chart-horizontal": "chart-bar",
  "pie-chart": "chart-pie",
  "line-chart": "chart-line",
  "area-chart": "chart-area",
  "scatter-chart": "chart-scatter",
  "candlestick-chart": "chart-candlestick",
  "gantt-chart": "chart-gantt",
};

function lucideFor(name: string): ComponentType<IconProps> | null {
  const key = ALIASES[name] ?? name;
  const loader = IMPORTS[key];
  if (!loader) return null;
  let Component = lazyCache.get(key);
  if (!Component) {
    Component = lazy(loader);
    lazyCache.set(key, Component);
  }
  return Component;
}

/**
 * Renders a lucide icon by its kebab-case {@code name} (e.g. {@code "bar-chart"}).
 * Unknown names fall back to a circle so an icon is never blank. The Suspense fallback
 * is a same-size spacer, so the async load doesn't shift surrounding layout.
 */
export function DynamicLucide({
  name,
  color,
  size = 16,
}: {
  name: string;
  color?: string;
  size?: number;
}) {
  const Component = lucideFor(name);
  if (!Component) return <Circle color={color} size={size} />;
  return (
    <Suspense fallback={<span style={{ display: "inline-block", width: size, height: size }} />}>
      <Component color={color} size={size} />
    </Suspense>
  );
}

type Mount = {
  id: number;
  el: HTMLElement;
  name: string;
  color?: string;
  size?: number;
  // Optional active-state coloring: when activePath matches the current route, the
  // icon paints activeColor instead of color. This mirrors how the nav's label/
  // background bind to DivKit's active_path variable (which the React client can't
  // evaluate inside a custom block), so the glyph highlights on navigation too.
  activeColor?: string;
  activePath?: string;
};

// A new array reference is published on every change so useSyncExternalStore sees it.
let mounts: Mount[] = [];
const listeners = new Set<() => void>();
let seq = 0;

// The nav's active path — the SAME value divkit-view writes into DivKit's active_path
// variable (the focused island's active tab), NOT the raw URL. Labels/backgrounds bind
// to that variable server-side; if icons resolved active from useLocation instead, the
// two could disagree (e.g. Esc-closing the last tab clears the variable but resets the
// URL to "/", leaving the Dashboard glyph lit while its label went idle).
let activeNavPath: string | null = null;

/** Publish the nav-active path; call wherever the active_path DivKit variable is set. */
export function setIconActivePath(path: string | null) {
  if (activeNavPath === path) return;
  activeNavPath = path;
  emit();
}
function getActivePath(): string | null {
  return activeNavPath;
}

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

class OnnoIconElement extends HTMLElement {
  private readonly _id = ++seq;
  private _name = "";
  private _color: string | undefined;
  private _size: number | undefined;
  private _activeColor: string | undefined;
  private _activePath: string | undefined;

  // DivKit assigns each custom_props key as a property on the element.
  set name(value: string) {
    this._name = value ?? "";
    this.sync();
  }
  get name(): string {
    return this._name;
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
  set activeColor(value: string | undefined) {
    this._activeColor = value;
    this.sync();
  }
  get activeColor(): string | undefined {
    return this._activeColor;
  }
  set activePath(value: string | undefined) {
    this._activePath = value;
    this.sync();
  }
  get activePath(): string | undefined {
    return this._activePath;
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
    if (!this.isConnected || !this._name) return;
    const existing = mounts.find((m) => m.el === this);
    if (existing) {
      existing.name = this._name;
      existing.color = this._color;
      existing.size = this._size;
      existing.activeColor = this._activeColor;
      existing.activePath = this._activePath;
    } else {
      mounts = [
        ...mounts,
        {
          id: this._id,
          el: this,
          name: this._name,
          color: this._color,
          size: this._size,
          activeColor: this._activeColor,
          activePath: this._activePath,
        },
      ];
    }
    mounts = [...mounts];
    emit();
  }
}

let defined = false;

/** Register the custom element once, before any DivKit content renders. */
export function defineIconElement() {
  if (defined || typeof customElements === "undefined") return;
  if (!customElements.get("onno-icon")) {
    customElements.define("onno-icon", OnnoIconElement);
  }
  defined = true;
}

defineIconElement();

/** The DivKit {@code customComponents} map: custom_type → element tag. */
export const ICON_CUSTOM_COMPONENTS = new Map<string, { element: string }>([
  ["onno-icon", { element: "onno-icon" }],
]);

/**
 * Portals every live {@code <onno-icon>} to its lucide icon. Mount once, high in the
 * tree. Active-state color resolves against {@link setIconActivePath}'s value so the
 * glyph and its DivKit-bound label always agree.
 */
export function IconPortals() {
  const list = useSyncExternalStore(subscribe, getSnapshot);
  const active = useSyncExternalStore(subscribe, getActivePath);
  return (
    <>
      {list.map((m) => {
        const color =
          m.activeColor && m.activePath != null && active === m.activePath ? m.activeColor : m.color;
        return createPortal(
          <IslandErrorBoundary label={`icon ${m.name}`}>
            <DynamicLucide name={m.name} color={color} size={m.size} />
          </IslandErrorBoundary>,
          m.el,
          String(m.id)
        );
      })}
    </>
  );
}
