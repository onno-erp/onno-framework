import { createContext, useContext } from "react";

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
