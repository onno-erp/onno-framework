import { useLayoutEffect, useRef, useState } from "react";
import type { ReactNode } from "react";
import { cn } from "@/lib/utils";

/**
 * The transitions.dev badge wrapper. It stays mounted long enough to animate closed and briefly
 * toggles closed/open when the unread count increases so every newly received notification pops.
 */
export function NotificationBadgeMotion({
  count,
  className,
  children,
}: {
  count: number;
  className?: string;
  children: ReactNode;
}) {
  const previousCount = useRef(count);
  const [open, setOpen] = useState(count > 0);

  useLayoutEffect(() => {
    let frame = 0;
    if (count <= 0) {
      setOpen(false);
    } else if (previousCount.current > 0 && count > previousCount.current) {
      setOpen(false);
      frame = window.requestAnimationFrame(() => setOpen(true));
    } else {
      setOpen(true);
    }
    previousCount.current = count;
    return () => window.cancelAnimationFrame(frame);
  }, [count]);

  return (
    <span className={cn("t-badge", className)} data-open={open}>
      <span className="t-badge-dot">{children}</span>
    </span>
  );
}
