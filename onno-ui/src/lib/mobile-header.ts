/** Mounts the mobile back-button slot inside a page header (`data-onno-page-header`). */
export function mountMobileHeaderSlot(node: HTMLElement): { slot: HTMLElement; dispose: () => void } {
  const padding = node.style.paddingLeft;
  const position = node.style.position;
  const minHeight = node.style.minHeight;
  const inset = parseFloat(getComputedStyle(node).paddingLeft) || 0;
  node.style.position = "relative";
  node.style.paddingLeft = `${inset + 52}px`;
  node.style.minHeight = "44px";
  const slot = document.createElement("div");
  slot.style.cssText = `position:absolute;left:${inset}px;top:50%;transform:translateY(-50%);width:44px;height:44px`;
  node.prepend(slot);
  return { slot, dispose: () => {
    slot.remove();
    node.style.paddingLeft = padding;
    node.style.position = position;
    node.style.minHeight = minHeight;
  }};
}
