import * as React from "react";
import { cleanup, fireEvent, render, screen } from "@testing-library/react";
import { afterEach, expect, it, vi } from "vitest";
vi.mock("@onno/widget-sdk", async () => ({
  ...React,
  ...await import("../src/components/ui/button"),
  ...await import("../src/components/ui/input"),
  ...await import("../src/components/ui/badge"),
  Label: "label", toast: {}, registerWidget: vi.fn(), useUiEvents: vi.fn(), ExtensionSlot: () => null,
}));
import { ContactPanel, type Config } from "../../../../../onno-crm-starter/src/main/widgets/CrmWorkspace";
afterEach(() => { cleanup();vi.unstubAllGlobals(); });
it("renders host fields and opens the host catalog without fetching CRM customer or stage catalogs", async () => {
  const fetch = vi.fn().mockResolvedValue({ok:true,json:async()=>({catalogName:"Guests",canWrite:false,
    fields:{id:"person",description:"Alex",membership:"Annual"},identities:[],conversations:[]})});
  vi.stubGlobal("fetch", fetch);
  const config = {fields:[{key:"customer.membership",label:"Membership",section:"Contact",format:"text",visible:true,editable:false}],
    actions:[{key:"edit",label:"Open contact",visible:true}],showAvatar:false,showIdentities:false,showEmpty:false} as Config;
  const navigate=vi.fn();window.addEventListener("onno:action",navigate);
  render(<ContactPanel id="person" config={config} />);
  expect(await screen.findByText("Annual")).toBeVisible();
  fireEvent.click(screen.getByRole("button",{name:"Open contact"}));
  expect((navigate.mock.calls[0][0] as CustomEvent).detail).toBe("onno://catalogs/Guests/person");
  expect(fetch.mock.calls.map(call=>call[0])).toEqual(["/api/crm/contacts/person"]);
  window.removeEventListener("onno:action",navigate);
});
