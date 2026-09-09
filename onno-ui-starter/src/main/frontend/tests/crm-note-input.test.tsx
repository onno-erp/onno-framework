import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { afterEach, expect, it, vi } from "vitest";
import * as React from "react";
vi.mock("@onno/widget-sdk", async () => ({...React, ...(await import("../src/components/ui/textarea")), ...(await import("../src/components/ui/button")), ...(await import("../src/components/ui/popover"))}));
import { NoteInput } from "../../../../../onno-crm-starter/src/main/widgets/NoteInput";
afterEach(() => { cleanup(); vi.unstubAllGlobals(); });
it("keeps the inbox input and inserts a person reference without introducing another send button", async () => {
  const request = vi.fn().mockResolvedValue({ok:true,json:async () => [{kind:"catalogs",name:"agents",id:"12345678-1234-1234-1234-123456789012",display:"Alice",entity:"Agents"}]});
  vi.stubGlobal("fetch",request);
  const change = vi.fn(), send = vi.fn();
  function Wrapper() {const [value,setValue] = React.useState(""); return <NoteInput value={value} disabled={false} onChange={(text,body) => {setValue(text);change(body);}} onSubmit={send}/>;}
  render(<Wrapper />);
  const input = screen.getByRole("textbox");
  fireEvent.change(input,{target:{value:"@Al",selectionStart:3}});
  expect(await screen.findByRole("option", {name:"Alice Agents"})).toBeTruthy();
  fireEvent.keyDown(input,{key:"Enter"});
  expect(change).toHaveBeenLastCalledWith("@[Alice](catalogs/agents/12345678-1234-1234-1234-123456789012) ");
  expect(send).not.toHaveBeenCalled();
  expect(screen.queryAllByRole("button")).toHaveLength(0);
  fireEvent.keyDown(input,{key:"Enter",ctrlKey:true}); expect(send).toHaveBeenCalledOnce();
  fireEvent.change(input,{target:{value:"#Quote",selectionStart:6}});
  await waitFor(() => expect(request).toHaveBeenLastCalledWith("/api/mentions?q=Quote", expect.anything()));
});
