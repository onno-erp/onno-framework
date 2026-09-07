import { Component, useSyncExternalStore, type ReactNode } from "react";
import type { UiExtension, ExtensionSlotProps } from "../../../../../../onno-widget-sdk/src/extensions";
const entries = new Map<string, UiExtension & {generation:number}>();
const listeners = new Set<() => void>();
let revision = 0;
const notify = () => { revision++; listeners.forEach(listener => listener()); };
const subscribe = (listener: () => void) => { listeners.add(listener); return () => {listeners.delete(listener);}; };
export function registerExtension(extension: UiExtension): () => void {
  if (!extension.id.trim() || !extension.slot.trim()) throw new Error("Extension ID and slot are required");
  const entry = {...extension,generation:revision+1};entries.set(entry.id,entry);notify();
  return () => {if(entries.get(entry.id)===entry){entries.delete(entry.id);notify();}};
}
class ExtensionBoundary extends Component<{children:ReactNode}, {failed:boolean}> {
  state={failed:false};
  static getDerivedStateFromError(){return {failed:true};}
  render(){return this.state.failed ? <span role="status" className="text-xs text-muted-foreground">Extension unavailable</span> : this.props.children;}
}
/** Empty outlets render nothing. Contributions share host context but have independent error boundaries. */
export function ExtensionSlot({name,context,className}:ExtensionSlotProps) {
  useSyncExternalStore(subscribe,()=>revision,()=>revision);
  const visible=[...entries.values()].filter(entry=>{
    if(entry.slot!==name)return false;
    try{return !entry.visible||entry.visible(context);}catch{return false;}
  }).sort((a,b)=>(a.order??0)-(b.order??0)||a.id.localeCompare(b.id));
  if(!visible.length)return null;
  return <div data-extension-slot={name} className={className}>{visible.map(entry=>{
    const Body=entry.component;
    return <ExtensionBoundary key={`${entry.id}:${entry.generation}:${context.surface}:${context.route??""}:${context.workspaceKey??""}:${context.kind??""}:${context.name??""}:${context.recordId??""}`}><Body context={context}/></ExtensionBoundary>;
  })}</div>;
}
export const extensions=Object.freeze({register:registerExtension,Slot:ExtensionSlot});
