import {useEffect,useState} from "react";
import {act,cleanup,fireEvent,render,screen} from "@testing-library/react";
import {afterEach,expect,it,vi} from "vitest";
import {ExtensionSlot,registerExtension} from "../src/lib/ui-extensions";
const remove:(()=>void)[]=[];
afterEach(()=>{cleanup();remove.splice(0).forEach(fn=>fn());vi.restoreAllMocks();});
const context={surface:"crm-chat",recordId:"one",permissions:{canWrite:true}};
it("renders nothing without contributions and supports ordered live install/remove",()=>{
 const {container}=render(<ExtensionSlot name="crm.chat.header" context={context}/>);expect(container).toBeEmptyDOMElement();
 act(()=>{remove.push(registerExtension({id:"b",slot:"crm.chat.header",order:2,component:()=> <button>Second</button>}));remove.push(registerExtension({id:"a",slot:"crm.chat.header",order:1,component:()=> <button>First</button>}));});
 expect(screen.getAllByRole("button").map(b=>b.textContent)).toEqual(["First","Second"]);
 act(()=>remove.splice(0).forEach(fn=>fn()));expect(container).toBeEmptyDOMElement();
});
it("isolates failed contributions and recovers on replacement without old cleanup deleting the replacement",()=>{
 vi.spyOn(console,"error").mockImplementation(()=>{});
 const old=registerExtension({id:"broken",slot:"crm.chat.header",component:()=>{throw Error("boom");}});remove.push(old);
 remove.push(registerExtension({id:"hidden",slot:"crm.chat.header",visible:()=>{throw Error("predicate");},component:()=> <p>Hidden</p>}));
 render(<ExtensionSlot name="crm.chat.header" context={context}/>);expect(screen.getByRole("status")).toHaveTextContent("Extension unavailable");
 act(()=>{remove.push(registerExtension({id:"broken",slot:"crm.chat.header",component:()=> <p>Recovered</p>}));old();});expect(screen.getByText("Recovered")).toBeTruthy();
});
it("supplies live context, resets per record, and preserves state when unrelated extensions load",()=>{
 const disposed=vi.fn();
 function Body({context}:any){const [count,setCount]=useState(0);useEffect(()=>disposed,[]);return <button onClick={()=>setCount(count+1)}>{context.recordId}:{count}</button>;}
 remove.push(registerExtension({id:"stateful",slot:"crm.chat.header",visible:c=>c.permissions.canWrite,component:Body}));
 const view=render(<ExtensionSlot name="crm.chat.header" context={context}/>);fireEvent.click(screen.getByRole("button"));
 act(()=>{remove.push(registerExtension({id:"other",slot:"entity.form.aside",component:()=>null}));});expect(screen.getByText("one:1")).toBeTruthy();
 view.rerender(<ExtensionSlot name="crm.chat.header" context={{...context,recordId:"two"}}/>);expect(screen.getByText("two:0")).toBeTruthy();expect(disposed).toHaveBeenCalledTimes(1);
 view.rerender(<ExtensionSlot name="crm.chat.header" context={{...context,permissions:{canWrite:false}}}/>);expect(screen.queryByRole("button")).toBeNull();
});
