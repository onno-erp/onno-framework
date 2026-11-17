import * as React from "react";
import {cleanup,fireEvent,render,screen} from "@testing-library/react";
import {afterEach,expect,it,vi} from "vitest";
vi.mock("@onno/ui",async()=>({...React,React,...await import("../src/components/ui/button"),...await import("./ui-text-mock")}));
vi.mock("../../onno-crm-starter/src/main/ui/ChannelLogo",()=>({ChannelLogo:()=>null}));
import {InboxChannelNavigation} from "../../onno-crm-starter/src/main/ui/InboxChannelNavigation";
afterEach(cleanup);
const channels=[{value:"EMAIL",label:"Email"},{value:"TELEGRAM",label:"Telegram"}];
const accounts=[{id:"a",channel:"EMAIL",label:"Studio email"},{id:"b",channel:"TELEGRAM",label:"Studio bot"}];
it("switches channels and clears a previously selected account",()=>{
  function Example(){const [channel,setChannel]=React.useState("EMAIL");const [account,setAccount]=React.useState("a");return <InboxChannelNavigation {...{channels,accounts,channel,account}} showChannels showAccounts onChannel={setChannel} onAccount={setAccount}/>;}
  render(<Example/>);
  expect(screen.getByRole("button",{name:"Studio email"})).toHaveAttribute("aria-pressed","true");
  expect(screen.queryByRole("button",{name:"Studio bot"})).toBeNull();
  fireEvent.click(screen.getByRole("button",{name:"Telegram"}));
  expect(screen.getByRole("button",{name:"Telegram"})).toHaveAttribute("aria-pressed","true");
  expect(screen.getByRole("button",{name:"All accounts"})).toHaveAttribute("aria-pressed","true");
  expect(screen.queryByRole("button",{name:"Studio email"})).toBeNull();
  fireEvent.click(screen.getByRole("button",{name:"Studio bot"}));
  expect(screen.getByRole("button",{name:"Studio bot"})).toHaveAttribute("aria-pressed","true");
  fireEvent.click(screen.getByRole("button",{name:"All channels"}));
  expect(screen.getByRole("button",{name:"Studio email"})).toBeVisible();
  expect(screen.getByRole("button",{name:"All accounts"})).toHaveAttribute("aria-pressed","true");
});
it("respects configured visibility and explains empty account groups",()=>{
  render(<InboxChannelNavigation channels={channels} accounts={[]} channel="" account="" showChannels={false} showAccounts onChannel={()=>{}} onAccount={()=>{}}/>);
  expect(screen.queryByRole("button",{name:"All channels"})).toBeNull();
  expect(screen.getByText("No accounts available for this channel.")).toBeVisible();
});
