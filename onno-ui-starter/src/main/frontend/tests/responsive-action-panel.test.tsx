import {cleanup, fireEvent, render, screen} from "@testing-library/react";
import {afterEach, expect, it, vi} from "vitest";
import {ResponsiveActionPanel} from "@/components/ui/responsive-action-panel";
afterEach(()=>{cleanup();vi.unstubAllGlobals();});
it("keeps mobile action sheets dismissible without a Done footer", async()=>{
  vi.stubGlobal("matchMedia",(q:string)=>({matches:q.includes("max-width: 767px"),addEventListener(){},removeEventListener(){}}));
  render(<ResponsiveActionPanel label="Tools" presentation="actions" trigger={<button>Open tools</button>}><button>Schedule call</button></ResponsiveActionPanel>);
  fireEvent.click(screen.getByRole("button",{name:"Open tools"}));
  expect(await screen.findByRole("button",{name:"Schedule call"})).toBeVisible();
  expect(screen.queryByRole("button",{name:"Done"})).toBeNull();
  fireEvent.click(screen.getByRole("button",{name:"Close"}));
  expect(screen.getByRole("button",{name:"Open tools"})).toHaveAttribute("aria-expanded","false");
});
