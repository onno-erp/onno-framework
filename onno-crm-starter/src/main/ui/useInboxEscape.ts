import { useEffect, type RefObject } from "react";

/** Consume Escape within this inbox's tab, after menus have had a chance to dismiss. */
export function useInboxEscape(root: RefObject<HTMLElement | null>, closeChat: (() => void) | null, closeFolder: (() => void) | null = null) {
  const dismiss = closeChat ?? closeFolder;
  useEffect(() => {
    if (!dismiss || !root.current) return;
    const element = root.current;
    const scope = element.closest("[data-workspace-pane-id]") ?? element;
    let lastInteractionInside = false;
    const remember = (event: Event) => {
      lastInteractionInside = event.target instanceof Node && scope.contains(event.target);
    };
    const onKey = (event: KeyboardEvent) => {
      if (event.key !== "Escape" || event.defaultPrevented || event.isComposing) return;
      if (getComputedStyle(element).visibility === "hidden" || !element.getClientRects().length) return;
      const target = event.target;
      if (!(target instanceof Node) || (!scope.contains(target) && !(target === document.body && lastInteractionInside))) return;
      // Portalled menus/dialogs own Escape even when focus remains on their trigger.
      const layers = document.querySelectorAll('[role="menu"], [role="dialog"], [role="alertdialog"], [data-radix-popper-content-wrapper]');
      if (Array.from(layers).some(layer => layer.getAttribute("data-state") !== "closed"
        && getComputedStyle(layer).visibility !== "hidden" && layer.getClientRects().length > 0)) return;
      event.preventDefault();
      if (!event.repeat) dismiss();
    };
    document.addEventListener("pointerdown", remember);
    document.addEventListener("focusin", remember);
    document.addEventListener("keydown", onKey);
    return () => {
      document.removeEventListener("pointerdown", remember);
      document.removeEventListener("focusin", remember);
      document.removeEventListener("keydown", onKey);
    };
  }, [root, dismiss]);
}
