import { expect, it } from "vitest";
import { contactChats, replySelection } from "../../../../../onno-crm-starter/src/main/widgets/contactChats";
it("shows the most recently used account and preview, regardless of channel ordering", () => {
  const wa = {id:"wa",customer:"mike",lastMessageAt:"2026-09-07T12:00:00Z",inboxDisplay:"WhatsApp · +1555",unreadCount:2,lastMessagePreview:"old"};
  const email = {id:"email",customer:"mike",lastMessageAt:"2026-09-07T16:00:00+03:00",inboxDisplay:"Gmail · sales@example.com",unreadCount:1,lastMessagePreview:"latest"};
  expect(contactChats([wa,email])).toEqual([{...email,unreadCount:3}]);
  expect(contactChats([email,wa])).toEqual([{...email,unreadCount:3}]);
  expect(wa.unreadCount).toBe(2);
  expect(contactChats([email,{...wa,lastMessageAt:"2026-09-07T14:00:00Z",lastMessagePreview:"reply"}])[0]).toMatchObject({id:"wa",lastMessagePreview:"reply"});
});

it("opens on the last used channel including outgoing replies, ignoring notes", () => {
 const entries = [{id:"note",conversationId:"email",direction:"INTERNAL"},{id:"reply",conversationId:"wa",direction:"OUTBOUND"},{id:"old",conversationId:"email",direction:"INBOUND"}];
 const initial=replySelection("mike",entries,null,false);
 expect(initial.conversationId).toBe("wa");
 expect(replySelection("mike",entries,initial.state,false).conversationId).toBeUndefined();
 const incoming=[{id:"new",conversationId:"telegram",direction:"INBOUND"},...entries];
 expect(replySelection("mike",incoming,initial.state,false).conversationId).toBe("telegram");
 expect(replySelection("mike",incoming,initial.state,true).conversationId).toBeUndefined();
 expect(replySelection("another",entries,initial.state,false).conversationId).toBe("wa");
});
