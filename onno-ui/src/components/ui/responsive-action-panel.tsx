import { useState, cloneElement, type ReactElement, type ReactNode } from "react";
import { Popover, PopoverTrigger, PopoverContent } from "./popover";
import { FacetSheet, SheetDoneButton, useFacetOverlay } from "./facet-sheet";
import { useMessages } from "../../providers/messages-provider";

/** One action surface: anchored on desktop, modal on tablet, sheet on phone. */
export function ResponsiveActionPanel({label, trigger, children, open: controlledOpen, onOpenChange, align = "end", presentation = "controls"}: {
  presentation?: "controls" | "actions";
  // eslint-disable-next-line @typescript-eslint/no-explicit-any -- any element that accepts trigger props
  label: string; trigger: ReactElement<any>; children: ReactNode;
  open?: boolean; onOpenChange?: (open: boolean) => void; align?: "start" | "center" | "end";
}) {
  const [localOpen, setLocalOpen] = useState(false);
  const open = controlledOpen ?? localOpen;
  const setOpen = (next: boolean) => {setLocalOpen(next);onOpenChange?.(next);};
  const overlay = useFacetOverlay();
  const t = useMessages();
  const content = presentation === "actions" ? <div className="flex min-w-56 flex-col gap-1 [&>button]:h-11 [&>button]:w-full [&>button]:justify-start [&>button]:gap-3 [&>button]:rounded-field [&>button]:border-0 [&>button]:bg-transparent [&>button]:px-3 [&>button]:text-sm [&>button]:text-foreground [&>button:hover]:bg-muted [&>button_svg]:size-5">{children}</div> : children;
  if (overlay === "popover") return <Popover open={open} onOpenChange={setOpen}>
    <PopoverTrigger asChild>{trigger}</PopoverTrigger>
    <PopoverContent align={align} aria-label={label} className="flex w-auto max-w-[calc(100vw-2rem)] flex-col gap-2 p-2">{content}</PopoverContent>
  </Popover>;
  return <>
    {cloneElement(trigger, {onClick:()=>setOpen(true), "aria-expanded":open, "aria-haspopup":"dialog"})}
    <FacetSheet label={label} open={open} onClose={()=>setOpen(false)} footer={presentation === "controls" ? <SheetDoneButton onClick={()=>setOpen(false)}>{t("list.done")}</SheetDoneButton> : undefined}>
      <div className="flex flex-col gap-2 p-3">{content}</div>
    </FacetSheet>
  </>;
}
