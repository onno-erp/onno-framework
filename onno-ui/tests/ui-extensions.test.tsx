import {useEffect,useState} from "react";
import {cleanup,fireEvent,render,screen} from "@testing-library/react";
import {afterEach,expect,it,vi} from "vitest";
import {ExtensionSlot} from "../src/lib/ui-extensions";
import {AppConfigProvider} from "../src/lib/app-config";
import type {ExtensionContext,UiExtension} from "../src/lib/extension-types";
afterEach(()=>{cleanup();vi.restoreAllMocks();});
const context:ExtensionContext={surface:"crm-chat",recordId:"one",permissions:{canWrite:true}};
const slot=(extensions:UiExtension[],ctx:ExtensionContext=context)=><AppConfigProvider app={{extensions}}><ExtensionSlot name="crm.chat.header" context={ctx}/></AppConfigProvider>;
it("renders nothing without contributions and the app's contributions in order",()=>{
 const view=render(slot([]));expect(view.container).toBeEmptyDOMElement();
 view.rerender(slot([{id:"b",slot:"crm.chat.header",order:2,component:()=> <button>Second</button>},{id:"a",slot:"crm.chat.header",order:1,component:()=> <button>First</button>},{id:"c",slot:"entity.form.aside",component:()=> <button>Elsewhere</button>}]));
 expect(screen.getAllByRole("button").map(b=>b.textContent)).toEqual(["First","Second"]);
});
it("isolates a failing contribution or predicate",()=>{
 vi.spyOn(console,"error").mockImplementation(()=>{});
 render(slot([{id:"broken",slot:"crm.chat.header",component:()=>{throw Error("boom");}},{id:"hidden",slot:"crm.chat.header",visible:()=>{throw Error("predicate");},component:()=> <p>Hidden</p>}]));
 expect(screen.getByRole("status")).toHaveTextContent("Extension unavailable");
 expect(screen.queryByText("Hidden")).toBeNull();
});
it("supplies live context and resets per record",()=>{
 const disposed=vi.fn();
 function Body({context}:{context:ExtensionContext}){const [count,setCount]=useState(0);useEffect(()=>disposed,[]);return <button onClick={()=>setCount(count+1)}>{context.recordId}:{count}</button>;}
 const extensions:UiExtension[]=[{id:"stateful",slot:"crm.chat.header",visible:c=>!!c.permissions.canWrite,component:Body}];
 const view=render(slot(extensions));fireEvent.click(screen.getByRole("button"));expect(screen.getByText("one:1")).toBeTruthy();
 view.rerender(slot(extensions,{...context,recordId:"two"}));expect(screen.getByText("two:0")).toBeTruthy();expect(disposed).toHaveBeenCalledTimes(1);
 view.rerender(slot(extensions,{...context,permissions:{canWrite:false}}));expect(screen.queryByRole("button")).toBeNull();
});
