import {expect,it} from "vitest";
import {composerState} from "../../../../../onno-crm-starter/src/main/widgets/composerState";
it("blocks replies during loading, errors, sending and unavailable channels while retaining the provider reason",()=>{
 const ready={canReply:true,busy:false,loaded:true,failed:false,delivery:{connected:true,label:"Email"}};
 expect(composerState(ready).disabled).toBe(false);
 expect(composerState({...ready,loaded:false})).toMatchObject({disabled:true,retry:false});
 expect(composerState({...ready,failed:true})).toMatchObject({disabled:true,retry:true});
 expect(composerState({...ready,busy:true})).toMatchObject({disabled:true,message:"Sending…"});
 expect(composerState({...ready,canReply:false}).disabled).toBe(true);
 expect(composerState({...ready,delivery:{connected:false,label:"The reply window has expired"}})).toMatchObject({disabled:true,message:"The reply window has expired",retry:true});
});

it("distinguishes a connected read-only channel from an outage and a closed reply window",()=>{
 const base={canReply:true,busy:false,loaded:true,failed:false};
 expect(composerState({...base,delivery:{connected:true,label:"Archive",replyCapability:"READ_ONLY"}})).toMatchObject({placeholder:"Read-only channel",disabled:true,retry:false});
 expect(composerState({...base,delivery:{connected:false,label:"Disconnected",replyCapability:"AVAILABLE"}})).toMatchObject({placeholder:"Channel unavailable",disabled:true,retry:true});
 expect(composerState({...base,delivery:{connected:true,label:"Chat",replyCapability:"WINDOW_CLOSED"}})).toMatchObject({placeholder:"Reply window closed",disabled:true});
 expect(composerState({...base,canReply:false,delivery:{connected:true,label:"Chat"}})).toMatchObject({placeholder:"Read-only conversation",retry:false});
});
