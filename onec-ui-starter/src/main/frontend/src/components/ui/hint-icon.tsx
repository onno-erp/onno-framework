import { HelpCircle } from "lucide-react";
import { Tooltip, TooltipContent, TooltipProvider, TooltipTrigger } from "@/components/ui/tooltip";
import { cn } from "@/lib/utils";

/**
 * A muted "?" glyph that reveals help text in a tooltip on hover or keyboard focus. Used next to
 * field labels, list column headers, and widget titles to surface an author-supplied hint. Renders
 * nothing when {@code text} is blank, so callers can pass an optional hint unconditionally.
 *
 * <p>It carries its own {@link TooltipProvider} so it works wherever it's mounted — inside a React
 * island, a DivKit portal, or the form — without depending on a provider higher in the tree.</p>
 */
export function HintIcon({
  text,
  size = 14,
  color,
  className,
}: {
  text?: string | null;
  size?: number;
  color?: string;
  className?: string;
}) {
  if (!text || !text.trim()) return null;
  return (
    <TooltipProvider delayDuration={150}>
      <Tooltip>
        <TooltipTrigger asChild>
          <button
            type="button"
            aria-label={text}
            // A help affordance, not a form action: never submit, never tab-trap focus styling away.
            // pointer-events-auto: when portaled into a DivKit detail surface the icon lives under a
            // wrapper chain that DivKit paints pointer-events:none (so taps fall through to row
            // actions); without re-enabling it here the hover/focus never reaches the trigger and the
            // tooltip never opens. Harmless on the React-island form, where events already flow.
            className={cn(
              "pointer-events-auto inline-flex shrink-0 cursor-help items-center justify-center rounded-full text-muted-foreground/70 outline-none transition-colors hover:text-foreground focus-visible:text-foreground focus-visible:ring-2 focus-visible:ring-ring",
              className
            )}
            style={color ? { color } : undefined}
            onClick={(e) => e.preventDefault()}
          >
            <HelpCircle size={size} aria-hidden="true" />
          </button>
        </TooltipTrigger>
        <TooltipContent>{text}</TooltipContent>
      </Tooltip>
    </TooltipProvider>
  );
}
