import type { CSSProperties, ReactNode } from "react";
import { ErrorBoundary } from "../../lib/error-boundary";
import { cn } from "../../lib/utils";

/**
 * The DOM boundary of one data-owning component: an error boundary, so a throwing renderer — often
 * app code — never unwinds the page.
 */
export function Boundary({ label, children, className, style }: {
  label: string;
  children: ReactNode;
  className?: string;
  style?: CSSProperties;
}) {
  return (
    <div className={cn("min-w-0 w-full", className)} style={style}>
      <ErrorBoundary label={label}>{children}</ErrorBoundary>
    </div>
  );
}
