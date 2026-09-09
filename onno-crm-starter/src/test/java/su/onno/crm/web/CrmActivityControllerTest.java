package su.onno.crm.web;
import org.junit.jupiter.api.Test;
import static org.mockito.Mockito.*;
import static org.assertj.core.api.Assertions.*;
import java.util.*;
import java.time.*;
import java.security.Principal;
import su.onno.crm.domain.*;
import su.onno.crm.repository.*;
import su.onno.crm.service.*;
import su.onno.ui.*;
import su.onno.ui.comments.CommentService;
import su.onno.types.Ref;
class CrmActivityControllerTest {
 final CrmContactService contacts=mock(CrmContactService.class);
 final ConversationRepository conversations=mock(ConversationRepository.class);
 final ConversationMessageRepository messages=mock(ConversationMessageRepository.class);
 final InboxRepository inboxes=mock(InboxRepository.class);
 final CrmInboxWorkspaceService workspaces=mock(CrmInboxWorkspaceService.class);
 final UiAccessService access=mock(UiAccessService.class);
 final CurrentUserResolver users=mock(CurrentUserResolver.class);
 final CommentService comments=mock(CommentService.class);
 final su.onno.ui.comments.CommentAuthorAvatars avatars=mock(su.onno.ui.comments.CommentAuthorAvatars.class);
 final Principal principal=()->"agent";
 final UUID customer=UUID.randomUUID();
 CrmActivityController controller(){when(access.hasAnyRole(eq(principal),any())).thenReturn(true);when(contacts.canonical(any())).thenAnswer(i->i.getArgument(0));return new CrmActivityController(contacts,conversations,messages,inboxes,workspaces,access,users,comments,avatars);}
 Conversation conversation(String channel){var c=new Conversation();c.setId(UUID.randomUUID());c.setCustomer(customer);c.setChannel(channel);return c;}
 ConversationMessage message(Conversation c,String body){var m=new ConversationMessage();m.setId(UUID.randomUUID());m.setConversation(Ref.of(Conversation.class,c.getId()));m.setKind(MessageKind.CUSTOMER_MESSAGE);m.setDirection(MessageDirection.INBOUND);m.setChannel(c.getChannel());m.setBody(body);m.setSentAt(LocalDateTime.now());return m;}
 @Test void combinesChannelsWithoutReadingHiddenConversations(){
  var controller=controller();var first=conversation(Channel.EMAIL);var second=conversation(Channel.WHATSAPP);var hidden=conversation(Channel.TELEGRAM);
  when(conversations.findAllActive()).thenReturn(List.of(first,second,hidden));when(workspaces.canAccess(first,principal,false)).thenReturn(true);when(workspaces.canAccess(second,principal,false)).thenReturn(true);
  when(messages.findByConversationAndDeletionMarkFalseOrderBySentAtAsc(Ref.of(Conversation.class,first.getId()))).thenReturn(List.of(message(first,"Email")));
  when(messages.findByConversationAndDeletionMarkFalseOrderBySentAtAsc(Ref.of(Conversation.class,second.getId()))).thenReturn(List.of(message(second,"WhatsApp")));
  var feed=controller.read(customer,0,100,principal);assertThat(feed.entries()).extracting(CrmActivityController.Entry::body).containsExactly("WhatsApp","Email");
  verify(messages,never()).findByConversationAndDeletionMarkFalseOrderBySentAtAsc(Ref.of(Conversation.class,hidden.getId()));verify(comments,never()).list(any(),any(),eq(hidden.getId()));
  assertThat(controller.read(customer,0,1,principal).hasMore()).isTrue();
 }
 @Test void preservesNoteOwnershipAndLiveAuthorPhotos() {
  var controller=controller();var c=conversation(Channel.WHATSAPP);
  when(conversations.findAllActive()).thenReturn(List.of(c));when(workspaces.canAccess(c,principal,false)).thenReturn(true);
  var me=mock(CurrentUserResolver.CurrentUser.class);when(me.recordId()).thenReturn("alice-id");when(users.resolve(principal)).thenReturn(me);
  var now=Instant.now();
  when(comments.list("catalogs","crm_conversations",c.getId())).thenReturn(List.of(
    new su.onno.ui.comments.Comment(UUID.randomUUID(),"catalogs","crm_conversations",c.getId(),"alice-id","Alice","Mine",null,now,null),
    new su.onno.ui.comments.Comment(UUID.randomUUID(),"catalogs","crm_conversations",c.getId(),"ben-id","Alice","Other author with same name",null,now.minusSeconds(1),null),
    new su.onno.ui.comments.Comment(UUID.randomUUID(),"catalogs","crm_conversations",c.getId(),null,"Admin","Unlinked",null,now.minusSeconds(2),null)));
  when(avatars.avatarsFor(any())).thenReturn(Map.of("alice-id","/media/alice.png","ben-id","/media/ben.png"));
  var feed=controller.read(customer,0,100,principal);
  assertThat(feed.entries()).extracting(CrmActivityController.Entry::mine).containsExactly(true,false,false);
  assertThat(feed.entries()).extracting(CrmActivityController.Entry::authorAvatarUrl).containsExactly("/media/alice.png","/media/ben.png",null);
 }
 @Test void logsAnInternalEventAndRejectsOtherContacts(){
  var controller=controller();var c=conversation(Channel.WHATSAPP);when(workspaces.requireConversation(c.getId(),principal,true)).thenReturn(c);
  var me=mock(CurrentUserResolver.CurrentUser.class);when(me.displayName()).thenReturn("Agent");when(users.resolve(principal)).thenReturn(me);
  when(messages.save(any(ConversationMessage.class))).thenAnswer(i->{ConversationMessage m=i.getArgument(0);m.setId(UUID.randomUUID());return m;});
  var request=new CrmActivityController.LogEvent(c.getId(),CrmActivityController.EventType.CALL_PLANNED,"Discuss quote",LocalDateTime.of(2026,9,8,10,0));controller.log(customer,request,principal);
  var saved=org.mockito.ArgumentCaptor.forClass(ConversationMessage.class);verify(messages).save(saved.capture());assertThat(saved.getValue().getKind()).isEqualTo(MessageKind.SYSTEM_EVENT);assertThat(saved.getValue().getDirection()).isEqualTo(MessageDirection.INTERNAL);assertThat(saved.getValue().getBody()).contains("Call planned","08 Sep 2026 10:00","Discuss quote");
  assertThatThrownBy(()->controller.log(UUID.randomUUID(),request,principal)).isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
 }
}
