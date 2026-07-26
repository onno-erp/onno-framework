import { useLayoutEffect, useState } from "react";
import { cn } from "@/lib/utils";

/**
 * A formatted number whose characters re-enter whenever its value changes. Formatting stays with
 * the caller (currency, locale, units); this primitive only owns the transitions.dev digit motion.
 */
export function AnimatedNumber({
  value,
  className,
}: {
  value: string;
  className?: string;
}) {
  const [playing, setPlaying] = useState(true);
  const characters = Array.from(value);

  useLayoutEffect(() => {
    setPlaying(false);
    let secondFrame = 0;
    const firstFrame = window.requestAnimationFrame(() => {
      secondFrame = window.requestAnimationFrame(() => setPlaying(true));
    });
    return () => {
      window.cancelAnimationFrame(firstFrame);
      window.cancelAnimationFrame(secondFrame);
    };
  }, [value]);

  return (
    <span
      className={cn("t-digit-group", playing && "is-animating", className)}
      aria-label={value}
    >
      {characters.map((character, index) => {
        const fromEnd = characters.length - index;
        return (
          <span
            // The value prefix remounts changed characters, while the replay class also handles
            // same-width/same-position replacements cleanly.
            key={`${value}-${index}`}
            className="t-digit"
            data-stagger={fromEnd === 2 ? "1" : fromEnd === 1 ? "2" : undefined}
            aria-hidden="true"
          >
            {character}
          </span>
        );
      })}
    </span>
  );
}
