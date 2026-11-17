import { Component, type ErrorInfo, type ReactNode } from "react";

/**
 * Error boundary around each data-owning part of a page (a list, a form, a chart): a throwing
 * part — most likely an app's own component — degrades to a small labelled placeholder
 * instead of unwinding the shared React root and blanking the whole app.
 *
 * A class component, deliberately: React still has no hook equivalent of
 * {@code getDerivedStateFromError}/{@code componentDidCatch}. Plain Tailwind, no other
 * component imports.
 */
export class ErrorBoundary extends Component<
  { label?: string; children: ReactNode },
  { error: Error | null }
> {
  state: { error: Error | null } = { error: null };

  static getDerivedStateFromError(error: Error) {
    return { error };
  }

  componentDidCatch(error: Error, info: ErrorInfo) {
    console.error(
      `[onno] "${this.props.label ?? "unknown"}" failed to render`,
      error,
      info.componentStack
    );
  }

  render() {
    const { error } = this.state;
    if (!error) return this.props.children;
    // The "something is off here, but the page lives on" placeholder.
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
