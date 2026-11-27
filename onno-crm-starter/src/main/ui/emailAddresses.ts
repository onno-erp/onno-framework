/**
 * Email address lists as the CRM keeps them on a message (`sentTo`, `copyTo`) and offers them (`delivery.cc`):
 * RFC 5322 entries, `Name <address>` or a bare address, joined by ", ". The server's `EmailAddresses` is the
 * same format; a name holding a comma is quoted, so a list splits back into its entries.
 */

/** The entries of a list, in order; commas or semicolons inside quotes or angle brackets do not split. */
export function splitAddresses(list: string | null | undefined): string[] {
  const entries: string[] = [];
  if (!list?.trim()) return entries;
  let current = "", quoted = false, escaped = false, angle = 0;
  for (const c of list) {
    if (escaped) { current += c; escaped = false; continue; }
    if (quoted && c === "\\") { current += c; escaped = true; continue; }
    if (c === '"') quoted = !quoted;
    else if (!quoted && c === "<") angle++;
    else if (!quoted && c === ">" && angle > 0) angle--;
    if ((c === "," || c === ";") && !quoted && angle === 0) {
      if (current.trim()) entries.push(current.trim());
      current = "";
      continue;
    }
    current += c;
  }
  if (current.trim()) entries.push(current.trim());
  return entries;
}

/** One entry's bare address and its name (unquoted; null when it has none). */
export function parseAddress(entry: string): { address: string; name: string | null } {
  const text = entry.trim();
  const open = text.lastIndexOf("<"), close = text.lastIndexOf(">");
  if (open < 0 || close < open) return { address: text, name: null };
  let name = text.slice(0, open).trim();
  if (name.length >= 2 && name.startsWith('"') && name.endsWith('"')) name = name.slice(1, -1).replace(/\\(.)/g, "$1");
  return { address: text.slice(open + 1, close).trim(), name: name || null };
}

const ADDRESS = /^[\p{L}\p{N}.!#$%&'*+/=?^_`{|}~-]+@[\p{L}\p{N}](?:[\p{L}\p{N}-]*[\p{L}\p{N}])?(?:\.[\p{L}\p{N}](?:[\p{L}\p{N}-]*[\p{L}\p{N}])?)+$/u;

/** Whether `address` is one plausible bare email address (the server checks it again). */
export function validAddress(address: string): boolean {
  return address.length <= 320 && ADDRESS.test(address);
}

/** What a person reads for an entry: the name when there is one, else the address. */
export function addressLabel(entry: string): string {
  const { address, name } = parseAddress(entry);
  return name || address;
}

/** Whether two lists hold the same addresses in the same order (ignoring case and names). */
export function sameAddresses(left: readonly string[], right: readonly string[]): boolean {
  return left.length === right.length && left.every((entry, index) =>
    parseAddress(entry).address.toLowerCase() === parseAddress(right[index]).address.toLowerCase());
}
