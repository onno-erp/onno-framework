import * as React from "react";
import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { afterEach, expect, it, vi } from "vitest";
vi.mock("@onno/widget-sdk", async () => ({
  ...(await import("./widget-sdk-text")),
  ...React,
  ...await import("../src/components/ui/button"),
  ...await import("../src/components/ui/input"),
  ...await import("../src/components/ui/textarea"),
  ...await import("../src/components/ui/popover"),
  ...await import("../src/components/ui/select"),
  ...await import("../src/components/ui/badge"),
  DatePicker: ({value,onChange,"aria-label":ariaLabel,isDisabled}:{value:string;onChange:(value:string)=>void;"aria-label":string;isDisabled:boolean}) => <input aria-label={ariaLabel} value={value} disabled={isDisabled} onChange={event=>onChange(event.target.value)} />,
  Label: "label", toast: {}, registerWidget: vi.fn(), useUiEvents: vi.fn(), ExtensionSlot: () => null,
}));
import { LogActivity } from "../../../../../onno-crm-starter/src/main/widgets/LogActivity";
afterEach(() => {cleanup(); vi.unstubAllGlobals();});
it("stores the phone summary and recording as internal activity, and rejects unsafe links", async () => {
  const fetch = vi.fn().mockResolvedValue({ok:true,json:async()=>({id:"event"})});
  vi.stubGlobal("fetch",fetch);
  const onSaved=vi.fn().mockResolvedValue(undefined);
  render(<LogActivity customerId="client" conversationId="chat" onSaved={onSaved} />);
  fireEvent.click(screen.getByRole("button",{name:"Log activity"}));
  fireEvent.change(await screen.findByLabelText("Summary"),{target:{value:"Agreed to review the venue shortlist."}});
  fireEvent.change(screen.getByLabelText("Recording link (optional)"),{target:{value:"javascript:alert(1)"}});
  fireEvent.click(screen.getByRole("button",{name:"Save activity"}));
  expect(await screen.findByRole("alert")).toHaveTextContent("HTTPS");
  expect(fetch).not.toHaveBeenCalled();
  fireEvent.change(screen.getByLabelText("Recording link (optional)"),{target:{value:"https://example.com/recording"}});
  fireEvent.click(screen.getByRole("button",{name:"Save activity"}));
  await waitFor(()=>expect(onSaved).toHaveBeenCalledOnce());
  expect(fetch.mock.calls[0][0]).toBe("/api/crm/contacts/client/activity");
  expect(JSON.parse(fetch.mock.calls[0][1].body)).toEqual({conversationId:"chat",type:"CALL_COMPLETED",details:"Agreed to review the venue shortlist.\nRecording: https://example.com/recording"});
});

import { ScheduleCall, callInvitation } from "../../../../../onno-crm-starter/src/main/widgets/ScheduleCall";
it("validates scheduling and prepares an invitation only after saving the event", async () => {
  expect(()=>callInvitation("2020-01-01T10:00","https://zoom.us/j/123","30")).toThrow("future");
  expect(()=>callInvitation("2099-01-01T10:00","https://zoom.us.evil.com/j/123","30")).toThrow("zoom.us");
  expect(()=>callInvitation("2099-01-01T10:00","https://zoom.us/j/123","0")).toThrow("duration");
  const fetch=vi.fn().mockResolvedValue({ok:true,json:async()=>({id:"meeting"})});
  vi.stubGlobal("fetch",fetch);
  const invitation=vi.fn();
  render(<ScheduleCall customerId="client" conversationId="chat" onSaved={async()=>{}} onInvitation={invitation} />);
  fireEvent.click(screen.getByRole("button",{name:"Schedule Zoom"}));
  fireEvent.change(await screen.findByLabelText("Date and time"),{target:{value:"2099-01-01T10:00"}});
  fireEvent.change(screen.getByLabelText("Zoom meeting link"),{target:{value:"https://zoom.us/j/123"}});
  fireEvent.click(screen.getByRole("button",{name:"Save & prepare invitation"}));
  await waitFor(()=>expect(invitation).toHaveBeenCalledOnce());
  const body=JSON.parse(fetch.mock.calls[0][1].body);
  expect(body.type).toBe("MEETING_PLANNED");expect(body.conversationId).toBe("chat");
  expect(body.details).toContain("UTC:");expect(invitation.mock.calls[0][0]).toContain("https://zoom.us/j/123");
  expect(fetch).toHaveBeenCalledTimes(1);
});
