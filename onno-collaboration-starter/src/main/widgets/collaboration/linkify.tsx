import type { ReactNode } from "react";

// Matches bare http(s) URLs. We intentionally only linkify explicit http/https schemes —
// never javascript:/data:/mailto: — so user-authored text can't smuggle a dangerous href.
const URL_RE = /https?:\/\/[^\s<]+/gi;

// Peel trailing characters that are almost always sentence punctuation rather than part of the
// URL ("see https://example.com." → drop the period). A closing paren is only trimmed when the
// URL has no matching opening one, so Wikipedia-style ...(disambiguation) links survive intact.
function splitTrailing(raw: string): [url: string, trailing: string] {
  let end = raw.length;
  while (end > 0) {
    const ch = raw[end - 1];
    if (".,;:!?\"'".includes(ch)) {
      end--;
      continue;
    }
    if (ch === ")" && !raw.slice(0, end - 1).includes("(")) {
      end--;
      continue;
    }
    break;
  }
  return [raw.slice(0, end), raw.slice(end)];
}

/**
 * Split free text into React nodes, turning bare http(s) URLs into clickable links that open in a
 * new tab (noopener/noreferrer). Used for user-authored content such as comment bodies; the
 * surrounding element supplies whitespace-pre-wrap, so plain-text segments keep their newlines.
 */
export function linkify(text: string): ReactNode[] {
  const nodes: ReactNode[] = [];
  let last = 0;
  let key = 0;
  for (const m of text.matchAll(URL_RE)) {
    const start = m.index ?? 0;
    const [url, trailing] = splitTrailing(m[0]);
    if (start > last) nodes.push(text.slice(last, start));
    nodes.push(
      <a
        key={key++}
        href={url}
        target="_blank"
        rel="noreferrer noopener"
        className="break-all text-primary underline underline-offset-2 hover:no-underline"
      >
        {url}
      </a>,
    );
    if (trailing) nodes.push(trailing);
    last = start + m[0].length;
  }
  if (last < text.length) nodes.push(text.slice(last));
  return nodes;
}
