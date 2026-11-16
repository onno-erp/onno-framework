import {cleanup, fireEvent, render, screen} from "@testing-library/react";
import {afterEach, expect, it, vi} from "vitest";
import {channelSetups} from "../../onno-crm-channels-starter/src/main/ui/ConnectorSetup";
import type {CrmConnectionAccount} from "../../onno-crm-starter/src/main/ui/CrmConnections";
const Setup=channelSetups.component;
const account=(extra:Partial<CrmConnectionAccount>)=>({key:"telegram-business",label:"Telegram Business",channel:"TELEGRAM",state:"DRAFT",account:"",description:"",actions:["connect"],scope:"PERSONAL",...extra}) as CrmConnectionAccount;

afterEach(()=>{cleanup();vi.unstubAllGlobals();});
it("opens a one-use business authorization link without sending a message or using the ordinary bot endpoint",async()=>{
 const request=vi.fn(async(_input:RequestInfo|URL)=>new Response(JSON.stringify({url:"https://t.me/example_bot?start=crm_proof"})));
 vi.stubGlobal("fetch",request);
 render(<Setup account={account({accountId:"account-1",account:"@example_bot"})} canManage refresh={async()=>{}}/>);
 fireEvent.click(await screen.findByRole("button",{name:"Connect account"}));
 const link=await screen.findByRole("link",{name:"Open Telegram and press Start"});
 expect(link).toHaveAttribute("href","https://t.me/example_bot?start=crm_proof");
 expect(request).toHaveBeenCalledTimes(1);
 expect(request.mock.calls[0][0]).toBe("/api/crm/telegram/business/account-1/authorize");
 expect(screen.getByText(/select which chats it can access/)).toBeVisible();
});
it("offers the bundled channels' providers only",()=>{
 expect(channelSetups.providers).toEqual(["gmail","telegram","telegram-business","instagram","whatsapp"]);
});
it("does not let a read-only viewer start business onboarding",async()=>{
 render(<Setup account={account({accountId:"account-1"})} canManage={false} refresh={async()=>{}}/>);
 expect(await screen.findByRole("button",{name:"Connect account"})).toBeDisabled();
});
