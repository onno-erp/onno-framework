import { type ClassValue, clsx } from "clsx";
import { twMerge } from "tailwind-merge";

export function cn(...inputs: ClassValue[]) {
  return twMerge(clsx(inputs));
}

// Copy text to the clipboard, resolving true on success. Prefers the async
// Clipboard API (the only option in modern browsers) and falls back to a hidden
// textarea + execCommand for non-secure contexts (plain http, older browsers)
// where navigator.clipboard is unavailable or throws.
export async function copyToClipboard(text: string): Promise<boolean> {
  try {
    if (navigator.clipboard?.writeText) {
      await navigator.clipboard.writeText(text);
      return true;
    }
  } catch {
    // fall through to the legacy path
  }
  try {
    const ta = document.createElement("textarea");
    ta.value = text;
    ta.style.position = "fixed";
    ta.style.opacity = "0";
    document.body.appendChild(ta);
    ta.focus();
    ta.select();
    const ok = document.execCommand("copy");
    document.body.removeChild(ta);
    return ok;
  } catch {
    return false;
  }
}

export function toSnakeCase(str: string): string {
  return str
    .replace(/([a-z])([A-Z])/g, "$1_$2")
    .replace(/\s+/g, "_")
    .toLowerCase();
}

/**
 * Resolve a status-pill style from an `@EnumLabel(color = …)` hex (`#RGB` or `#RRGGBB`). Returns an
 * inline style carrying the colour as the background and a readable text colour derived from its
 * luminance — near-black on a light pill, white on a dark one — or `null` when the input isn't a
 * usable hex, so the caller falls back to plain text. Kept dependency-free (no theme tokens) because
 * the colour is an authored, fixed brand/spreadsheet colour, not a themeable surface.
 */
export function enumPillStyle(
  color: string | undefined | null,
): { backgroundColor: string; color: string } | null {
  if (!color) return null;
  const match = /^#?([0-9a-fA-F]{3}|[0-9a-fA-F]{6})$/.exec(color.trim());
  if (!match) return null;
  let hex = match[1].toLowerCase();
  if (hex.length === 3) hex = hex[0] + hex[0] + hex[1] + hex[1] + hex[2] + hex[2];
  const r = parseInt(hex.slice(0, 2), 16);
  const g = parseInt(hex.slice(2, 4), 16);
  const b = parseInt(hex.slice(4, 6), 16);
  // Perceived luminance (sRGB weights, 0–1): a light pill takes dark text, a dark pill white.
  const luminance = (0.299 * r + 0.587 * g + 0.114 * b) / 255;
  return { backgroundColor: `#${hex}`, color: luminance > 0.6 ? "#1f2937" : "#ffffff" };
}

/**
 * The row-tint classes for a conditional row style — the tone a list's `rowStyle(row)` customization
 * returns (a semantic tone the theme maps, not a raw color). A translucent wash so hover/selection
 * still read over it; an unknown tone returns `null` (no tint).
 */
export function rowStyleClass(style: unknown): string | null {
  switch (style) {
    case "danger":
      return "bg-destructive/10";
    case "warning":
      return "bg-[hsl(var(--warning))]/10";
    case "success":
      return "bg-[hsl(var(--success))]/10";
    case "accent":
      return "bg-primary/10";
    case "muted":
      return "opacity-60";
    default:
      return null;
  }
}
