import { Fragment, type ReactNode } from "react";
import { MoreHorizontal } from "lucide-react";
import { Button } from "./button";
import { Popover, PopoverTrigger, PopoverContent } from "./popover";
import { useMessages } from "@/providers/messages-provider";
import { cn } from "@/lib/utils";

export type WorkspaceToolbarLayout = Partial<Record<"start" | "center" | "end" | "secondary" | "overflow" | "hidden", string[]>>;
export type WorkspaceToolbarProps = {
  items: { id: string; content: ReactNode }[];
  layout?: WorkspaceToolbarLayout;
  compactLayout?: WorkspaceToolbarLayout;
  compact?: boolean;
  balanced?: boolean;
  className?: string;
};
/** Layout only: callers supply authorized controls with their existing handlers and state.
 * Arrays replace the corresponding region; omitted ids are hidden. Each id renders once. */
export function WorkspaceToolbar({items, layout, compactLayout, compact, balanced, className}: WorkspaceToolbarProps) {
  const t = useMessages();
  const resolved: WorkspaceToolbarLayout = {...(layout ? {} : {start: items.map(item => item.id)}), ...layout, ...(compact ? compactLayout : {})};
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
  return <div className={cn("min-w-0 shrink-0",className)}>
    {(start.length + center.length + end.length + overflow.length > 0) && <div className={cn("min-w-0 items-center gap-2",balanced ? "grid grid-cols-[var(--workspace-toolbar-edge,72px)_minmax(0,1fr)_var(--workspace-toolbar-edge,72px)]" : "flex")}>
      <div className="flex min-w-0 items-center gap-1">{start}</div>
      <div className={cn("min-w-0",balanced ? "text-center" : "flex flex-1 items-center gap-2")}>{center}</div>
      <div className="ml-auto flex min-w-0 items-center justify-end gap-1">{end}
        {!!overflow.length && <Popover><PopoverTrigger asChild><Button variant="ghost" size="toolbar" aria-label={t("shell.more")}><MoreHorizontal className="size-4" /></Button></PopoverTrigger>
          <PopoverContent align="end" className="flex w-auto max-w-[calc(100vw-2rem)] flex-col gap-2 p-2">{overflow}</PopoverContent>
        </Popover>}
      </div>
    </div>}
    {!!secondary.length && <div className="mt-2 flex min-w-0 flex-wrap items-center gap-2">{secondary}</div>}
  </div>;
}
