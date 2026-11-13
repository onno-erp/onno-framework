import { act, cleanup, fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import { afterEach, beforeEach, expect, it, vi } from "vitest";
import type { ComponentType } from "react";
import { resolveWidget } from "@/lib/widget-registry";
vi.mock("@/lib/presence-store", () => ({ useViewersById: () => new Map() }));
vi.mock("@/components/list-map-view", () => ({ ListMapView: () => null }));
vi.mock("@onno/widget-sdk", async importOriginal => { await import("@/lib/plugin-host"); return importOriginal(); });
import "../../../../../onno-crm-starter/src/main/widgets/CrmInbox";
class Observer { observe() {} unobserve() {} disconnect() {} }
const response = (body: unknown) => new Response(JSON.stringify(body));
function deferred() {
  let resolve!: (value: Response) => void;
  const promise = new Promise<Response>(done => { resolve = done; });
  return {promise, resolve};
}
beforeEach(() => {
  vi.stubGlobal("ResizeObserver", Observer);
  vi.stubGlobal("IntersectionObserver", Observer);
  vi.spyOn(HTMLElement.prototype, "clientWidth", "get").mockReturnValue(1200);
  vi.spyOn(HTMLElement.prototype, "getClientRects").mockReturnValue([{}] as unknown as DOMRectList);
  vi.spyOn(document, "visibilityState", "get").mockReturnValue("visible");
  Object.defineProperty(HTMLElement.prototype, "scrollIntoView", {configurable:true, value:vi.fn()});
});
afterEach(() => { cleanup(); vi.restoreAllMocks(); vi.unstubAllGlobals(); delete (HTMLElement.prototype as Partial<HTMLElement>).scrollIntoView; });
const config = {folders:[], fields:[], actions:[{key:"reply",label:"Reply",visible:true},{key:"sendReply",label:"Send reply",visible:true}], showAvatar:false, listTitle:"customerDisplay",listSubtitle:"subject",listPreview:"lastMessagePreview"};
const activity = {entries:[{id:"message:one",conversationId:"chat",kind:"CUSTOMER_MESSAGE",direction:"INBOUND",channel:"EMAIL",authorName:"Person",body:"Hello from the customer",at:"2026-10-05T10:00:00",attachments:[]}],hasMore:false};
const connected = {connected:true,label:"Email",replyCapability:"AVAILABLE",maxAttachments:2,maxTextLength:8000};
function mount(options: {sidebar?: boolean; activity?: Promise<Response>; contact?: Promise<Response>; delivery?: Promise<Response>; read?: Promise<Response>} = {}) {
  const fetcher = vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
    const path = new URL(String(input), "http://localhost").pathname;
    if (path === "/api/crm/inbox-workspaces") return response([{key:"support"}]);
    if (path === "/api/crm/inbox-workspaces/support") return response({key:"support",config,
      list:{columns:[],filters:[],searchable:false,sort:{column:null,descending:false},pageSize:50,custom:{type:"crmInbox",defaultView:true}},
      rows:[{id:"chat",customer:"person",customerDisplay:"Person",channel:"EMAIL",subject:"Original topic",inboxDisplay:"Gmail · agent@example.com",unreadCount:1}],total:1,hasMore:false,nextCursor:null});
    if (path === "/api/crm/workspace") return response({config});
    if (path.endsWith("/activity")) return options.activity ?? response(activity);
    if (path === "/api/crm/contacts/person") return options.contact ?? response({fields:{description:"Person"},identities:[]});
    if (path.endsWith("/delivery")) return options.delivery ?? response(connected);
    if (path.endsWith("/read")) return options.read ?? response({unreadCount:0});
    if (path === "/api/media/limits") return response({maxFileSize:25*1024*1024});
    if (path === "/api/media") return response({url:"/api/media/test.pdf",filename:"test.pdf",contentType:"application/pdf",size:4});
    return response([]);
  });
  vi.stubGlobal("fetch", fetcher);
  const Workspace = resolveWidget("crmInboxWorkspaces") as ComponentType<any>;
  const view = render(<Workspace widget={{extraConfig:{workspace:"support",toolbarPlacement:"renderer",connectionsRoute:"/connections",filterSidebar:options.sidebar?"true":"false"}}} />);
  return {...view, fetcher};
}
it("acknowledges visible history without waiting for contact/delivery and clears the badge on success", async () => {
  const history = deferred(), contact = deferred(), delivery = deferred(), read = deferred();
  const {fetcher} = mount({activity:history.promise,contact:contact.promise,delivery:delivery.promise,read:read.promise});
  const chat = await screen.findByRole("button",{name:/Person/});
  expect(within(chat).getByText("1")).toBeInTheDocument();
  expect(fetcher.mock.calls.some(([url]) => String(url).includes("/read"))).toBe(false);
  await act(async () => history.resolve(response(activity)));
  await screen.findByText("Hello from the customer");
  await waitFor(() => expect(fetcher.mock.calls.some(([url]) => String(url).includes("/read"))).toBe(true));
  expect(within(chat).getByText("1")).toBeInTheDocument();
  await act(async () => read.resolve(response({unreadCount:0})));
  await waitFor(() => expect(within(chat).queryByText("1")).toBeNull());
});
it("does not mark a failed history request as read", async () => {
  const {fetcher} = mount({activity:Promise.resolve(new Response(null,{status:503}))});
  await screen.findByRole("alert");
  expect(fetcher.mock.calls.some(([url]) => String(url).includes("/read"))).toBe(false);
});
it.each([
  [{...connected,connected:false,label:"Channel unavailable"}, "Channel unavailable"],
  [{...connected,maxAttachments:0,attachmentReason:"Files are not supported"}, "Files are not supported"],
])("keeps unavailable attachments disabled with a hover explanation", async (delivery, reason) => {
  const {container,fetcher} = mount({delivery:Promise.resolve(response(delivery))});
  const button = await screen.findByRole("button",{name:"Attach files"});
  await waitFor(() => expect(button.parentElement).toHaveAttribute("title",reason));
  expect(button).toBeDisabled();
  const picker = container.querySelector<HTMLInputElement>('input[type="file"]')!;
  const click = vi.spyOn(picker,"click");
  fireEvent.click(button);
  expect(screen.queryByRole("alert")).not.toBeInTheDocument();
  expect(click).not.toHaveBeenCalled();
  expect(fetcher.mock.calls.some(([url]) => String(url) === "/api/media")).toBe(false);
});
it("opens the picker and uploads for an available channel without sending a message", async () => {
  const {container,fetcher} = mount();
  const button = await screen.findByRole("button",{name:"Attach files"});
  await waitFor(() => expect(button).toBeEnabled());
  const picker = container.querySelector<HTMLInputElement>('input[type="file"]')!;
  const click = vi.spyOn(picker,"click");
  fireEvent.click(button);
  expect(click).toHaveBeenCalledOnce();
  fireEvent.change(picker,{target:{files:[new File(["test"],"test.pdf",{type:"application/pdf"})]}});
  await screen.findByText("test.pdf");
  expect(fetcher.mock.calls.some(([url]) => String(url) === "/api/media")).toBe(true);
  expect(fetcher.mock.calls.some(([url]) => String(url).includes("/reply"))).toBe(false);
});

it("keeps sender identity separate and sends an edited email subject", async () => {
  const {container,fetcher}=mount();
  const subject=await screen.findByRole("textbox",{name:"Subject"});
  await waitFor(()=>expect(subject).toBeEnabled());
  expect(subject).toHaveValue("Original topic");
  const sender=container.querySelector("[data-sender-account]")!;
  expect(screen.queryByRole("combobox",{name:"Send from"})).not.toBeInTheDocument();
  expect(sender).toHaveTextContent("agent@example.com");
  expect(sender).not.toHaveTextContent("Original topic");
  fireEvent.change(subject,{target:{value:"Wedding proposal"}});
  expect(screen.getByText("Changing the subject starts a new email thread.")).toBeInTheDocument();
  expect(subject).toHaveValue("Wedding proposal");
  const body=screen.getByRole("textbox",{name:"Write a reply"});
  fireEvent.change(body,{target:{value:"Here is the proposal"}});
  fireEvent.keyDown(body,{key:"Enter",ctrlKey:true});
  await waitFor(()=>expect(fetcher.mock.calls.some(([url,init])=>String(url).includes("/messages")&&JSON.parse(String(init?.body)).subject==="Wedding proposal")).toBe(true));
});

it("rejects files over the configured limit without posting their bytes", async () => {
  const {container,fetcher}=mount();
  await waitFor(()=>expect(screen.getByRole("button",{name:"Attach files"})).toBeEnabled());
  const file=new File(["test"],"too-large.pdf",{type:"application/pdf"});
  Object.defineProperty(file,"size",{value:26*1024*1024});
  fireEvent.change(container.querySelector('input[type="file"]')!,{target:{files:[file]}});
  await screen.findByText(/too-large.pdf is too large/);
  expect(fetcher.mock.calls.some(([url])=>String(url)==="/api/media")).toBe(false);
});
it("allows a 23 MB file under the configured 25 MB limit", async () => {
  const {container,fetcher}=mount();
  await waitFor(()=>expect(screen.getByRole("button",{name:"Attach files"})).toBeEnabled());
  const file=new File(["test"],"proposal.pdf",{type:"application/pdf"});
  Object.defineProperty(file,"size",{value:23*1024*1024});
  fireEvent.change(container.querySelector('input[type="file"]')!,{target:{files:[file]}});
  await screen.findByText("test.pdf");
  expect(fetcher.mock.calls.some(([url])=>String(url)==="/api/media")).toBe(true);
});

it("accepts dropped files without sending a reply and keeps the target visible across children", async () => {
  const {container,fetcher}=mount();
  await waitFor(()=>expect(screen.getByRole("button",{name:"Attach files"})).toBeEnabled());
  const zone=container.querySelector('[data-file-drop-zone]')!;
  const transfer={types:["Files"],files:[new File(["test"],"test.pdf",{type:"application/pdf"})],dropEffect:"none"};
  fireEvent.dragEnter(zone,{dataTransfer:transfer});
  expect(screen.getByText("Drop files to attach")).toBeInTheDocument();
  const child=container.querySelector('[data-chat-composer]')!;
  fireEvent.dragEnter(child,{dataTransfer:transfer});
  fireEvent.dragLeave(child,{dataTransfer:transfer});
  expect(screen.getByText("Drop files to attach")).toBeInTheDocument();
  expect(fireEvent.dragOver(zone,{dataTransfer:transfer})).toBe(false);
  expect(fireEvent.drop(zone,{dataTransfer:transfer})).toBe(false);
  expect(screen.queryByText("Drop files to attach")).not.toBeInTheDocument();
  await screen.findByText("test.pdf");
  expect(fetcher.mock.calls.some(([url])=>String(url).includes("/messages"))).toBe(false);
});
it("leaves text drags alone and rejects files when the channel cannot attach", async () => {
  const {container,fetcher}=mount({delivery:Promise.resolve(response({...connected,maxAttachments:0,attachmentReason:"Files are not supported"}))});
  await waitFor(()=>expect(screen.getByRole("button",{name:"Attach files"}).parentElement).toHaveAttribute("title","Files are not supported"));
  const zone=container.querySelector('[data-file-drop-zone]')!;
  expect(fireEvent.dragOver(zone,{dataTransfer:{types:["text/plain"]}})).toBe(true);
  expect(screen.queryByText("Drop files to attach")).not.toBeInTheDocument();
  fireEvent.drop(zone,{dataTransfer:{types:["Files"],files:[new File(["test"],"test.pdf")]}});
  expect(screen.getByRole("alert")).toHaveTextContent("Files are not supported");
  expect(fetcher.mock.calls.some(([url])=>String(url).startsWith("/api/media"))).toBe(false);
});

it("opens Manage connections through the host navigation action", async () => {
  const events: string[]=[];
  const listener=(event:Event)=>events.push((event as CustomEvent).detail);
  window.addEventListener("onno:action",listener);
  try {
    vi.stubGlobal("matchMedia",()=>({matches:false,addEventListener(){},removeEventListener(){}}));
    vi.spyOn(HTMLElement.prototype,"getBoundingClientRect").mockReturnValue({width:1400,height:600,top:0,left:0,right:1400,bottom:600,x:0,y:0,toJSON(){}});
    mount({sidebar:true});
    const button=await screen.findByRole("button",{name:"Manage connections"});
    fireEvent.click(button);
    expect(events).toEqual(["onno://connections"]);
    expect(screen.queryByRole("link",{name:"Manage connections"})).not.toBeInTheDocument();
  } finally {window.removeEventListener("onno:action",listener);}
});

it("plays received audio inline without preloading and retains a download fallback", async () => {
  const file={url:"/api/media/voice.ogg",filename:"voice.ogg",contentType:"audio/ogg",size:100,image:false};
  const {container}=mount({activity:Promise.resolve(response({ ...activity, entries:[{...activity.entries[0],body:"",attachments:[file]}]}))});
  const player=await screen.findByLabelText("voice.ogg", {selector:"audio"});
  expect(player.tagName).toBe("AUDIO");
  expect(player).not.toHaveAttribute("controls");
  expect(screen.getByRole("button",{name:"Play voice message"})).toBeInTheDocument();
  expect(player).toHaveAttribute("preload","none");
  expect(player).toHaveAttribute("src",file.url);
  expect(player).not.toHaveAttribute("autoplay");
  expect(screen.getByRole("link",{name:"voice.ogg"})).toHaveAttribute("download","voice.ogg");
  expect(container.querySelectorAll("audio")).toHaveLength(1);
});

it("chooses a contact email independently from the sending account", async () => {
  const {fetcher}=mount({delivery:Promise.resolve(response({...connected,recipient:"personal@example.com",recipients:["personal@example.com","work@example.com"]}))});
  const recipient=await screen.findByRole("combobox",{name:"To"});
  expect(recipient).toHaveTextContent("personal@example.com");
  fireEvent.keyDown(recipient,{key:"ArrowDown"});
  fireEvent.click(await screen.findByRole("option",{name:"work@example.com"}));
  expect(recipient).toHaveTextContent("work@example.com");
  expect(screen.getByText("Changing the recipient starts a new email thread.")).toBeInTheDocument();
  expect(screen.getByText("Gmail · agent@example.com")).toBeInTheDocument();
  const body=screen.getByRole("textbox",{name:"Write a reply"});
  fireEvent.change(body,{target:{value:"Hello at work"}});
  fireEvent.keyDown(body,{key:"Enter",ctrlKey:true});
  await waitFor(()=>expect(fetcher.mock.calls.some(([url,init])=>String(url).includes("/messages")&&JSON.parse(String(init?.body)).recipient==="work@example.com")).toBe(true));
});
