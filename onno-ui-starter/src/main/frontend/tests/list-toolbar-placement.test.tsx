import {cleanup, fireEvent, render, screen, waitFor, within} from "@testing-library/react";
import {afterEach, expect, it, vi} from "vitest";
import {EntityListWidget, type ListDescriptor, type ListRendererProps} from "@/components/entity-list-widget";
vi.mock("@/lib/presence-store",()=>({useViewersById:()=>new Map()}));
vi.mock("@/components/list-map-view",()=>({ListMapView:()=>null}));
afterEach(()=>{cleanup();vi.unstubAllGlobals();});
it("keeps controls reachable for empty custom feeds and restores them when switching to table", async()=>{
  vi.stubGlobal("ResizeObserver",class {observe(){} unobserve(){} disconnect(){}});
  vi.stubGlobal("fetch",vi.fn(async()=>new Response(JSON.stringify({rows:[],hasMore:false,total:0}))));
  const list: ListDescriptor = {kind:"catalogs",name:"orders",title:"Orders",columns:[],searchable:false,sort:{column:null,descending:false},newUrl:null,canWrite:false,pageSize:20,embedded:true,custom:{type:"workspace",label:"Workspace",defaultView:true}};
  const Renderer = (props:ListRendererProps)=><section data-testid="custom-body">{props.toolbar}<p>No workspace records</p></section>;
  render(<EntityListWidget list={list} renderer={Renderer} toolbarPlacement="renderer" headerExtra={<button>Reset filters</button>} />);
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
  vi.stubGlobal("matchMedia",(query:string)=>({matches:query.includes("max-width: 767px"),addEventListener(){},removeEventListener(){}}));
  vi.stubGlobal("fetch",vi.fn(async()=>new Response(JSON.stringify({rows:[],hasMore:false,total:0}))));
  const list: ListDescriptor = {kind:"catalogs",name:"orders",title:"Orders",columns:[],searchable:true,sort:{column:null,descending:false},newUrl:null,canWrite:false,pageSize:20,embedded:true,custom:{type:"workspace",label:"Workspace",defaultView:true}};
  const Renderer=(props:ListRendererProps)=><section>{props.toolbar}</section>;
  render(<EntityListWidget list={list} renderer={Renderer} toolbarPlacement="renderer" toolbarOptions={{compactPresentation:"sheet",controls:["search","filters"]}} toolbarContent={<button>Channel options</button>} />);
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
  const rect = vi.spyOn(HTMLElement.prototype,"getBoundingClientRect").mockImplementation(function(){
    return {width:this.hasAttribute("data-toolbar-container") ? 300 : 1200,height:600,top:0,left:0,right:1200,bottom:600,x:0,y:0,toJSON(){}};
  });
  vi.stubGlobal("fetch",vi.fn(async()=>new Response(JSON.stringify({rows:[],hasMore:false,total:0}))));
  const list: ListDescriptor = {kind:"catalogs",name:"orders",title:"Orders",columns:[],searchable:true,sort:{column:null,descending:false},newUrl:null,canWrite:false,pageSize:20,embedded:true};
  try {
    render(<EntityListWidget list={list} toolbarOptions={{compactPresentation:"sheet",controls:["search","filters"]}} toolbarContent={<button>Channel options</button>} />);
    expect(await screen.findByRole("textbox",{name:"Search"})).toBeVisible();
    expect(screen.getByRole("button",{name:"Filters"})).toBeVisible();
    expect(screen.queryByText("Channel options")).toBeNull();
  } finally {rect.mockRestore();}
});
