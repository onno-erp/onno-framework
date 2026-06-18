import { createContext, useContext, useEffect, useState } from "react";
import { api } from "@/lib/api";
import { useTheme } from "@/providers/theme-provider";
import type { BrandColors, Branding } from "@/lib/types";

const BrandingContext = createContext<Branding>({});

/**
 * Fetches the consumer's branding once on mount and applies the parts the React shell
 * owns: the document title (from {@code appName}), the favicon (from {@code faviconUrl}),
 * and the brand accent. The React shell (toolbars, inputs, buttons, list hovers, focus
 * rings) is styled from shadcn CSS variables, not server-rendered DivKit — so without this
 * a focused input would still draw a neutral-black ring and primary buttons stay black even
 * when the brand is blue. We map the per-mode brand palette onto the relevant variables so
 * the whole chrome — not just the DivKit nav — carries the brand.
 *
 * The logo is exposed via {@link useBranding} for the login screen; the in-app sidebar/menu
 * logo is rendered server-side. Fails silently — an unbranded app keeps the CSS defaults.
 */
export function BrandingProvider({ children }: { children: React.ReactNode }) {
  const [branding, setBranding] = useState<Branding>({});
  const { theme } = useTheme();

  useEffect(() => {
    api
      .getBranding()
      .then((b) => {
        setBranding(b);
        if (b.appName) {
          document.title = b.appName;
        }
        if (b.faviconUrl) {
          applyFavicon(b.faviconUrl);
        }
      })
      .catch(() => {
        // No branding configured — keep the index.html defaults.
      });
  }, []);

  // Re-tint the shadcn variables for the active mode whenever the palette or theme changes.
  // The palette differs per mode (a darker/brighter primary), so this re-runs on theme flips;
  // it also tracks the OS preference while the theme is "system".
  useEffect(() => {
    const apply = () => applyBrandVariables(branding.palette, resolveMode(theme));
    apply();
    if (theme !== "system") return;
    const mq = window.matchMedia("(prefers-color-scheme: dark)");
    mq.addEventListener("change", apply);
    return () => mq.removeEventListener("change", apply);
  }, [branding.palette, theme]);

  return <BrandingContext.Provider value={branding}>{children}</BrandingContext.Provider>;
}

function resolveMode(theme: string): "light" | "dark" {
  if (theme === "light" || theme === "dark") return theme;
  return window.matchMedia("(prefers-color-scheme: dark)").matches ? "dark" : "light";
}

// shadcn variables are HSL triplets consumed as `hsl(var(--x))`, so brand hexes are converted
// to "H S% L%". We override only the accent-bearing slots; structure (borders, surfaces, muted
// text) stays neutral. `--primary`/`--ring` take the brand primary (buttons + focus rings), the
// primary foreground goes white for legible text on a colored button, and `--accent` becomes a
// soft surface in the brand HUE (hovers, selected rows, dropdown items) — derived rather than
// taken from the soft tint, which is calibrated for the DivKit nav and reads near-white here.
// A missing brand primary clears the overrides → CSS default (unbranded apps untouched).
function applyBrandVariables(palette: Branding["palette"], mode: "light" | "dark") {
  const root = document.documentElement;
  const colors: BrandColors | undefined = mode === "dark" ? palette?.dark : palette?.light;
  const hsl = colors?.primary ? hexToHsl(colors.primary) : null;

  if (!hsl) {
    ["--primary", "--primary-foreground", "--ring", "--accent"].forEach((v) =>
      root.style.removeProperty(v)
    );
    return;
  }
  const primary = `${hsl.h} ${hsl.s}% ${hsl.l}%`;
  root.style.setProperty("--primary", primary);
  root.style.setProperty("--primary-foreground", "0 0% 100%");
  // The focus ring is an "active border" — kept on-brand but desaturated so it reads as a soft
  // accent rather than a loud saturated line (it still has enough contrast to be a clear focus
  // affordance). Same intent as the half-opacity focused-island border.
  root.style.setProperty("--ring", `${hsl.h} ${Math.round(hsl.s * 0.55)}% ${hsl.l}%`);
  // A subtle brand-hued surface: pale in light mode, a deep tint in dark — present enough to
  // read as "ours" on hover without the loudness of the full primary.
  root.style.setProperty("--accent", mode === "dark" ? `${hsl.h} 45% 18%` : `${hsl.h} 80% 93%`);
}

// "#2563EB" → {h:217, s:83, l:53}. Returns null for an unparseable hex.
function hexToHsl(hex: string): { h: number; s: number; l: number } | null {
  let x = hex.trim().replace(/^#/, "");
  if (x.length === 3) x = x.split("").map((c) => c + c).join("");
  if (!/^[0-9a-f]{6}$/i.test(x)) return null;
  const r = parseInt(x.slice(0, 2), 16) / 255;
  const g = parseInt(x.slice(2, 4), 16) / 255;
  const b = parseInt(x.slice(4, 6), 16) / 255;
  const max = Math.max(r, g, b);
  const min = Math.min(r, g, b);
  const l = (max + min) / 2;
  const d = max - min;
  let h = 0;
  let s = 0;
  if (d !== 0) {
    s = l > 0.5 ? d / (2 - max - min) : d / (max + min);
    if (max === r) h = (g - b) / d + (g < b ? 6 : 0);
    else if (max === g) h = (b - r) / d + 2;
    else h = (r - g) / d + 4;
    h /= 6;
  }
  return { h: Math.round(h * 360), s: Math.round(s * 100), l: Math.round(l * 100) };
}

// Point every icon <link> at the configured favicon, creating one if the page has none.
function applyFavicon(href: string) {
  const links = document.querySelectorAll<HTMLLinkElement>("link[rel~='icon']");
  if (links.length === 0) {
    const link = document.createElement("link");
    link.rel = "icon";
    link.href = href;
    document.head.appendChild(link);
    return;
  }
  links.forEach((link) => {
    link.href = href;
    // Drop a type hint that may not match the new asset (e.g. svg → png).
    link.removeAttribute("type");
  });
}

export const useBranding = () => useContext(BrandingContext);
