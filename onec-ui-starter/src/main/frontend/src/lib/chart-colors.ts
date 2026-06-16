/**
 * Color resolution for the data widgets (chart, stat, sparkline, gauge). Series colors come from
 * a theme-driven categorical palette (the `--chart-N` CSS vars in index.css) by default, and an
 * author can override them per widget from the Java DSL via `config("colors", "...")`.
 *
 * An override is a comma list of tokens applied slot-by-slot; each token is either a named alias
 * (`primary`, `success`, `warning`, `destructive`, `muted`), a palette slot (`chart-1`..`chart-8`),
 * or a raw CSS color (`#8b5cf6`, `hsl(270 70% 60%)`, `rebeccapurple`). Unspecified slots fall back
 * to the palette, which itself cycles when a chart needs more series than the palette has.
 */

const PALETTE_SIZE = 8;

/** The ordered default palette as CSS color strings backed by the theme `--chart-N` vars. */
export function chartPalette(): string[] {
  return Array.from({ length: PALETTE_SIZE }, (_, i) => `hsl(var(--chart-${i + 1}))`);
}

// Named aliases an author may use in config("colors", ...); each maps to a theme CSS var so the
// color tracks light/dark mode and any brand override.
const ALIASES: Record<string, string> = {
  primary: "hsl(var(--primary))",
  success: "hsl(var(--success))",
  warning: "hsl(var(--warning))",
  destructive: "hsl(var(--destructive))",
  danger: "hsl(var(--destructive))",
  muted: "hsl(var(--muted-foreground))",
};

/** Resolve one token to a CSS color: an alias, a `chart-N` slot, or a literal CSS color. */
export function resolveColorToken(token: string): string {
  const t = token.trim();
  if (!t) return "";
  const lower = t.toLowerCase();
  if (lower in ALIASES) return ALIASES[lower];
  const slot = /^chart-([1-8])$/.exec(lower);
  if (slot) return `hsl(var(--chart-${slot[1]}))`;
  // Otherwise treat it as a literal CSS color (hex / rgb() / hsl() / named).
  return t;
}

/** Parse a `config("colors", ...)` value into its resolved CSS colors (empty when unset). */
export function parseColors(override?: string | null): string[] {
  return (override ?? "")
    .split(",")
    .map(resolveColorToken)
    .filter(Boolean);
}

/**
 * The `count` colors to paint with: author overrides win slot-by-slot, the palette fills the rest
 * (cycling if `count` exceeds the palette length). Always returns exactly `count` colors.
 */
export function resolveColors(count: number, override?: string | null): string[] {
  const palette = chartPalette();
  const custom = parseColors(override);
  return Array.from({ length: count }, (_, i) => custom[i] ?? palette[i % palette.length]);
}

/** A single color — the first override token, else the lead palette slot. */
export function resolveColor(override?: string | null): string {
  return resolveColors(1, override)[0];
}
