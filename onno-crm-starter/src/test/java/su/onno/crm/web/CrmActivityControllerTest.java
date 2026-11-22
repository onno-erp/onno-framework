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
import su.onno.web.*;
import su.onno.service.*;
import su.onno.web.comments.CommentService;
import su.onno.types.Ref;
class CrmActivityControllerTest {
 final CrmContactService contacts=mock(CrmContactService.class);
 final ConversationRepository conversations=mock(ConversationRepository.class);
 final ConversationMessageRepository messages=mock(ConversationMessageRepository.class);
 final InboxRepository inboxes=mock(InboxRepository.class);
 final CrmInboxWorkspaceService workspaces=mock(CrmInboxWorkspaceService.class);
 final AccessService access=su.onno.crm.TestBindings.withGrants(mock(AccessService.class));
 final CurrentUserResolver users=mock(CurrentUserResolver.class);
 final CommentService comments=mock(CommentService.class);
 final su.onno.web.comments.CommentAuthorAvatars avatars=mock(su.onno.web.comments.CommentAuthorAvatars.class);
 /** Every key resolves; what is under test is whether the files reach the feed at all. */
 final su.onno.crm.service.CrmAttachments attachments=new su.onno.crm.service.CrmAttachments(
   new su.onno.web.media.MediaStorage(){
    public su.onno.web.media.StoredMedia store(java.io.InputStream c,String filename,String type,long size){throw new UnsupportedOperationException();}
    @Override public Optional<su.onno.web.media.LoadedMedia> load(String key){
     return Optional.of(new su.onno.web.media.LoadedMedia(new org.springframework.core.io.ByteArrayResource("file".getBytes()),
       key.endsWith(".png")?"image/png":"application/pdf",4,key.substring(key.lastIndexOf('/')+1)));}
   },new su.onno.web.media.MediaProperties());
 final WebProperties ui=new WebProperties();
 org.jdbi.v3.core.Jdbi jdbc;
 String conversationTable,messageTable;
 final Map<UUID,Conversation> records=new HashMap<>();
 final Principal principal=()->"agent";
 final UUID customer=UUID.randomUUID();
 CrmActivityController controller(){when(access.hasAnyRole(eq(principal),any())).thenReturn(true);
  // The controller filters in one batch; the per-row stubs below stay the source of truth.
  when(workspaces.accessible(any(),eq(principal),anyBoolean())).thenAnswer(i->i.<List<Conversation>>getArgument(0).stream()
    .filter(c->workspaces.canAccess(c,principal,i.getArgument(2))).toList());when(contacts.canonical(any())).thenAnswer(i->i.getArgument(0));
  var ds=new org.h2.jdbcx.JdbcDataSource();ds.setURL("jdbc:h2:mem:"+UUID.randomUUID()+";DB_CLOSE_DELAY=-1");jdbc=org.jdbi.v3.core.Jdbi.create(ds);
  var registry=new su.onno.metadata.MetadataRegistry();var scanner=new su.onno.metadata.MetadataScanner(new su.onno.metadata.DefaultNamingStrategy());
  var cd=scanner.scan(Conversation.class);var md=scanner.scan(ConversationMessage.class);registry.registerCatalog(cd);registry.registerCatalog(md);
  conversationTable=cd.tableName();messageTable=md.tableName();
  jdbc.useHandle(h->{h.execute("CREATE TABLE "+conversationTable+" (_id UUID PRIMARY KEY,_deletion_mark BOOLEAN)");
   h.execute("CREATE TABLE "+messageTable+" (_id UUID PRIMARY KEY,_deletion_mark BOOLEAN,conversation UUID,sent_at TIMESTAMP)");
   h.execute("CREATE TABLE onno_comments (_id UUID PRIMARY KEY,_entity_id UUID,_created_at TIMESTAMP,_deleted BOOLEAN,_entity_type VARCHAR,_entity_name VARCHAR)");});
  when(conversations.findAllById(any())).thenAnswer(i->{var found=new ArrayList<Conversation>();for(UUID id:i.<Iterable<UUID>>getArgument(0))if(records.containsKey(id))found.add(records.get(id));return found;});
  when(workspaces.requireConversation(any(UUID.class),eq(principal),eq(false))).thenAnswer(i->records.get(i.<UUID>getArgument(0)));
  var controller=new CrmActivityController(contacts,conversations,messages,inboxes,workspaces,access,users,comments,avatars,attachments,ui);
  org.springframework.test.util.ReflectionTestUtils.setField(controller,"feeds",new CrmFeedQuery(jdbc,conversations,registry));return controller;}
 Conversation conversation(String channel){var c=new Conversation();c.setId(UUID.randomUUID());c.setCustomer(customer);c.setChannel(channel);records.put(c.getId(),c);jdbc.useHandle(h->h.execute("INSERT INTO "+conversationTable+" VALUES (?,false)",c.getId()));return c;}
 ConversationMessage message(Conversation c,String body){var m=new ConversationMessage();m.setId(UUID.randomUUID());m.setConversation(Ref.of(Conversation.class,c.getId()));m.setKind(MessageKind.CUSTOMER_MESSAGE);m.setDirection(MessageDirection.INBOUND);m.setChannel(c.getChannel());m.setBody(body);m.setSentAt(LocalDateTime.now());jdbc.useHandle(h->h.execute("INSERT INTO "+messageTable+" VALUES (?,false,?,?)",m.getId(),c.getId(),m.getSentAt()));when(messages.findActiveById(m.getId())).thenReturn(Optional.of(m));return m;}
 @Test void combinesChannelsWithoutReadingHiddenConversations(){
  var controller=controller();var first=conversation(Channel.EMAIL);var second=conversation(Channel.WHATSAPP);var hidden=conversation(Channel.TELEGRAM);
  when(conversations.findAllActive()).thenReturn(List.of(first,second,hidden));when(workspaces.canAccess(first,principal,false)).thenReturn(true);when(workspaces.canAccess(second,principal,false)).thenReturn(true);
  message(first,"Email");
  message(second,"WhatsApp");
  var feed=controller.read(customer,100,null,principal);assertThat(feed.rows()).extracting(CrmActivityController.Entry::body).containsExactly("WhatsApp","Email");
  verify(messages,never()).findByConversationAndDeletionMarkFalseOrderBySentAtAsc(Ref.of(Conversation.class,hidden.getId()));verify(comments,never()).list(any(),any(),eq(hidden.getId()));
  assertThat(controller.read(customer,1,null,principal).hasMore()).isTrue();
 }
 /**
  * The chat pane renders from this feed rather than from /conversations/{id}/messages, so a reply's
  * files have to arrive on the entry. A files-only reply has no body at all: were they missing here,
  * the message would render as an empty bubble with no way to reach what was sent.
  */
 @Test void messageEntriesCarryTheirFiles() {
  var controller=controller();var c=conversation(Channel.TELEGRAM);
  when(conversations.findAllActive()).thenReturn(List.of(c));when(workspaces.canAccess(c,principal,false)).thenReturn(true);
  when(users.resolve(principal)).thenReturn(mock(CurrentUserResolver.CurrentUser.class));
  var reply=message(c,"");reply.setKind(MessageKind.AGENT_REPLY);reply.setDirection(MessageDirection.OUTBOUND);
  reply.setAttachments("/api/media/2026/09/quote.pdf\n/api/media/2026/09/plan.png");
  when(messages.findByConversationAndDeletionMarkFalseOrderBySentAtAsc(Ref.of(Conversation.class,c.getId()))).thenReturn(List.of(reply));
  var entry=controller.read(customer,100,null,principal).rows().getFirst();
  assertThat(entry.attachments()).extracting(su.onno.crm.service.CrmAttachments.View::filename)
    .containsExactly("quote.pdf","plan.png");
  assertThat(entry.attachments()).extracting(su.onno.crm.service.CrmAttachments.View::image)
    .containsExactly(false,true);
 }
 @Test void preservesNoteOwnershipAndLiveAuthorPhotos() {
  var controller=controller();var c=conversation(Channel.WHATSAPP);
  when(conversations.findAllActive()).thenReturn(List.of(c));when(workspaces.canAccess(c,principal,false)).thenReturn(true);
  var me=mock(CurrentUserResolver.CurrentUser.class);when(me.recordId()).thenReturn("alice-id");when(users.resolve(principal)).thenReturn(me);
  var now=Instant.now();
  var notes=List.of(
    new su.onno.web.comments.Comment(UUID.randomUUID(),"catalogs","crm_conversations",c.getId(),"alice-id","Alice","Mine",null,now,null),
    new su.onno.web.comments.Comment(UUID.randomUUID(),"catalogs","crm_conversations",c.getId(),"ben-id","Alice","Other author with same name",null,now.minusSeconds(1),null),
    new su.onno.web.comments.Comment(UUID.randomUUID(),"catalogs","crm_conversations",c.getId(),null,"Admin","Unlinked",null,now.minusSeconds(2),null));
  for(var note:notes){jdbc.useHandle(h->h.execute("INSERT INTO onno_comments VALUES (?,?,?,false,'catalogs','crm_conversations')",note.id(),c.getId(),java.sql.Timestamp.from(note.createdAt())));when(comments.find(note.id())).thenReturn(Optional.of(note));}
  when(avatars.avatarsFor(any())).thenReturn(Map.of("alice-id","/media/alice.png","ben-id","/media/ben.png"));
  var feed=controller.read(customer,100,null,principal);
  assertThat(feed.rows()).extracting(CrmActivityController.Entry::mine).containsExactly(true,false,false);
  assertThat(feed.rows()).extracting(CrmActivityController.Entry::authorAvatarUrl).containsExactly("/media/alice.png","/media/ben.png",null);
 }
 @Test void accountContextComesOnlyFromReadableConversations() {
  var controller=controller();var visible=conversation(Channel.EMAIL);var hidden=conversation(Channel.EMAIL);
  var inbox=new Inbox();inbox.setId(UUID.randomUUID());inbox.setDescription("Sales");inbox.setAddress("sales@example.com");
  visible.setInbox(Ref.of(Inbox.class,inbox.getId()));hidden.setInbox(Ref.of(Inbox.class,UUID.randomUUID()));
  when(inboxes.findActiveById(inbox.getId())).thenReturn(Optional.of(inbox));
  when(conversations.findAllActive()).thenReturn(List.of(visible,hidden));when(workspaces.canAccess(visible,principal,false)).thenReturn(true);
  message(visible,"Hello");
  assertThat(controller.read(customer,100,null,principal).rows()).extracting(CrmActivityController.Entry::accountLabel).containsExactly("Sales · sales@example.com");
  verify(inboxes,never()).findActiveById(hidden.getInbox().id());
 }
 @Test void readOnlyModeRejectsManualActivities() {
  var controller=controller();ui.setReadOnly(true);
  assertThatThrownBy(()->controller.log(customer,new CrmActivityController.LogEvent(UUID.randomUUID(),CrmActivityController.EventType.CALL_COMPLETED,"Notes",null),principal))
    .isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
  verifyNoInteractions(messages);
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
