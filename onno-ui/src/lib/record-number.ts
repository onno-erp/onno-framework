/** Compact a record's code/number for display without changing its stored value or sort key.
 * Only the final numeric part loses leading zeros; prefixes and significant digits stay intact.
 * Keep this string-based: identifiers can exceed JavaScript's safe integer range.
 */
export function shortRecordNumber(value: unknown): string {
  const text = value == null ? "" : String(value);
  return text.replace(/^((?:.*\D)?)0+(?=\d)(\d+)$/, "$1$2");
}
