import type { ReactNode } from "react";

const URL_RE = /https?:\/\/[^\s<]+/gi;

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

export function linkify(text: string): ReactNode[] {
  const nodes: ReactNode[] = [];
  let last = 0;
  let key = 0;
  for (const match of text.matchAll(URL_RE)) {
    const start = match.index ?? 0;
    const [url, trailing] = splitTrailing(match[0]);
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
      </a>
    );
    if (trailing) nodes.push(trailing);
    last = start + match[0].length;
  }
  if (last < text.length) nodes.push(text.slice(last));
  return nodes;
}
