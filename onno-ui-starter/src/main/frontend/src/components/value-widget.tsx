import type { MouseEvent } from "react";
import type { DashboardWidgetMeta } from "@/lib/types";
import { withBasePath } from "@/lib/base-path";
import { AnimatedNumber } from "@/components/ui/animated-number";
import { Card, CardContent } from "@/components/ui/card";
import { HintIcon } from "@/components/ui/hint-icon";

/** Server-resolved count/metric tile, rendered as a React island so live updates can animate. */
export function ValueWidget({ widget }: { widget: DashboardWidgetMeta }) {
  const card = (
    <Card className="h-full">
      <CardContent className="p-4">
        <AnimatedNumber
          value={widget.resolvedValue ?? "—"}
          className="text-2xl font-medium leading-none tabular-nums text-foreground"
        />
        <div className="mt-1 flex items-center gap-1.5">
          <span className="text-[13px] text-muted-foreground">{widget.title}</span>
          <HintIcon text={widget.hint} size={14} />
        </div>
      </CardContent>
    </Card>
  );

  if (!widget.href) return card;
  const open = (event: MouseEvent<HTMLAnchorElement>) => {
    if (
      event.button !== 0
      || event.metaKey
      || event.ctrlKey
      || event.shiftKey
      || event.altKey
    ) {
      return;
    }
    event.preventDefault();
    window.dispatchEvent(new CustomEvent("onno:action", {
      detail: `onno://${widget.href!.replace(/^\/+/, "")}`,
    }));
  };
  return (
    <a className="block h-full" href={withBasePath(widget.href)} onClick={open}>
      {card}
    </a>
  );
}
