import type { ReactNode } from "react";

// Only explicit http(s) URLs become links, so message text can never produce a javascript:/data: href.
const URL_RE = /https?:\/\/[^\s<>"]+/gi;

/** Trailing sentence punctuation stays text; a closing paren is kept when the URL opened one. */
function splitTrailing(raw: string): [string, string] {
  let end = raw.length;
  while (end > 0) {
    const ch = raw[end - 1];
    if (".,;:!?'".includes(ch) || (ch === ")" && !raw.slice(0, end - 1).includes("("))) end--;
    else break;
  }
  return [raw.slice(0, end), raw.slice(end)];
}

/**
 * Plain message text with bare http(s) URLs turned into links that open in a new tab. Links inherit
 * the surrounding text colour, so they stay readable on outbound (primary) and inbound bubbles alike.
 */
export function LinkedText({ text, className = "underline underline-offset-2 hover:decoration-2" }: { text: string; className?: string }) {
  const nodes: ReactNode[] = [];
  let last = 0;
  for (const m of text.matchAll(URL_RE)) {
    const start = m.index ?? 0;
    const [url, trailing] = splitTrailing(m[0]);
    if (!url.match(/^https?:\/\/[^/?#]/i)) continue;
    if (start > last) nodes.push(text.slice(last, start));
    nodes.push(<a key={start} href={url} target="_blank" rel="noopener noreferrer" className={`break-all ${className}`}
      onClick={e => e.stopPropagation()}>{url}</a>);
    if (trailing) nodes.push(trailing);
    last = start + m[0].length;
  }
  if (last < text.length) nodes.push(text.slice(last));
  return <>{nodes}</>;
}
