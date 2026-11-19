import * as React from "react";
import {cleanup,fireEvent,render,screen,waitFor} from "@testing-library/react";
import {afterEach,expect,it,vi} from "vitest";
vi.mock("@onno/ui",async()=>({
  ...React,
  ...await import("../src/lib/ui-extensions"),
  ...await import("../src/components/ui/context-menu"),
  ...await import("../src/components/ui/button"),
  ...await import("../src/components/ui/input"),
  EntityTagMenu:()=>null,
  Icon:({icon:Glyph}:{icon:React.ElementType})=><Glyph />,
  useCommandPresentations:()=>presentations,
  ...(await import("./ui-text-mock")),
}));
// The app's customization of CrmConversations: an icon for one command, another left out of the menu.
const presentations:Record<string,{icon?:React.ElementType;placement?:string}>={
  // Lucide icons are forwardRef objects; a plain function is a per-record icon chooser.
  "assign-to-me":{icon:React.forwardRef<SVGSVGElement>((_,ref)=><svg ref={ref} data-testid="assign-icon" />)},
  "state-closed":{placement:"hidden"},
};
vi.mock("../../onno-crm-starter/src/main/ui/CrmWorkspace",()=>({request:vi.fn().mockResolvedValue([
  {id:"assign-to-me",title:"Assign to me",enabled:true},
  {id:"state-waiting",title:"Waiting for client",enabled:true},
  {id:"state-closed",title:"Close conversation",enabled:true},
])}));
import {ContactRowMenu} from "../../onno-crm-starter/src/main/ui/ContactRowMenu";
afterEach(cleanup);
function openMenu(){fireEvent.contextMenu(screen.getByRole("button",{name:"Chat"}));fireEvent.click(screen.getByRole("menuitem",{name:"Move to group"}));}
it("creates a named group from the submenu and closes on success",async()=>{
  const change=vi.fn().mockResolvedValue(undefined);
  render(<ContactRowMenu id="customer" conversationId="chat" groups={[]} canEdit onOpen={()=>{}} onGroupChange={change}><button>Chat</button></ContactRowMenu>);
  openMenu();fireEvent.click(screen.getByRole("menuitem",{name:"New group…"}));
  fireEvent.change(screen.getByRole("textbox",{name:"Group name"}),{target:{value:"Partners"}});
  fireEvent.click(screen.getByRole("button",{name:"Create and move"}));
  await waitFor(()=>expect(change).toHaveBeenCalledWith({operation:"create",label:"Partners",conversationId:"chat"}));
  await waitFor(()=>expect(screen.queryByRole("menu")).toBeNull());
});
it("moves to existing groups and retains errors without pretending a move succeeded",async()=>{
  const change=vi.fn().mockRejectedValue(new Error("Group no longer exists"));
  render(<ContactRowMenu id="customer" conversationId="chat" groups={[{key:"one",label:"VIP",customerIds:[]},{key:"two",label:"Later",customerIds:[]}]} groupKey="one" canEdit onOpen={()=>{}} onGroupChange={change}><button>Chat</button></ContactRowMenu>);
  openMenu();fireEvent.click(screen.getByRole("menuitem",{name:"Later"}));
  expect(await screen.findByRole("alert")).toHaveTextContent("Group no longer exists");
  expect(change).toHaveBeenCalledWith({operation:"move",key:"two",conversationId:"chat"});
  change.mockResolvedValue(undefined);fireEvent.click(screen.getByRole("menuitem",{name:"Remove from group"}));
  await waitFor(()=>expect(change).toHaveBeenLastCalledWith({operation:"move",key:null,conversationId:"chat"}));
});
it("draws commands as the app customizes the conversation entity: its icons, without hidden ones",async()=>{
  render(<ContactRowMenu id="customer" conversationId="chat" workspaceKey="support" canEdit onOpen={()=>{}}><button>Chat</button></ContactRowMenu>);
  fireEvent.contextMenu(screen.getByRole("button",{name:"Chat"}));
  const assign=await screen.findByRole("menuitem",{name:"Assign to me"});
  expect(assign.querySelector('[data-testid="assign-icon"]')).not.toBeNull();
  expect(screen.getByRole("menuitem",{name:"Waiting for client"}).querySelector("[data-testid]")).toBeNull();
  expect(screen.queryByRole("menuitem",{name:"Close conversation"})).toBeNull();
});
