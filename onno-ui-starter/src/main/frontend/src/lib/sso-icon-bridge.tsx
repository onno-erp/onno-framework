import { useSyncExternalStore } from "react";
import { createPortal } from "react-dom";

/**
 * Bridges DivKit's {@code div-custom} blocks of type {@code onno-sso-icon} to a monochrome provider
 * logo on an SSO login button. The server (LoginDivBuilder) emits an {@code onno-sso-icon} node
 * carrying {@code src/color/size} when a provider supplies an {@code iconUrl}; DivKit instantiates
 * the {@link OnnoSsoIconElement} custom element and assigns those as properties, and {@link
 * SsoIconPortals} (mounted on the login route) renders an {@link SsoIcon} into each.
 *
 * <p>Mirrors {@code icon-bridge} / {@code hint-bridge} exactly — the same store/portal pattern DivKit
 * needs to host a React node inside a custom block — but renders an arbitrary same-origin SVG URL
 * (the connector that owns the provider serves it, e.g. {@code /api/auth/telegram/logo.svg}) rather
 * than a named lucide glyph.</p>
 */

/**
 * A provider logo painted in the button's text color. The SVG is used as a CSS mask and filled with
 * {@code color} (the foreground the server resolved for the button), so a monochrome logo inherits
 * the button text color — equivalent to the SVG's own {@code currentColor}, but reliable for an
 * external URL (a plain {@code <img>} wouldn't inherit the host's color). Falls back to
 * {@code currentColor} when no color is supplied.
 */
export function SsoIcon({
  src,
  color,
  size = 18,
}: {
  src: string;
  color?: string;
  size?: number;
}) {
  if (!src) return null;
  // CSS url() in a mask resolves relative to the document, so a same-origin path (e.g.
  // "/api/auth/telegram/logo.svg") works as given. Quote it to survive any odd characters.
  const maskImage = `url("${src.replace(/"/g, "%22")}")`;
  return (
    <span
      aria-hidden="true"
      data-onno-sso-icon=""
      style={{
        display: "inline-block",
        width: size,
        height: size,
        flex: "none",
        backgroundColor: color ?? "currentColor",
        // Longhands (not the `mask` shorthand) so each property is explicit and reliable; -webkit-
        // mirrors them for Safari/Chrome.
        WebkitMaskImage: maskImage,
        maskImage,
        WebkitMaskRepeat: "no-repeat",
        maskRepeat: "no-repeat",
        WebkitMaskPosition: "center",
        maskPosition: "center",
        WebkitMaskSize: "contain",
        maskSize: "contain",
      }}
    />
  );
}

type Mount = {
  id: number;
  el: HTMLElement;
  src: string;
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

class OnnoSsoIconElement extends HTMLElement {
  private readonly _id = ++seq;
  private _src = "";
  private _color: string | undefined;
  private _size: number | undefined;

  // DivKit assigns each custom_props key as a property on the element.
  set src(value: string) {
    this._src = value ?? "";
    this.sync();
  }
  get src(): string {
    return this._src;
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
    if (!this.isConnected || !this._src) return;
    const existing = mounts.find((m) => m.el === this);
    if (existing) {
      existing.src = this._src;
      existing.color = this._color;
      existing.size = this._size;
    } else {
      mounts = [
        ...mounts,
        { id: this._id, el: this, src: this._src, color: this._color, size: this._size },
      ];
    }
    mounts = [...mounts];
    emit();
  }
}

let defined = false;

/** Register the custom element once, before any DivKit content renders. */
export function defineSsoIconElement() {
  if (defined || typeof customElements === "undefined") return;
  if (!customElements.get("onno-sso-icon")) {
    customElements.define("onno-sso-icon", OnnoSsoIconElement);
  }
  defined = true;
}

defineSsoIconElement();

/** The DivKit {@code customComponents} map: custom_type → element tag. */
export const SSO_ICON_CUSTOM_COMPONENTS = new Map<string, { element: string }>([
  ["onno-sso-icon", { element: "onno-sso-icon" }],
]);

/**
 * Portals every live {@code <onno-sso-icon>} to its {@link SsoIcon}. Mount once, on the login route
 * (alongside {@code IconPortals}).
 */
export function SsoIconPortals() {
  const list = useSyncExternalStore(subscribe, getSnapshot);
  return (
    <>
      {list.map((m) =>
        createPortal(
          <SsoIcon src={m.src} color={m.color} size={m.size} />,
          m.el,
          String(m.id)
        )
      )}
    </>
  );
}
