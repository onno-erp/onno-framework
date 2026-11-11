package su.onno.crm.web;

import java.security.Principal;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.transaction.annotation.Transactional;
import su.onno.crm.domain.*;
import su.onno.crm.repository.*;
import su.onno.crm.service.*;
import su.onno.types.Ref;
import su.onno.ui.CurrentUserResolver;
import su.onno.ui.UiAccessService;
import su.onno.ui.comments.CommentService;
import su.onno.ui.comments.CommentAuthorAvatars;

/** Contact history includes only conversations readable by the requesting user. */
@RestController
@RequestMapping("/api/crm/contacts/{customer}/activity")
public class CrmActivityController {
    @org.springframework.beans.factory.annotation.Autowired private CrmFeedQuery feeds;
    private final CrmContactService contacts;
    private final ConversationMessageRepository messages;
    private final InboxRepository inboxes;
    private final CrmInboxWorkspaceService workspaces;
    private final UiAccessService access;
    private final CurrentUserResolver users;
    private final CommentService comments;
    private final CommentAuthorAvatars authorAvatars;
    private final su.onno.crm.service.CrmAttachments attachments;
    // The repository beans are registered by OnnoJdbcRepositoriesRegistrar at runtime.
    @SuppressWarnings("SpringJavaInjectionPointsAutowiringInspection")
    public CrmActivityController(CrmContactService contacts,ConversationRepository ignoredConversations,
            ConversationMessageRepository messages,InboxRepository inboxes,CrmInboxWorkspaceService workspaces,
            UiAccessService access,CurrentUserResolver users,CommentService comments,CommentAuthorAvatars authorAvatars,
            su.onno.crm.service.CrmAttachments attachments) {
        this.contacts=contacts;this.messages=messages;this.inboxes=inboxes;
        this.workspaces=workspaces;this.access=access;this.users=users;this.comments=comments;this.authorAvatars=authorAvatars;
        this.attachments=attachments;
    }
    @org.springframework.beans.factory.annotation.Value("${onno.ui.read-only:false}") private boolean readOnly;

    /**
     * {@code attachments} carries the files on a message, empty for a note and for anything that has
     * none. This feed is what the chat pane renders from, not just what a contact's history shows,
     * so a reply's files have to arrive here or they are nowhere.
     */
    public record Entry(String id,UUID conversationId,String subject,String channel,String kind,
                        String direction,String authorName,String body,LocalDateTime at,String deliveryStatus,String authorAvatarUrl,boolean mine,String accountLabel,
                        List<su.onno.crm.service.CrmAttachments.View> attachments,
                        /* Sanitized email HTML; null for plain-text messages and notes. Render isolated. */
                        String bodyHtml) {
        public Entry {
            attachments=attachments==null?List.of():List.copyOf(attachments);
        }
        @SuppressWarnings("unused") // Compatibility constructor for Java clients.
        public Entry(String id,UUID conversationId,String subject,String channel,String kind,String direction,
                String authorName,String body,LocalDateTime at,String deliveryStatus,String authorAvatarUrl,boolean mine,String accountLabel,
                List<su.onno.crm.service.CrmAttachments.View> attachments) {
            this(id,conversationId,subject,channel,kind,direction,authorName,body,at,deliveryStatus,authorAvatarUrl,mine,accountLabel,attachments,null);
        }
        public Entry(String id,UUID conversationId,String subject,String channel,String kind,String direction,
                String authorName,String body,LocalDateTime at,String deliveryStatus,String authorAvatarUrl,boolean mine,String accountLabel) {
            this(id,conversationId,subject,channel,kind,direction,authorName,body,at,deliveryStatus,authorAvatarUrl,mine,accountLabel,List.of());
        }
        public Entry(String id,UUID conversationId,String subject,String channel,String kind,String direction,
                String authorName,String body,LocalDateTime at,String deliveryStatus,String authorAvatarUrl,boolean mine) {
            this(id,conversationId,subject,channel,kind,direction,authorName,body,at,deliveryStatus,authorAvatarUrl,mine,null,List.of());
        }
    }
    public record Feed(List<Entry> rows, String nextCursor, boolean hasMore) {
        /** Deprecated alias for one release. */
        @com.fasterxml.jackson.annotation.JsonProperty("entries")
        public List<Entry> entries() { return rows; }
    }
    private void require(Principal principal){
        workspaces.requireAccess(principal,false);
    }
    /** Source-compatible Java overload; the HTTP API uses cursor. */
    public Feed read(UUID customer,int offset,int limit,Principal principal) {
        return read(customer,offset,limit,null,principal);
    }
    @GetMapping public Feed read(@PathVariable UUID customer,@RequestParam(defaultValue="0") int offset,
            @RequestParam(defaultValue="100") int limit,@RequestParam(required=false) String cursor,Principal principal) {
        require(principal);
        if(offset<0)throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"offset must be non-negative");
        UUID canonical=contacts.canonical(customer);
        contacts.requireRead(canonical,principal);
        var redirects=contacts.redirects();
        var me=users.resolve(principal);
        Map<String,String> messageAvatars=new HashMap<>();
        Map<UUID,Conversation> selectedConversations=new HashMap<>();
        Map<UUID,String> accountLabels=new HashMap<>();
        String context="crm-activity:"+canonical+":"+principal.getName()+":"+new TreeSet<>(access.roles(principal));
        var page=feeds.activity(cursor,limit,offset,context,(handle,sink)->feeds.scan(handle,batch->{
            var related=batch.stream().filter(c->c.getCustomer()!=null
                    && canonical.equals(CrmContactService.canonical(c.getCustomer(),redirects))).toList();
            for(var conversation:workspaces.accessible(related,principal,false))sink.add(conversation.getId(),null);
        }),row->{
            UUID conversationId=(UUID)row.get("_conversation");
            var conversation=selectedConversations.computeIfAbsent(conversationId,
                    id->workspaces.requireConversation(id,principal,false));
            String channel=conversation.getChannel();
            String accountLabel=accountLabels.computeIfAbsent(conversationId,id->conversation.getInbox()==null ? channel
                    : inboxes.findActiveById(conversation.getInbox().id())
                        .map(account->account.getDescription()+" · "+account.getAddress()).orElse(channel));
            UUID id=(UUID)row.get("_id");
            if("message".equals(row.get("_source"))) {
                var message=messages.findActiveById(id).orElseThrow(()->new ResponseStatusException(HttpStatus.NOT_FOUND));
                String author=message.getAuthorId();
                if(author!=null && !messageAvatars.containsKey(author))messageAvatars.putAll(authorAvatars.avatarsFor(List.of(author)));
                return new Entry("message:"+id,conversationId,conversation.getSubject(),channel,
                        message.getKind().name(),message.getDirection().name(),message.getAuthorName(),message.getBody(),
                        message.getSentAt(),message.getDeliveryStatus().name(),author==null?null:messageAvatars.get(author),
                        author!=null&&author.equals(me.recordId()),accountLabel,attachments.viewsOf(message),message.getBodyHtml());
            }
            var note=comments.find(id).orElseThrow(()->new ResponseStatusException(HttpStatus.NOT_FOUND));
            String author=note.authorId();
            if(author!=null && !messageAvatars.containsKey(author))messageAvatars.putAll(authorAvatars.avatarsFor(List.of(author)));
            return new Entry("note:"+id,conversationId,conversation.getSubject(),channel,"NOTE","INTERNAL",
                    note.authorName(),note.body(),LocalDateTime.ofInstant(note.createdAt(),ZoneId.systemDefault()),"NOT_APPLICABLE",
                    author==null?null:messageAvatars.get(author),author!=null&&author.equals(me.recordId()),accountLabel);
        });
        return new Feed(page.rows(),page.nextCursor(),page.hasMore());
    }
    public enum EventType {
        QUOTED("Quoted"), CALL_PLANNED("Call planned"), CALL_COMPLETED("Call completed"),
        MEETING_PLANNED("Meeting planned"), OTHER("Activity");
        final String label;EventType(String label){this.label=label;}
    }
    public record LogEvent(UUID conversationId,EventType type,String details,LocalDateTime scheduledFor) {}
    @PostMapping @Transactional public Map<String,UUID> log(@PathVariable UUID customer,@RequestBody LogEvent request,Principal principal) {
        require(principal);
        if(readOnly)throw new ResponseStatusException(HttpStatus.FORBIDDEN,"The application is read-only");
        if(request.conversationId()==null||request.type()==null||request.details()==null||request.details().isBlank()||request.details().length()>7000)
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,"Choose an event type and enter details (up to 7000 characters)");
        workspaces.requireCustomer(customer,principal,true);
        var conversation=workspaces.requireConversation(request.conversationId(),principal,true);
        if(conversation.getCustomer()==null||!contacts.canonical(customer).equals(contacts.canonical(conversation.getCustomer())))
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,"Conversation belongs to another contact");
        String body=request.type().label;
        if(request.scheduledFor()!=null)body+=" · Scheduled for "+request.scheduledFor().format(DateTimeFormatter.ofPattern("dd MMM yyyy HH:mm",Locale.ENGLISH));
        body+="\n"+request.details().strip();
        var event=new ConversationMessage();event.setConversation(Ref.of(Conversation.class,conversation.getId()));
        event.setKind(MessageKind.SYSTEM_EVENT);event.setDirection(MessageDirection.INTERNAL);event.setChannel(conversation.getChannel());
        event.setAuthorName(users.resolve(principal).displayName());event.setBody(body);event.setDescription(request.type().label);
        event.setDeliveryStatus(DeliveryStatus.NOT_APPLICABLE);
        var saved = messages.save(event);
        return Map.of("id", Objects.requireNonNull(saved.getId(), "Saved event must have an id"));
    }
}
