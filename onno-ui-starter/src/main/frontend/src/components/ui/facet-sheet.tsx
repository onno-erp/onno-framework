import { useEffect, useSyncExternalStore, type ReactNode } from "react";
import { X } from "lucide-react";
import { Drawer } from "vaul";
import { useMessages } from "@/providers/messages-provider";

/**
 * Responsive overlay primitives shared by every faceted control (list filter bar, dashboard time
 * range): phones get a bottom sheet, tablets get a centered modal, and laptop/desktop widths keep
 * anchored popovers/dropdowns.
 */

export type FacetOverlay = "sheet" | "modal" | "popover";

const MOBILE_QUERY = "(max-width: 767px)";
const TABLET_QUERY = "(min-width: 768px) and (max-width: 1023px)";
const OVERLAY_QUERIES = [MOBILE_QUERY, TABLET_QUERY];

function subscribeOverlay(cb: () => void) {
  if (typeof window === "undefined" || !window.matchMedia) return () => {};
  const mqs = OVERLAY_QUERIES.map((query) => window.matchMedia(query));
  mqs.forEach((mq) => mq.addEventListener("change", cb));
  return () => mqs.forEach((mq) => mq.removeEventListener("change", cb));
}

function overlaySnapshot(): FacetOverlay {
  if (typeof window === "undefined" || !window.matchMedia) return "popover";
  if (window.matchMedia(MOBILE_QUERY).matches) return "sheet";
  if (window.matchMedia(TABLET_QUERY).matches) return "modal";
  return "popover";
}

export function useFacetOverlay(): FacetOverlay {
  return useSyncExternalStore(subscribeOverlay, overlaySnapshot, () => "popover");
}

export function useTouchLayout(): boolean {
  return useFacetOverlay() !== "popover";
}

/**
 * The framed overlay every facet shares on compact layouts. Vaul owns the phone sheet's
 * drag/momentum behavior; tablet gets the same content in a centered modal.
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
  const t = useMessages();
  const overlay = useFacetOverlay();
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
  if (overlay === "modal") {
    return (
      <div className="fixed inset-0 z-50 flex items-center justify-center p-6" role="dialog" aria-modal="true">
        <button type="button" aria-label={t("action.close")} className="absolute inset-0 bg-black/50" onClick={onClose} />
        <div className="relative flex max-h-[82dvh] w-full max-w-xl flex-col overflow-hidden rounded-card border bg-popover text-popover-foreground shadow-md outline-none">
          <div className="flex shrink-0 items-center justify-between border-b px-4 py-3">
            <span className="text-sm font-medium">{label}</span>
            <button
              type="button"
              aria-label={t("action.close")}
              onClick={onClose}
              className="grid size-8 place-items-center rounded-md text-muted-foreground transition hover:bg-foreground/10"
            >
              <X className="size-4" />
            </button>
          </div>
          <div className="min-h-0 flex-1 overflow-y-auto">{children}</div>
          {footer ? <div className="flex shrink-0 gap-2 border-t px-4 py-3">{footer}</div> : null}
        </div>
      </div>
    );
  }
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
              aria-label={t("action.close")}
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
