import {cleanup, fireEvent, render, screen} from "@testing-library/react";
import {afterEach, expect, it, vi} from "vitest";
vi.mock("@onno/widget-sdk", async importOriginal => {await import("@/lib/plugin-host");return importOriginal();});
import {ExtensionSlot} from "@/lib/ui-extensions";
import "../../../../../onno-crm-channels-starter/src/main/widgets/ConnectorSetup";

afterEach(()=>{cleanup();vi.unstubAllGlobals();});
it("opens a one-use business authorization link without sending a message or using the ordinary bot endpoint",async()=>{
 const request=vi.fn(async(_input:RequestInfo|URL)=>new Response(JSON.stringify({url:"https://t.me/example_bot?start=crm_proof"})));
 vi.stubGlobal("fetch",request);
 render(<ExtensionSlot name="crm.channel.settings" context={{surface:"crm-channel",record:{key:"telegram-business",accountId:"account-1",account:"@example_bot",actions:["connect"]},permissions:{canManage:true}}}/>);
 fireEvent.click(await screen.findByRole("button",{name:"Connect account"}));
 const link=await screen.findByRole("link",{name:"Open Telegram and press Start"});
 expect(link).toHaveAttribute("href","https://t.me/example_bot?start=crm_proof");
 expect(request).toHaveBeenCalledTimes(1);
 expect(request.mock.calls[0][0]).toBe("/api/crm/telegram/business/account-1/authorize");
 expect(screen.getByText(/select which chats it can access/)).toBeVisible();
});
it("does not let a read-only viewer start business onboarding",async()=>{
 render(<ExtensionSlot name="crm.channel.settings" context={{surface:"crm-channel",record:{key:"telegram-business",accountId:"account-1",actions:["connect"]},permissions:{canManage:false}}}/>);
 expect(await screen.findByRole("button",{name:"Connect account"})).toBeDisabled();
});
