import { useCallback, useLayoutEffect, useRef } from "react";
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
  const pillRef = useRef<HTMLSpanElement | null>(null);
  const optionRefs = useRef(new Map<T, HTMLButtonElement>());
  const valueRef = useRef(value);
  const positionedRef = useRef(false);
  valueRef.current = value;

  const movePill = useCallback((next: T, animate: boolean) => {
    const option = optionRefs.current.get(next);
    const pill = pillRef.current;
    if (!option || !pill) return;
    const reduceMotion = window.matchMedia?.("(prefers-reduced-motion: reduce)").matches;
    if (!animate || reduceMotion) {
      const previousTransition = pill.style.transition;
      pill.style.transition = "none";
      pill.style.transform = `translateX(${option.offsetLeft}px)`;
      pill.style.width = `${option.offsetWidth}px`;
      void pill.offsetWidth;
      pill.style.transition = previousTransition;
      return;
    }
    pill.style.transform = `translateX(${option.offsetLeft}px)`;
    pill.style.width = `${option.offsetWidth}px`;
  }, []);

  // The first position must snap into place; later controlled value changes animate. Keeping the
  // selected value controlled means SDK consumers retain exactly the same public API.
  useLayoutEffect(() => {
    const frame = window.requestAnimationFrame(() => {
      movePill(value, positionedRef.current);
      positionedRef.current = true;
    });
    return () => window.cancelAnimationFrame(frame);
  }, [movePill, value]);

  // Labels can change width independently of selection (for example "All 12" → "All 13").
  // Re-snap on geometry changes so the pill never drifts or performs an incidental resize tween.
  useLayoutEffect(() => {
    const snap = () => movePill(valueRef.current, false);
    window.addEventListener("resize", snap);
    if (typeof ResizeObserver === "undefined") {
      return () => window.removeEventListener("resize", snap);
    }
    const observer = new ResizeObserver(snap);
    for (const option of optionRefs.current.values()) observer.observe(option);
    return () => {
      observer.disconnect();
      window.removeEventListener("resize", snap);
    };
  }, [movePill, options.length]);

  return (
    <div
      className={cn(
        "t-tabs relative inline-flex shrink-0 items-center rounded-field border border-input bg-muted p-0.5",
        size === "md" && "h-8",
        className
      )}
    >
      <span
        ref={pillRef}
        className="t-tabs-pill rounded-[max(calc(var(--radius-field)-2px),2px)] bg-background shadow-sm"
        aria-hidden="true"
      />
      {options.map((o) => {
        const Icon = o.icon;
        const on = o.value === value;
        return (
          <button
            key={o.value}
            ref={(element) => {
              if (element) optionRefs.current.set(o.value, element);
              else optionRefs.current.delete(o.value);
            }}
            type="button"
            onClick={() => {
              movePill(o.value, true);
              onChange(o.value);
            }}
            aria-pressed={on}
            aria-label={o.ariaLabel}
            title={o.ariaLabel}
            className={cn(
              // Concentric corners: the segment radius is the container's minus the 2px (p-0.5)
              // inset, so the active pill's arc runs parallel to the container's at every tier of
              // the --radius-field token (a bare rounded-field here reads pinched at the ends).
              "t-tab relative z-[1] inline-flex items-center gap-1.5 rounded-[max(calc(var(--radius-field)-2px),2px)] bg-transparent font-medium",
              size === "sm" ? "px-2 py-0.5 text-[11px] leading-none" : "h-7 px-2.5 text-xs",
              on ? "text-foreground" : "text-muted-foreground hover:text-foreground"
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
