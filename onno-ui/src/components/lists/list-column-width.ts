/**
 * Parse a list-column width authored as a positive whole-pixel value.
 *
 * Field width hints are shared with edit forms, where tokens such as `half` and `1/2` are valid.
 * Those layout tokens must not be read with `parseInt`: `parseInt("1/2", 10)` is `1`, which
 * collapses an otherwise healthy table into one-pixel tracks.
 */
export function listColumnPixelWidth(width: string | null | undefined): number | null {
  const match = /^\s*([0-9]+)(?:px)?\s*$/i.exec(width ?? "");
  if (!match) return null;
  const pixels = Number(match[1]);
  return Number.isSafeInteger(pixels) && pixels > 0 ? pixels : null;
}
