import {cleanup, fireEvent, render, screen} from "@testing-library/react";
import {afterEach, expect, it, vi} from "vitest";
import {ResponsiveActionPanel} from "@/components/ui/responsive-action-panel";
import {stubScreenWidth} from "./viewport-fixtures";
afterEach(()=>{cleanup();vi.unstubAllGlobals();});
it("keeps mobile action sheets dismissible without a Done footer", async()=>{
  stubScreenWidth(390);
  render(<ResponsiveActionPanel label="Tools" presentation="actions" trigger={<button>Open tools</button>}><button>Schedule call</button></ResponsiveActionPanel>);
  fireEvent.click(screen.getByRole("button",{name:"Open tools"}));
  expect(await screen.findByRole("button",{name:"Schedule call"})).toBeVisible();
  expect(screen.queryByRole("button",{name:"Done"})).toBeNull();
  fireEvent.click(screen.getByRole("button",{name:"Close"}));
  expect(screen.getByRole("button",{name:"Open tools"})).toHaveAttribute("aria-expanded","false");
});

it("keeps a child form inside the sheet while editing and submitting", async()=>{
  stubScreenWidth(390);
  const {Popover,PopoverTrigger,PopoverContent} = await import("@/components/ui/popover");
  const saved = vi.fn();
  render(<ResponsiveActionPanel label="Tools" presentation="actions" trigger={<button>Open tools</button>}>
    <Popover><PopoverTrigger asChild><button>Log activity</button></PopoverTrigger>
      <PopoverContent aria-label="Activity form"><form onSubmit={e=>{e.preventDefault();saved();}}><input aria-label="Summary"/><button type="submit">Save</button></form></PopoverContent>
    </Popover>
  </ResponsiveActionPanel>);
  fireEvent.click(screen.getByRole("button",{name:"Open tools"}));
  fireEvent.click(await screen.findByRole("button",{name:"Log activity"}));
  const form = await screen.findByRole("dialog",{name:"Activity form"});
  expect(form.closest('[data-vaul-drawer]')).not.toBeNull();
  fireEvent.change(screen.getByRole("textbox",{name:"Summary"}),{target:{value:"Follow-up call"}});
  fireEvent.click(screen.getByRole("button",{name:"Save"}));
  expect(saved).toHaveBeenCalledOnce();
  expect(screen.getByRole("button",{name:"Open tools",hidden:true})).toHaveAttribute("aria-expanded","true");
});
