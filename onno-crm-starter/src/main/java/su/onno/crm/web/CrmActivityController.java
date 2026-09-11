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
import su.onno.ui.comments.Comment;

/** Contact history includes only conversations readable by the requesting user. */
@RestController
@RequestMapping("/api/crm/contacts/{customer}/activity")
public class CrmActivityController {
    private final CrmContactService contacts;
    private final ConversationRepository conversations;
    private final ConversationMessageRepository messages;
    private final InboxRepository inboxes;
    private final CrmInboxWorkspaceService workspaces;
    private final UiAccessService access;
    private final CurrentUserResolver users;
    private final CommentService comments;
    private final CommentAuthorAvatars authorAvatars;
    public CrmActivityController(CrmContactService contacts,ConversationRepository conversations,
            ConversationMessageRepository messages,InboxRepository inboxes,CrmInboxWorkspaceService workspaces,
            UiAccessService access,CurrentUserResolver users,CommentService comments,CommentAuthorAvatars authorAvatars) {
        this.contacts=contacts;this.conversations=conversations;this.messages=messages;this.inboxes=inboxes;
        this.workspaces=workspaces;this.access=access;this.users=users;this.comments=comments;this.authorAvatars=authorAvatars;
    }
    @org.springframework.beans.factory.annotation.Value("${onno.ui.read-only:false}") private boolean readOnly;
    public record Entry(String id,UUID conversationId,String subject,String channel,String kind,
                        String direction,String authorName,String body,LocalDateTime at,String deliveryStatus,String authorAvatarUrl,boolean mine,String accountLabel) {
        public Entry(String id,UUID conversationId,String subject,String channel,String kind,String direction,
                String authorName,String body,LocalDateTime at,String deliveryStatus,String authorAvatarUrl,boolean mine) {
            this(id,conversationId,subject,channel,kind,direction,authorName,body,at,deliveryStatus,authorAvatarUrl,mine,null);
        }
    }
    public record Feed(List<Entry> entries,int total,boolean hasMore) {}
    private void require(Principal principal){
        workspaces.requireAccess(principal,false);
    }
    @GetMapping public Feed read(@PathVariable UUID customer,@RequestParam(defaultValue="0") int offset,
                                @RequestParam(defaultValue="100") int limit,Principal principal) {
        require(principal);
        if(offset<0||limit<1||limit>500)throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Invalid activity page");
        UUID canonical=contacts.canonical(customer);workspaces.requireCustomer(canonical,principal,false);
        List<Entry> entries=new ArrayList<>();
        var me=users.resolve(principal);
        // Agent replies get their author's photo here too, so one contact's history reads the same
        // whether it is opened as a timeline or as a chat.
        Map<String,String> messageAvatars=new HashMap<>();
        for(var conversation:conversations.findAllActive()) {
            if(conversation.getCustomer()==null||!canonical.equals(contacts.canonical(conversation.getCustomer()))||
                    !workspaces.canAccess(conversation,principal,false))continue;
            String channel=conversation.getChannel();
            String accountLabel=conversation.getInbox()==null ? channel : inboxes.findActiveById(conversation.getInbox().id())
                .map(account -> account.getDescription()+" · "+account.getAddress()).orElse(channel);
            var thread=messages.findByConversationAndDeletionMarkFalseOrderBySentAtAsc(Ref.of(Conversation.class,conversation.getId()));
            var unresolved=thread.stream().map(ConversationMessage::getAuthorId).filter(Objects::nonNull)
                .filter(id->!messageAvatars.containsKey(id)).distinct().toList();
            if(!unresolved.isEmpty())messageAvatars.putAll(authorAvatars.avatarsFor(unresolved));
            for(var message:thread)
                entries.add(new Entry("message:"+message.getId(),conversation.getId(),conversation.getSubject(),channel,
                    message.getKind().name(),message.getDirection().name(),message.getAuthorName(),message.getBody(),
                    message.getSentAt(),message.getDeliveryStatus().name(),
                    message.getAuthorId()==null?null:messageAvatars.get(message.getAuthorId()),
                    message.getAuthorId()!=null&&message.getAuthorId().equals(me.recordId()),accountLabel));
            var notes=comments.list("catalogs","crm_conversations",conversation.getId());
            var avatars=authorAvatars.avatarsFor(notes.stream().map(Comment::authorId).filter(Objects::nonNull).toList());
            for(var note:notes)
                entries.add(new Entry("note:"+note.id(),conversation.getId(),conversation.getSubject(),channel,"NOTE","INTERNAL",
                    note.authorName(),note.body(),LocalDateTime.ofInstant(note.createdAt(),ZoneId.systemDefault()),"NOT_APPLICABLE",
                    note.authorId()==null?null:avatars.get(note.authorId()),
                    note.authorId()!=null&&note.authorId().equals(me.recordId()),accountLabel));
        }
        entries.sort(Comparator.comparing(Entry::at).thenComparing(Entry::id).reversed());
        int end=(int)Math.min(entries.size(),(long)offset+limit);
        return new Feed(entries.subList(Math.min(offset,entries.size()),end),entries.size(),end<entries.size());
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
        event.setDeliveryStatus(DeliveryStatus.NOT_APPLICABLE);messages.save(event);
        return Map.of("id",event.getId());
    }
}
