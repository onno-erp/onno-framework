import { Component, type ReactNode } from "react";
import type { ExtensionSlotProps } from "./extension-types";
import { useAppConfig } from "./app-config";

class ExtensionBoundary extends Component<{children:ReactNode}, {failed:boolean}> {
  state={failed:false};
  static getDerivedStateFromError(){return {failed:true};}
  render(){return this.state.failed ? <span role="status" className="text-xs text-muted-foreground">Extension unavailable</span> : this.props.children;}
}

/**
 * A named outlet for the app's contributions (`defineApp({ extensions })`). Renders each
 * contribution declared for this slot whose `visible` predicate accepts the context, in `order`;
 * an outlet with none renders nothing. Contributions share the host context but each has its own
 * error boundary, so a throwing one degrades to a small notice instead of taking the surface down.
 */
export function ExtensionSlot({name,context,className}:ExtensionSlotProps) {
  const extensions = useAppConfig().app.extensions ?? [];
  const visible=extensions.filter(entry=>{
    if(entry.slot!==name)return false;
    try{return !entry.visible||entry.visible(context);}catch{return false;}
  }).sort((a,b)=>(a.order??0)-(b.order??0)||a.id.localeCompare(b.id));
  if(!visible.length)return null;
  return <div data-extension-slot={name} className={className}>{visible.map(entry=>{
    const Body=entry.component;
    return <ExtensionBoundary key={`${entry.id}:${context.surface}:${context.route??""}:${context.workspaceKey??""}:${context.kind??""}:${context.name??""}:${context.recordId??""}`}><Body context={context}/></ExtensionBoundary>;
  })}</div>;
}
