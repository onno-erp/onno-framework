import type { ComponentType, ReactNode } from "react";
import { cn } from "@/lib/utils";

export type SegmentedOption<T extends string> = {
  value: T;
  label?: ReactNode;
  icon?: ComponentType<{ className?: string }>;
  /** Accessible name (and tooltip) — required when the label is hidden. */
  ariaLabel?: string;
};

/**
 * The one segmented control: a horizontal row of mutually-exclusive options where exactly one is
 * active. Used for every view/mode switcher (chart controls, table/map toggle, register views,
 * All/Unread filter, calendar month/week). Not for tool palettes (a tool can be deselected) or
 * document tab strips — those are different interactions.
 */
export function Segmented<T extends string>({
  value,
  options,
  onChange,
  size = "md",
  className,
}: {
  value: T;
  options: SegmentedOption<T>[];
  onChange: (v: T) => void;
  /** "sm" for dense in-card control rows, "md" for toolbars (h-8, matches inputs/buttons). */
  size?: "sm" | "md";
  className?: string;
}) {
  return (
    <div
      className={cn(
        "inline-flex shrink-0 items-center rounded-field border border-input bg-muted p-0.5",
        size === "md" && "h-8",
        className
      )}
    >
      {options.map((o) => {
        const Icon = o.icon;
        const on = o.value === value;
        return (
          <button
            key={o.value}
            type="button"
            onClick={() => onChange(o.value)}
            aria-pressed={on}
            aria-label={o.ariaLabel}
            title={o.ariaLabel}
            className={cn(
              // Concentric corners: the segment radius is the container's minus the 2px (p-0.5)
              // inset, so the active pill's arc runs parallel to the container's at every tier of
              // the --radius-field token (a bare rounded-field here reads pinched at the ends).
              "inline-flex items-center gap-1.5 rounded-[max(calc(var(--radius-field)-2px),2px)] font-medium transition-colors",
              size === "sm" ? "px-2 py-0.5 text-[11px] leading-none" : "h-7 px-2.5 text-xs",
              on ? "bg-background text-foreground shadow-sm" : "text-muted-foreground hover:text-foreground"
            )}
          >
            {Icon ? <Icon className="size-4" /> : null}
            {o.label}
          </button>
        );
      })}
    </div>
  );
}
