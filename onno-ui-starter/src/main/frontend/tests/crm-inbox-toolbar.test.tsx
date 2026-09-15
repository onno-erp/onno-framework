import * as React from "react";
import {cleanup,fireEvent,render,screen} from "@testing-library/react";
import {afterEach,expect,it,vi} from "vitest";
vi.mock("@onno/widget-sdk",async()=>({
  ...React,
  ...await import("../src/lib/ui-extensions"),
  ...await import("../src/components/ui/button"),
  ...(await import("./widget-sdk-text")),
}));
import {ExtensionSlot} from "../src/lib/ui-extensions";
import type {ExtensionContext} from "../../../../../onno-widget-sdk/src/extensions";
// Imported for its registration: the CRM contributes its own grouping switch through the outlet.
import "../../../../../onno-crm-starter/src/main/widgets/InboxGrouping";
afterEach(cleanup);

function toolbar(record:Record<string,unknown>,view:ExtensionContext["view"]){
  return render(<ExtensionSlot name="crm.inbox.toolbar" context={{surface:"crm-inbox",permissions:{},record,view}} />);
}
const bag=(state:Record<string,string|undefined>)=>({get:(key:string)=>state[key],set:vi.fn()});

it("offers the grouping switch only where there is folding to drop",()=>{
  toolbar({folders:0,grouping:"true"},bag({}));
  expect(screen.queryByRole("button",{name:"Everything"})).toBeNull();
  cleanup();
  toolbar({folders:3,grouping:"false"},bag({}));
  expect(screen.queryByRole("button",{name:"Everything"})).toBeNull();
  cleanup();
  toolbar({folders:3,grouping:"true"},bag({}));
  expect(screen.getByRole("button",{name:"Folders"})).toHaveAttribute("aria-pressed","true");
});

it("writes the reading it wants into the view rather than into the list",()=>{
  const view=bag({});
  toolbar({folders:3,grouping:"true"},view);
  fireEvent.click(screen.getByRole("button",{name:"Everything"}));
  expect(view.set).toHaveBeenCalledWith("grouping","flat");
});

it("reads the current grouping back from the view",()=>{
  toolbar({folders:3,grouping:"true"},bag({grouping:"flat"}));
  expect(screen.getByRole("button",{name:"Everything"})).toHaveAttribute("aria-pressed","true");
  expect(screen.getByRole("button",{name:"Folders"})).toHaveAttribute("aria-pressed","false");
});

it("lets an application replace the contribution under the same id",async()=>{
  const {registerExtension}=await import("../src/lib/ui-extensions");
  registerExtension({id:"onno.crm.inbox.grouping",slot:"crm.inbox.toolbar",
    component:()=><button type="button">Ours</button>});
  toolbar({folders:3,grouping:"true"},bag({}));
  expect(screen.getByRole("button",{name:"Ours"})).toBeTruthy();
  expect(screen.queryByRole("button",{name:"Everything"})).toBeNull();
});
