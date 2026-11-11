import { createContext, useCallback, useContext, useEffect, useRef } from "react";

/**
 * Portal target for overlays opened from inside DialogShell.
 *
 * Radix menus/popovers must stay inside React Aria's modal subtree; portalling them to document.body
 * makes the focus trap immediately close them as an outside interaction.
 */
export const DialogPortalContext = createContext<HTMLElement | null>(null);

export function useDialogPortal(): HTMLElement | null {
  return useContext(DialogPortalContext);
}

/**
 * `shouldCloseOnInteractOutside` for a React Aria modal whose selects and popovers are Radix
 * overlays portalled into `container`.
 *
 * React Aria does not know about those overlays, so a press outside an open one would close the
 * popup and the whole modal at once. An open Radix Select also sets `pointer-events: none` on the
 * body, so even a tap on the modal itself lands on `<html>` and counts as outside. The guard notes
 * whether a popup was open when the press began (window capture runs before both libraries'
 * document listeners, and Radix may already have closed the popup by the time React Aria decides),
 * so that press only closes the popup; the next press outside closes the modal.
 */
export function useOutsidePressGuard(open: boolean, container: HTMLElement | null): () => boolean {
  const popupOpenAtPress = useRef(false);
  useEffect(() => {
    if (!open || !container) return;
    const note = () => { popupOpenAtPress.current = !!container.querySelector('[data-state="open"]'); };
    window.addEventListener("pointerdown", note, true);
    return () => window.removeEventListener("pointerdown", note, true);
  }, [open, container]);
  return useCallback(() => !popupOpenAtPress.current, []);
}
