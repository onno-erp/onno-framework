import { act, cleanup, fireEvent, render, screen, within } from "@testing-library/react";
import { afterEach, beforeEach, expect, it, vi } from "vitest";
import { CrmConnections } from "../../onno-crm-starter/src/main/ui/CrmConnections";
class ResizeObserverMock { observe() {} unobserve() {} disconnect() {} }
beforeEach(()=>{vi.stubGlobal("ResizeObserver",ResizeObserverMock);});
afterEach(()=>{cleanup();vi.unstubAllGlobals();vi.restoreAllMocks();});
const providers=[{key:"gmail",label:"Gmail",channel:"EMAIL",iconUrl:"",description:"Email",enabled:true,scopes:["SHARED","PERSONAL"],setupHint:""},{key:"telegram",label:"Telegram",channel:"TELEGRAM",iconUrl:"",description:"Bot conversations",enabled:false,scopes:[],setupHint:"An administrator must configure this provider."}];
const response=(data:unknown)=>new Response(JSON.stringify(data));
function fixture(settings:Record<string,unknown>) {
 vi.stubGlobal("fetch",vi.fn(async(input:RequestInfo|URL)=>String(input).endsWith("/connection-accounts")?response({providers,accounts:[],canManageShared:false,canManagePersonal:true,...settings}):response({channels:[],canManage:false})));
}
it("shows skeletons instead of an empty state before the request settles",async()=>{
 const pending:Array<(r:Response)=>void>=[];
 vi.stubGlobal("fetch",vi.fn(()=>new Promise<Response>(resolve=>pending.push(resolve))));
 const {unmount}=render(<CrmConnections />);
 expect(screen.getByRole("status",{name:"Loading connections"})).toHaveAttribute("aria-busy","true");
 expect(screen.queryByText("No accounts here yet")).not.toBeInTheDocument();
 await act(async()=>{unmount();pending.forEach(resolve=>resolve(response({providers:[],accounts:[],channels:[]})));});
});
it("keeps shared accounts read-only and offers personal onboarding with inactive provider guidance",async()=>{
 fixture({});render(<CrmConnections sharedLabel="Team accounts" />);
 await screen.findByText("Gmail");expect(screen.getByRole("button",{name:"Add account"})).toBeDisabled();
 fireEvent.click(screen.getByRole("button",{name:"My accounts"}));
 expect(screen.getByRole("button",{name:"Add account"})).toBeEnabled();
 expect(screen.getByRole("button",{name:"Shared accounts only"})).toBeDisabled();
 const card=screen.getByText("Telegram").closest("section")!;
 fireEvent.click(within(card).getByRole("button",{name:"Setup details"}));
 expect(screen.getByText("An administrator must configure this provider.")).toBeVisible();
 fireEvent.click(screen.getByRole("button",{name:"Add account"}));
 expect(screen.getByLabelText("Account name")).toHaveValue("Gmail");
});
it("honors host provider and scope configuration",async()=>{
 fixture({});render(<CrmConnections scopes={["personal"]} providers={["gmail"]} />);
 await screen.findByText("Gmail");expect(screen.queryByText("Telegram")).not.toBeInTheDocument();
 expect(screen.queryByRole("button",{name:"Team accounts"})).not.toBeInTheDocument();
 expect(screen.getByRole("button",{name:"Add account"})).toBeEnabled();
});
it("renders a retry state without a false empty state on failure",async()=>{
 vi.stubGlobal("fetch",vi.fn(async()=>new Response("{}",{status:500})));
 render(<CrmConnections />);
 await screen.findByRole("alert");expect(screen.getByRole("button",{name:"Retry"})).toBeEnabled();
 expect(screen.queryByText("No accounts here yet")).not.toBeInTheDocument();
});
