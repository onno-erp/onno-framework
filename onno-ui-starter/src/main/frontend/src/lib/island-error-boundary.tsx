import { Component, type ErrorInfo, type ReactNode } from "react";

/**
 * Error boundary for React islands portaled into DivKit-owned host elements. Every
 * bridge (widgets, lists, forms, icons, …) wraps its portaled node in this boundary so
 * a throwing island — most likely a consumer-authored widget registered via {@code
 * registerWidget} — degrades to a small labelled placeholder instead of unwinding the
 * shared React root and blanking the whole app.
 *
 * A class component, deliberately: React still has no hook equivalent of
 * {@code getDerivedStateFromError}/{@code componentDidCatch}. Kept dependency-free
 * (plain Tailwind, no shadcn imports) so every bridge can pull it in cheaply.
 */
export class IslandErrorBoundary extends Component<
  { label?: string; children: ReactNode },
  { error: Error | null }
> {
  state: { error: Error | null } = { error: null };

  static getDerivedStateFromError(error: Error) {
    return { error };
  }

  componentDidCatch(error: Error, info: ErrorInfo) {
    console.error(
      `[onno] island "${this.props.label ?? "unknown"}" failed to render`,
      error,
      info.componentStack
    );
  }

  render() {
    const { error } = this.state;
    if (!error) return this.props.children;
    // Styled to match UnknownWidget in widget-bridge.tsx — the "something is off
    // here, but the page lives on" placeholder idiom.
    return (
      <div className="rounded-panel border border-dashed border-border p-4 text-xs text-muted-foreground">
        <div className="font-medium text-foreground">
          {this.props.label ?? "Component"} failed to render
        </div>
        <div className="mt-1">
          <code className="font-mono">{error.message || String(error)}</code>
        </div>
      </div>
    );
  }
}
