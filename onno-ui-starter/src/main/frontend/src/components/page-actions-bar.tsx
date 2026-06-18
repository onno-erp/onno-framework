import { useState } from "react";
import { toast } from "sonner";
import { Loader2 } from "lucide-react";
import { api } from "@/lib/api";
import { cn } from "@/lib/utils";
import { DynamicLucide } from "@/lib/icon-bridge";
import type { ActionResult } from "@/lib/types";

/**
 * One button in a page-level action section (PageBuilder.actions). A server button runs an
 * обработка-style handler; a navigation button just routes the client.
 */
export type PageActionButton = {
  key: string;
  label: string;
  icon?: string;
  /** Image URL/path shown instead of the lucide icon — e.g. a brand logo for "Connect with X". */
  logo?: string;
  server: boolean;
  url?: string;
};

function dispatchAction(url: string) {
  window.dispatchEvent(new CustomEvent("onno:action", { detail: url }));
}

/**
 * A section of page-level action buttons, bridged from the {@code onno-actions} div-custom block.
 * Each button either runs a server handler (POST /api/divkit/page-action → toast / navigate; any
 * embedded onno-list on the page self-refreshes over SSE from the handler's writes) or routes the
 * client (navigation-only). This is the first-class "section of buttons" primitive that replaces
 * bolting обработка triggers onto a list toolbar.
 */
export function PageActionsBar({
  heading,
  route,
  profile,
  buttons,
}: {
  heading?: string;
  route: string;
  profile?: string;
  buttons: PageActionButton[];
}) {
  const [pending, setPending] = useState<Record<string, boolean>>({});

  const run = (b: PageActionButton) => {
    if (!b.server) {
      if (b.url) dispatchAction(b.url);
      return;
    }
    setPending((s) => ({ ...s, [b.key]: true }));
    api
      .runPageAction(route, b.key, profile)
      .then((result: ActionResult) => {
        if (result?.message) toast.success(result.message);
        if (result?.navigate) dispatchAction(result.navigate);
        // result.refresh needs no work here: the data the handler wrote fans out over SSE,
        // so any embedded onno-list on this page reloads its own window.
      })
      .catch((e) => toast.error(e instanceof Error ? e.message : String(e)))
      .finally(() => setPending((s) => ({ ...s, [b.key]: false })));
  };

  if (!buttons.length) return null;

  return (
    // DivKit wraps custom blocks in pointer-events:none spans — re-assert so the buttons work.
    <div className="pointer-events-auto w-full">
      {heading ? (
        <h2 className="mb-2 text-sm font-semibold text-foreground">{heading}</h2>
      ) : null}
      <div className="flex flex-wrap gap-2 rounded-2xl border border-border bg-card p-4">
        {buttons.map((b) => {
          const busy = pending[b.key];
          return (
            <button
              key={b.key}
              type="button"
              disabled={busy}
              onClick={() => run(b)}
              className={cn(
                "inline-flex h-9 shrink-0 items-center gap-1.5 whitespace-nowrap rounded-lg bg-secondary px-3",
                "text-sm font-medium text-foreground transition-colors hover:bg-accent",
                "disabled:cursor-not-allowed disabled:opacity-60"
              )}
              title={b.label}
            >
              {busy ? (
                <Loader2 className="size-4 animate-spin" aria-hidden="true" />
              ) : b.logo ? (
                <img
                  src={b.logo}
                  alt=""
                  aria-hidden="true"
                  className="size-4 shrink-0 object-contain"
                />
              ) : b.icon ? (
                <DynamicLucide name={b.icon} size={16} />
              ) : null}
              {b.label}
            </button>
          );
        })}
      </div>
    </div>
  );
}
