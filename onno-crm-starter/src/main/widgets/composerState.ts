export function composerState(input: {
  canReply: boolean; busy: boolean; loaded: boolean; failed: boolean;
  delivery: {connected:boolean;label:string;replyCapability?:"AVAILABLE"|"READ_ONLY"|"WINDOW_CLOSED";replyReason?:string} | null;
}) {
  if (!input.canReply) return {disabled:true, placeholder:"Read-only conversation", message:"You don’t have permission to send replies.", retry:false};
  if (input.busy) return {disabled:true, placeholder:"Sending…", message:"Sending…", retry:false};
  if (input.failed) return {disabled:true, placeholder:"Availability unknown", message:"Could not check whether replies are available.", retry:true};
  if (!input.loaded || !input.delivery) return {disabled:true, placeholder:"Checking availability…", message:"Checking whether replies are available…", retry:false};
  if (input.delivery.replyCapability === "READ_ONLY") return {disabled:true, placeholder:"Read-only channel", message:input.delivery.replyReason || "This channel does not support sending replies.", retry:false};
  if (!input.delivery.connected) return {disabled:true, placeholder:"Channel unavailable", message:input.delivery.label || "This channel is unavailable right now.", retry:true};
  if (input.delivery.replyCapability === "WINDOW_CLOSED") return {disabled:true, placeholder:"Reply window closed", message:input.delivery.replyReason || "Wait for a new incoming message to reply.", retry:true};
  return {disabled:false, placeholder:"Write a reply…", message:"", retry:false};
}
