import { render, screen, fireEvent, waitFor, cleanup } from "@testing-library/react";
import { afterEach, expect, it, vi } from "vitest";
import { EntityTags } from "../src/components/entity-tags";
afterEach(() => { cleanup(); vi.unstubAllGlobals(); });
it("shows real tag chips and removes only the selected assignment", async () => {
  let assigned = [{ id: "vip", name: "VIP", color: "#8b78ff" }];
  const fetcher = vi.fn(async (url: string, options?: RequestInit) => {
    if (options?.method === "DELETE") { assigned = []; return new Response(null, {status:204}); }
    return new Response(JSON.stringify(url.endsWith("/record") ? assigned : [{ id:"vip",name:"VIP",color:"#8b78ff" }]));
  });
  vi.stubGlobal("fetch", fetcher);
  render(<EntityTags kind="catalogs" name="Customers" id="record" />);
  fireEvent.click(await screen.findByRole("button", {name:"Remove tag VIP"}));
  await waitFor(() => expect(screen.queryByText("VIP")).toBeNull());
  expect(fetcher).toHaveBeenCalledWith("/api/tags/catalogs/Customers/record/vip", expect.objectContaining({method:"DELETE"}));
});
it("read-only records have no tag editing controls", async () => {
  vi.stubGlobal("fetch", vi.fn(async () => new Response(JSON.stringify([{ id:"vip", name:"VIP", color:"#8b78ff" }]))));
  render(<EntityTags kind="catalogs" name="Customers" id="record" readOnly />);
  expect(await screen.findByText("VIP")).toBeTruthy();
  expect(screen.queryByRole("button", {name:"Add tag"})).toBeNull();
  expect(screen.queryByRole("button", {name:"Remove tag VIP"})).toBeNull();
});
it("only selects existing tags and never offers inline creation", async () => {
  vi.stubGlobal("fetch", vi.fn(async () => new Response(JSON.stringify([]))));
  render(<EntityTags kind="catalogs" name="Customers" id="record" />);
  fireEvent.click(screen.getByRole("button", {name:"Add tag"}));
  fireEvent.change(await screen.findByRole("textbox", {name:"Search tags"}), {target:{value:"New tag"}});
  expect(screen.queryByRole("button", {name:/Create/})).toBeNull();
  expect(await screen.findByText("No matching tags.")).toBeTruthy();
});
it("toggles tags directly from a checked submenu item", async () => {
  const { EntityTagMenu } = await import("../src/components/entity-tags");
  const tag={id:"vip",name:"VIP",color:"#8b78ff"};
  let selected: typeof tag[]=[];
  vi.stubGlobal("fetch",vi.fn(async (url:string,options?:RequestInit) => {
    if(options?.method==="POST") {selected=[tag];return new Response(null,{status:204});}
    if(options?.method==="DELETE") {selected=[];return new Response(null,{status:204});}
    return new Response(JSON.stringify(url.endsWith("/record")?selected:[tag]));
  }));
  render(<EntityTagMenu kind="catalogs" name="Customers" id="record" />);
  const item=await screen.findByRole("menuitemcheckbox",{name:"VIP"});
  expect(item.getAttribute("aria-checked")).toBe("false");
  fireEvent.click(item);
  await waitFor(()=>expect(item.getAttribute("aria-checked")).toBe("true"));
  await waitFor(()=>expect((item as HTMLButtonElement).disabled).toBe(false));
  fireEvent.click(item);
  await waitFor(()=>expect(item.getAttribute("aria-checked")).toBe("false"));
});
