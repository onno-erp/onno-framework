import { useState } from "react";
import { fireEvent, render, screen, cleanup } from "@testing-library/react";
import { afterEach, expect, it } from "vitest";
import { ContextMenuContent, ContextMenuItem, ContextMenuSub } from "../src/components/ui/context-menu";
afterEach(cleanup);
function Row({ name }: { name: string }) {
  const [open, setOpen] = useState(false);
  return <><button onContextMenu={event => { event.preventDefault(); setOpen(true); }}>{name}</button>
    <ContextMenuContent open={open} position={{x:10,y:10}} onOpenChange={setOpen}>
      <ContextMenuItem>{name} action</ContextMenuItem>
      <ContextMenuSub label="Tags"><ContextMenuItem>VIP</ContextMenuItem></ContextMenuSub>
    </ContextMenuContent></>;
}
it("opening another row menu replaces the previous root, while its submenu remains open", () => {
  render(<><Row name="First" /><Row name="Second" /></>);
  fireEvent.contextMenu(screen.getByRole("button", {name:"First"}));
  expect(screen.getByRole("menuitem", {name:"First action"})).toBeTruthy();
  fireEvent.contextMenu(screen.getByRole("button", {name:"Second"}));
  expect(screen.queryByRole("menuitem", {name:"First action"})).toBeNull();
  expect(screen.getAllByRole("menu")).toHaveLength(1);
  fireEvent.click(screen.getByRole("menuitem", {name:"Tags"}));
  expect(screen.getByRole("menuitem", {name:"VIP"})).toBeTruthy();
  expect(screen.getByRole("menuitem", {name:"Second action"})).toBeTruthy();
  fireEvent.keyDown(window, {key:"Escape"});
  expect(screen.queryByRole("menu")).toBeNull();
});
