import { Button } from "@onno/ui";
import { useEffect, useRef, useState } from "react";
import { text as t } from "@onno/ui";

/** Taller emails start collapsed so one newsletter does not push the whole thread off screen. */
const COLLAPSED_HEIGHT = 480;

/**
 * The frame document. The server already sanitized the HTML; this is the second wall. The frame has
 * no allow-scripts, so nothing in it can run, and its CSP refuses every fetch except inline styles
 * and images.
 */
function frameDocument(html: string): string {
  const images = "'self' data: https: http:";
  return `<!doctype html><html><head><meta charset="utf-8">`
    + `<meta http-equiv="Content-Security-Policy" content="default-src 'none'; style-src 'unsafe-inline'; img-src ${images}; font-src data:">`
    + `<base target="_blank">`
    + `<style>:root{color-scheme:light}html,body{margin:0;background:#fff;color:#1f2328}`
    + `body{padding:12px 14px;font:14px/1.5 -apple-system,BlinkMacSystemFont,"Segoe UI",Roboto,sans-serif;overflow-wrap:anywhere;overflow-x:hidden}`
    + `img{max-width:100%;height:auto}table{max-width:100%}a{color:#0b65c2}blockquote{margin:0 0 0 .6em;padding-left:.8em;border-left:2px solid #d0d7de;color:#57606a}</style>`
    + `</head><body>${html}</body></html>`;
}

/**
 * A sanitized HTML email, isolated in a sandboxed frame so its styles cannot reach the inbox and
 * the inbox theme cannot break its layout. The frame is same-origin but script-less, which lets the
 * host measure it without giving the email any ability to act. An email laid out wider than the
 * bubble (the usual fixed 600px newsletter table) is zoomed down to fit, as mail apps on phones do,
 * instead of being cropped or scrolled sideways.
 */
export function EmailHtmlBody({ html }: { html: string }) {
  const frame = useRef<HTMLIFrameElement>(null);
  const [height, setHeight] = useState(120);
  const [expanded, setExpanded] = useState(false);

  useEffect(() => {
    const element = frame.current;
    if (!element) return;
    let observer: ResizeObserver | null = null;
    let content: ResizeObserver | null = null;
    let frameWidth = -1;
    let lastHeight = -1;
    const measure = () => {
      const doc = element.contentDocument;
      const root = doc?.documentElement;
      if (!doc?.body || !root) return;
      // Lay out at natural size, then zoom the whole body so its widest element fits the frame.
      doc.body.style.zoom = "1";
      const available = root.clientWidth;
      const needed = Math.max(root.scrollWidth, doc.body.scrollWidth);
      const ratio = available > 0 ? available / needed : 1;
      // Sub-2% overflow is rounding or a scrollbar gutter, not a wide layout: leave it unscaled.
      const scale = ratio < 0.98 ? Math.max(0.3, ratio) : 1;
      doc.body.style.zoom = String(scale);
      const next = Math.max(40, Math.ceil(root.scrollHeight));
      if (next !== lastHeight) { lastHeight = next; setHeight(next); }
    };
    const loaded = () => {
      measure();
      const doc = element.contentDocument;
      if (!doc?.body) return;
      observer?.disconnect();
      content?.disconnect();
      // Re-fit when the bubble changes width (window resize, panel toggle, a hidden tab shown again: it measured 0 wide).
      observer = new ResizeObserver(() => {
        if (element.clientWidth === frameWidth) return;
        frameWidth = element.clientWidth;
        measure();
      });
      observer.observe(element);
      // The email's own content can grow after load (web fonts, late layout, images without sizes): follow it, or the
      // frame keeps its first, too short height and crops the email. measure() only sets the height when it changes.
      const View = doc.defaultView as (Window & typeof globalThis) | null;
      if (View?.ResizeObserver) {
        content = new View.ResizeObserver(() => measure());
        content.observe(doc.body);
      }
      doc.querySelectorAll("img").forEach(img => img.addEventListener("load", measure));
      void doc.fonts?.ready.then(measure);
    };
    element.addEventListener("load", loaded);
    // A frame that is already loaded (React re-ran the effect) is measured now, not on a load event that has passed.
    if (element.contentDocument?.readyState === "complete" && element.contentDocument.body) loaded();
    return () => { element.removeEventListener("load", loaded); observer?.disconnect(); content?.disconnect(); };
  }, [html]);

  const collapsible = height > COLLAPSED_HEIGHT;
  return (
    <div className="mt-1.5">
      <div className="relative overflow-hidden rounded-field border border-border bg-white"
        style={{ height: collapsible && !expanded ? COLLAPSED_HEIGHT : height }}>
        <iframe
          ref={frame}
          title={t("crm.email.frameTitle")}
          sandbox="allow-same-origin allow-popups allow-popups-to-escape-sandbox"
          referrerPolicy="no-referrer"
          srcDoc={frameDocument(html)}
          className="block w-full border-0"
          style={{ height }}
        />
        {collapsible && !expanded && (
          <div className="pointer-events-none absolute inset-x-0 bottom-0 h-16 bg-gradient-to-t from-white to-transparent" />
        )}
      </div>
      {collapsible && (
        <Button type="button" variant="ghost" className="mt-1 h-auto p-0 text-[11px] font-medium underline text-inherit"
          onClick={() => setExpanded(value => !value)}>
          {expanded ? t("crm.email.collapse") : t("crm.email.expand")}
        </Button>
      )}
    </div>
  );
}
