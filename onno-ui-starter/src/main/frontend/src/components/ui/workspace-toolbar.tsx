import { Fragment, useLayoutEffect, useRef, useState, type ReactNode } from "react";
import { MoreHorizontal } from "lucide-react";
import { Button } from "./button";
import { ResponsiveActionPanel } from "./responsive-action-panel";
import { useMessages } from "@/providers/messages-provider";
import { cn } from "@/lib/utils";

export type WorkspaceToolbarLayout = Partial<Record<"start" | "center" | "end" | "secondary" | "overflow" | "hidden", string[]>>;
export type WorkspaceToolbarProps = {
  items: { id: string; content: ReactNode }[];
  layout?: WorkspaceToolbarLayout;
  compactLayout?: WorkspaceToolbarLayout;
  compact?: boolean;
  balanced?: boolean;
  /** Keep a single-row toolbar inside its width: first hide `[data-toolbar-label]` text, then apply {@link narrowLayout}. */
  fit?: boolean;
  /** Layout used when {@link fit} still overflows with condensed labels, e.g. moving tools to `secondary`. */
  narrowLayout?: WorkspaceToolbarLayout;
  className?: string;
};
/** Layout only: callers supply authorized controls with their existing handlers and state.
 * Arrays replace the corresponding region; omitted ids are hidden. Each id renders once. */
export function WorkspaceToolbar({items, layout, compactLayout, compact, balanced, fit, narrowLayout, className}: WorkspaceToolbarProps) {
  const t = useMessages();
  const fitting = !!fit && !compact && !balanced;
  const [stage, fitRef] = useFitStage(fitting, narrowLayout ? 2 : 1);
  const resolved: WorkspaceToolbarLayout = {...(layout ? {} : {start: items.map(item => item.id)}), ...layout,
    ...(compact ? compactLayout : {}), ...(fitting && stage >= 2 ? narrowLayout : {})};
  const hidden = new Set(resolved.hidden ?? []);
  const used = new Set<string>();
  const controls = new Map(items.map(item => [item.id, item.content]));
  const region = (name: "start" | "center" | "end" | "secondary" | "overflow") => (resolved[name] ?? []).flatMap(id => {
    const content = controls.get(id);
    if (hidden.has(id) || used.has(id) || content == null || content === false) return [];
    used.add(id);
    return [<Fragment key={id}>{content}</Fragment>];
  });
  const start = region("start"), center = region("center"), end = region("end"), secondary = region("secondary"), overflow = region("overflow");
  if (![start,center,end,secondary,overflow].some(nodes => nodes.length)) return null;
  return <div className={cn("min-w-0 shrink-0",className)} data-toolbar-condensed={fitting && stage >= 1 ? "" : undefined}>
    {(start.length + center.length + end.length + overflow.length > 0) && <div ref={fitRef} className={cn("min-w-0 items-center gap-2",balanced ? "grid grid-cols-[var(--workspace-toolbar-edge,72px)_minmax(0,1fr)_var(--workspace-toolbar-edge,72px)]" : "flex")}>
      <div className="flex min-w-0 items-center gap-1">{start}</div>
      <div className={cn("min-w-0",balanced ? "text-center" : "flex flex-1 items-center gap-2",fitting && "min-w-32")}>{center}</div>
      <div className={cn("ml-auto flex items-center justify-end gap-1",fitting ? "shrink-0" : "min-w-0")}>{end}
        {!!overflow.length && <ResponsiveActionPanel presentation="actions" label={t("shell.more")} trigger={<Button variant="ghost" size="toolbar" aria-label={t("shell.more")}><MoreHorizontal className="size-4" /></Button>}>
          {overflow}
        </ResponsiveActionPanel>}
      </div>
    </div>}
    {!!secondary.length && <div className="mt-2 flex min-w-0 flex-wrap items-center gap-2">{secondary}</div>}
  </div>;
}

/**
 * Escalates 0 → full, 1 → condensed labels, 2 → narrow layout while the row overflows, and steps back
 * once the row is again as wide as the overflow measured at the previous stage (so it never oscillates).
 */
function useFitStage(enabled: boolean, max: number) {
  const [row, setRow] = useState<HTMLDivElement | null>(null);
  const [value, setValue] = useState(0);
  const needed = useRef<number[]>([]);
  useLayoutEffect(() => {
    if (!enabled || !row) { setValue(0); return; }
    const measure = () => setValue(current => {
      const width = row.clientWidth;
      if (row.scrollWidth > width + 1 && current < max) { needed.current[current] = row.scrollWidth; return current + 1; }
      let next = Math.min(current, max);
      while (next > 0 && (needed.current[next - 1] ?? Infinity) <= width) next--;
      return next;
    });
    measure();
    const observer = new ResizeObserver(measure);
    observer.observe(row);
    for (const region of row.children) observer.observe(region);
    return () => observer.disconnect();
  }, [enabled, max, row, value]);
  return [value, setRow] as const;
}
