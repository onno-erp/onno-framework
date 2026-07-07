import { useEffect, useSyncExternalStore, type ReactNode } from "react";
import { X } from "lucide-react";
import { Drawer } from "vaul";

/**
 * Touch-layout primitives shared by every faceted control (list filter bar, dashboard time range):
 * a media-query hook that says "this is a phone/tablet", and the bottom-sheet shell those controls
 * render instead of an anchored popover a thumb can't comfortably reach.
 */

// Small screens OR any coarse (finger) pointer — phones and tablets both.
const TOUCH_QUERY = "(max-width: 767px), (pointer: coarse)";
function subscribeTouch(cb: () => void) {
  if (typeof window === "undefined" || !window.matchMedia) return () => {};
  const mq = window.matchMedia(TOUCH_QUERY);
  mq.addEventListener("change", cb);
  return () => mq.removeEventListener("change", cb);
}
export function useTouchLayout(): boolean {
  return useSyncExternalStore(
    subscribeTouch,
    () => (typeof window !== "undefined" && window.matchMedia ? window.matchMedia(TOUCH_QUERY).matches : false),
    () => false
  );
}

/**
 * The bottom sheet every facet shares on touch layouts. Vaul owns the drag/momentum behavior; this
 * wrapper keeps the visual structure common across filters, date pickers, and mobile selectors.
 */
export function FacetSheet({
  label,
  onClose,
  footer,
  children,
}: {
  label: string;
  onClose: () => void;
  footer?: ReactNode;
  children: ReactNode;
}) {
  const onOpenChange = (open: boolean) => {
    if (!open) onClose();
  };
  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      if (e.key === "Escape") {
        e.preventDefault();
        e.stopPropagation();
        e.stopImmediatePropagation();
        onClose();
      }
    };
    document.addEventListener("keydown", onKey, true);
    return () => document.removeEventListener("keydown", onKey, true);
  }, [onClose]);
  return (
    <Drawer.Root open onOpenChange={onOpenChange} direction="bottom" closeThreshold={0.32}>
      <Drawer.Portal>
        <Drawer.Overlay className="fixed inset-0 z-50 bg-black/50" />
        <Drawer.Content className="fixed inset-x-0 bottom-0 z-50 flex max-h-[85dvh] flex-col rounded-t-2xl border-t bg-popover pb-[max(env(safe-area-inset-bottom),0.75rem)] text-popover-foreground shadow-md outline-none">
          <Drawer.Handle className="mx-auto mt-2 h-1 w-10 shrink-0 rounded-full bg-border" />
          <Drawer.Title className="sr-only">{label}</Drawer.Title>
          <div className="flex shrink-0 items-center justify-between px-4 pt-2">
            <span className="text-sm font-medium">{label}</span>
            <button
              type="button"
              aria-label="Close"
              onClick={onClose}
              className="grid size-8 place-items-center rounded-md text-muted-foreground transition hover:bg-foreground/10"
            >
              <X className="size-4" />
            </button>
          </div>
          <div className="min-h-0 flex-1 overflow-y-auto">{children}</div>
          {footer ? <div className="flex shrink-0 gap-2 border-t px-4 pt-3">{footer}</div> : null}
        </Drawer.Content>
      </Drawer.Portal>
    </Drawer.Root>
  );
}

/** The sheet footer's quiet Clear button. */
export function SheetClearButton({ onClick, children }: { onClick: () => void; children: ReactNode }) {
  return (
    <button
      type="button"
      onClick={onClick}
      className="h-11 flex-1 rounded-field border border-input text-sm font-medium text-foreground transition-colors active:bg-accent"
    >
      {children}
    </button>
  );
}

/** The sheet footer's primary Done button. */
export function SheetDoneButton({ onClick, children }: { onClick: () => void; children: ReactNode }) {
  return (
    <button
      type="button"
      onClick={onClick}
      className="h-11 flex-1 rounded-field bg-primary text-sm font-medium text-primary-foreground transition-opacity active:opacity-90"
    >
      {children}
    </button>
  );
}
