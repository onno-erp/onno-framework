import {act, cleanup, fireEvent, render, screen, waitFor, within} from "@testing-library/react";
import {afterEach, expect, it, vi} from "vitest";
import {EntityListView, type ListDescriptor, type ListRendererProps} from "@/components/lists/entity-list";
import {stubScreenWidth} from "./viewport-fixtures";
vi.mock("@/lib/presence-store",()=>({useViewersById:()=>new Map()}));
vi.mock("@/components/maps/list-map-view",()=>({ListMapView:()=>null}));
afterEach(()=>{cleanup();vi.unstubAllGlobals();});
it("keeps controls reachable for empty custom feeds and restores them when switching to table", async()=>{
  vi.stubGlobal("ResizeObserver",class {observe(){} unobserve(){} disconnect(){}});
  vi.stubGlobal("fetch",vi.fn(async()=>new Response(JSON.stringify({rows:[],hasMore:false,total:0}))));
  const list: ListDescriptor = {kind:"catalogs",name:"orders",title:"Orders",columns:[],searchable:false,sort:{column:null,descending:false},newUrl:null,canWrite:false,pageSize:20,embedded:true,custom:{label:"Workspace",defaultView:true}};
  const Renderer = (props:ListRendererProps)=><section data-testid="custom-body">{props.toolbar}<p>No workspace records</p></section>;
  render(<EntityListView list={list} renderer={Renderer} toolbarPlacement="renderer" headerExtra={<button>Reset filters</button>} />);
  const body = await screen.findByTestId("custom-body");
  expect(within(body).getByText("Reset filters")).toBeVisible();
  expect(document.querySelectorAll("[data-list-toolbar]")).toHaveLength(1);
  fireEvent.click(screen.getByRole("button",{name:"Table"}));
  await waitFor(()=>expect(screen.queryByTestId("custom-body")).toBeNull());
  expect(screen.getByText("Reset filters")).toBeVisible();
  expect(document.querySelectorAll("[data-list-toolbar]")).toHaveLength(1);
});
it("uses a persistent mobile search and a filter sheet without losing query state", async()=>{
  vi.stubGlobal("ResizeObserver",class {observe(){} unobserve(){} disconnect(){}});
  stubScreenWidth(390);
  vi.stubGlobal("fetch",vi.fn(async()=>new Response(JSON.stringify({rows:[],hasMore:false,total:0}))));
  const list: ListDescriptor = {kind:"catalogs",name:"orders",title:"Orders",columns:[],searchable:true,sort:{column:null,descending:false},newUrl:null,canWrite:false,pageSize:20,embedded:true,custom:{label:"Workspace",defaultView:true}};
  const Renderer=(props:ListRendererProps)=><section>{props.toolbar}</section>;
  render(<EntityListView list={list} renderer={Renderer} toolbarPlacement="renderer" toolbarOptions={{compactPresentation:"sheet",controls:["search","filters"]}} toolbarContent={<button>Channel options</button>} />);
  const search=screen.getByRole("textbox",{name:"Search"});
  fireEvent.change(search,{target:{value:"Amelia"}});
  expect(screen.queryByText("Channel options")).toBeNull();
  fireEvent.click(screen.getByRole("button",{name:"Filters"}));
  expect(await screen.findByText("Channel options")).toBeVisible();
  fireEvent.click(screen.getByRole("button",{name:"Done"}));
  expect(search).toHaveValue("Amelia");
  await waitFor(()=>expect(fetch).toHaveBeenCalledTimes(2));
  expect(screen.getByRole("textbox",{name:"Search"})).toBe(search);
});

it("uses compact controls in narrow panes on a desktop viewport", async()=>{
  vi.stubGlobal("matchMedia",()=>({matches:false,addEventListener(){},removeEventListener(){}}));
  vi.stubGlobal("ResizeObserver",class {observe(){} unobserve(){} disconnect(){}});
  const rect = vi.spyOn(HTMLElement.prototype,"getBoundingClientRect").mockImplementation(function(this:HTMLElement){
    return {width:this.hasAttribute("data-toolbar-container") ? 300 : 1200,height:600,top:0,left:0,right:1200,bottom:600,x:0,y:0,toJSON(){}};
  });
  vi.stubGlobal("fetch",vi.fn(async()=>new Response(JSON.stringify({rows:[],hasMore:false,total:0}))));
  const list: ListDescriptor = {kind:"catalogs",name:"orders",title:"Orders",columns:[],searchable:true,filters:[{key:"status",column:"status",label:"Status",type:"options",options:[{value:"open",label:"Open"},{value:"closed",label:"Closed"}]}],sort:{column:null,descending:false},newUrl:null,canWrite:false,pageSize:20,embedded:true};
  try {
    render(<EntityListView list={list} toolbarOptions={{compactPresentation:"sheet",controls:["search","filters"]}} toolbarContent={<button>Channel options</button>} />);
    expect(await screen.findByRole("textbox",{name:"Search"})).toBeVisible();
    expect(screen.getByRole("button",{name:"Filters"})).toBeVisible();
    expect(screen.queryByText("Channel options")).toBeNull();
    fireEvent.click(screen.getByRole("button", {name:"Filters"}));
    expect(await screen.findByRole("dialog", {name:"Filters"})).toBeVisible();
    expect(screen.getByText("Channel options")).toBeVisible();
    expect(document.querySelector("[data-vaul-drawer]")).toBeNull();
    fireEvent.click(screen.getByRole("button", {name:"Open"}));
    expect(screen.getByRole("button", {name:"Open"})).toHaveAttribute("aria-pressed", "true");
    expect(screen.getAllByRole("dialog")).toHaveLength(1);
    await waitFor(()=>expect(vi.mocked(fetch).mock.calls.some(([url])=>decodeURIComponent(String(url)).includes("status,open"))).toBe(true));
    fireEvent.click(screen.getByRole("button", {name:"All"}));
    expect(screen.getByRole("button", {name:"Open"})).toHaveAttribute("aria-pressed", "false");
  } finally {rect.mockRestore();}
});

it("shows workspace navigation by default and preserves selections in the mobile filter sheet", async()=>{
  vi.stubGlobal("matchMedia",()=>({matches:false,addEventListener(){},removeEventListener(){}}));
  let width = 1400;
  const observers = new Set<() => void>();
  vi.stubGlobal("ResizeObserver",class { constructor(private callback:()=>void) { observers.add(callback); } observe(){} unobserve(){} disconnect(){observers.delete(this.callback);} });
  const rect=vi.spyOn(HTMLElement.prototype,"getBoundingClientRect").mockImplementation(function(this:HTMLElement){
    return {width:this.hasAttribute("data-toolbar-container")?300:width,height:600,top:0,left:0,right:width,bottom:600,x:0,y:0,toJSON(){}};
  });
  vi.stubGlobal("fetch",vi.fn(async()=>new Response(JSON.stringify({rows:[],hasMore:false,total:0}))));
  const list:ListDescriptor={kind:"catalogs",name:"sidebar-orders",title:"Orders",columns:[],searchable:true,filters:[{key:"status",column:"status",label:"Status",type:"multiOptions",options:[{value:"open",label:"Open"},{value:"closed",label:"Closed"}]}],sort:{column:null,descending:false},newUrl:null,canWrite:false,pageSize:20,embedded:true,custom:{label:"Workspace",defaultView:true}};
  const Renderer=(props:ListRendererProps)=><section data-testid="inbox-body">{props.toolbar}<p>Conversations</p></section>;
  try {
    render(<EntityListView list={list} renderer={Renderer} toolbarPlacement="renderer" toolbarOptions={{filterSidebar:true,controls:["search","filters"]}} />);
    const panel=await screen.findByRole("navigation",{name:"Orders"});
    expect(screen.queryByRole("button",{name:"Filters"})).toBeNull();
    expect(within(panel).queryByRole("button",{name:"Close"})).toBeNull();
    expect(panel.compareDocumentPosition(screen.getByTestId("inbox-body")) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy();
    expect(screen.queryByRole("dialog")).toBeNull();
    fireEvent.click(within(panel).getByRole("button",{name:"Open"}));
    fireEvent.click(within(panel).getByRole("button",{name:"Closed"}));
    expect(within(panel).getByRole("button",{name:"Open"})).toHaveAttribute("aria-pressed","true");
    fireEvent.click(screen.getByRole("button",{name:"Hide filters"}));
    expect(screen.queryByRole("navigation",{name:"Orders"})).toBeNull();
    expect(panel.parentElement).toHaveAttribute("inert");
    const reopen=screen.getByRole("button",{name:"Show filters"});
    expect(reopen).toHaveAttribute("aria-expanded","false");
    expect(reopen).toHaveAttribute("aria-controls",panel.parentElement!.id);
    fireEvent.click(reopen);
    expect(screen.getByRole("navigation",{name:"Orders"})).toBe(panel);
    expect(within(panel).getByRole("button",{name:"Open"})).toHaveAttribute("aria-pressed","true");
    await act(async()=>{width=700;observers.forEach(callback=>callback());});
    expect(screen.queryByRole("navigation",{name:"Orders"})).toBeNull();
    fireEvent.click(screen.getByRole("button",{name:"Filters"}));
    expect(await screen.findByRole("dialog")).toBeVisible();
    expect(screen.getByRole("button",{name:"Open"})).toHaveAttribute("aria-pressed","true");
    expect(screen.getByRole("button",{name:"Closed"})).toHaveAttribute("aria-pressed","true");
    fireEvent.click(screen.getByRole("button",{name:"Done"}));
    await waitFor(()=>expect(screen.queryByRole("dialog")).toBeNull());
    expect(screen.getByRole("button",{name:"Filters"})).toHaveAttribute("aria-expanded","false");
  } finally {rect.mockRestore();}
});

it("can disable the sidebar while retaining filters and workspace actions",async()=>{
  vi.stubGlobal("matchMedia",()=>({matches:false,addEventListener(){},removeEventListener(){}}));
  vi.stubGlobal("ResizeObserver",class {observe(){} unobserve(){} disconnect(){}});
  const rect=vi.spyOn(HTMLElement.prototype,"getBoundingClientRect").mockImplementation(function(this:HTMLElement){return {width:this.hasAttribute("data-toolbar-container")?300:1400,height:600,top:0,left:0,right:1400,bottom:600,x:0,y:0,toJSON(){}};});
  vi.stubGlobal("fetch",vi.fn(async()=>new Response(JSON.stringify({rows:[],hasMore:false,total:0}))));
  const list:ListDescriptor={kind:"catalogs",name:"optional-sidebar",title:"Inbox",columns:[],searchable:true,sort:{column:null,descending:false},newUrl:null,canWrite:false,pageSize:20,embedded:true,custom:{label:"Workspace",defaultView:true}};
  const Renderer=(props:ListRendererProps)=><section>{props.toolbar}</section>;
  const view=(enabled:boolean)=><EntityListView list={list} renderer={Renderer} toolbarPlacement="renderer" toolbarOptions={{filterSidebar:enabled,compactPresentation:"sheet",controls:["search","filters"]}} toolbarContent={<button>Channels</button>} toolbarFooter={<a href="/ui/connections">Manage connections</a>}/>;
  try {
    const {rerender}=render(view(true));
    expect(await screen.findByRole("navigation",{name:"Inbox"})).toBeVisible();
    rerender(view(false));
    expect(screen.queryByRole("navigation",{name:"Inbox"})).toBeNull();
    expect(screen.getByRole("textbox",{name:"Search"})).toBeVisible();
    fireEvent.click(screen.getByRole("button",{name:"Filters"}));
    expect(await screen.findByRole("button",{name:"Channels"})).toBeVisible();
    expect(screen.getByRole("link",{name:"Manage connections"})).toBeVisible();
  } finally {rect.mockRestore();}
});

it("renders configured facet colors and respects the configured filter order",async()=>{
  vi.stubGlobal("ResizeObserver",class {observe(){} unobserve(){} disconnect(){}});
  vi.stubGlobal("matchMedia",()=>({matches:true,addEventListener(){},removeEventListener(){}}));
  vi.stubGlobal("fetch",vi.fn(async()=>new Response(JSON.stringify({rows:[],hasMore:false,total:0}))));
  const list:ListDescriptor={kind:"catalogs",name:"colored-facets",title:"Inbox",columns:[],searchable:false,sort:{column:null,descending:false},newUrl:null,canWrite:false,pageSize:20,embedded:true,filters:[{key:"status",column:"status",label:"Conversation status",type:"multiOptions",options:[{value:"open",label:"Open",color:"#60A5FA"}]},{key:"stage",column:"stage",label:"Pipeline stage",type:"multiOptions",options:[{value:"won",label:"Won",color:"#10B981"}]}]};
  render(<EntityListView list={list} toolbarOptions={{compactPresentation:"sheet",controls:["filters"],filterKeys:["stage","status"]}}/>);
  fireEvent.click(screen.getByRole("button",{name:"Filters"}));
  expect(await screen.findByText("Pipeline stage")).toBeVisible();
  const groups=[...document.querySelectorAll("fieldset legend")].map(e=>e.textContent);
  expect(groups).toEqual(["Pipeline stage","Conversation status"]);
  expect(screen.getByRole("button",{name:"Won"}).querySelector('[aria-hidden="true"]')).toHaveStyle({backgroundColor:"#10B981"});
});
