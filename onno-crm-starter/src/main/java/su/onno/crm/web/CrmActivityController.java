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
    public record Entry(String id,UUID conversationId,String subject,String channel,String kind,
                        String direction,String authorName,String body,LocalDateTime at,String deliveryStatus,String authorAvatarUrl,boolean mine) {}
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
        for(var conversation:conversations.findAllActive()) {
            if(conversation.getCustomer()==null||!canonical.equals(contacts.canonical(conversation.getCustomer()))||
                    !workspaces.canAccess(conversation,principal,false))continue;
            String channel=conversation.getChannel();
            for(var message:messages.findByConversationAndDeletionMarkFalseOrderBySentAtAsc(Ref.of(Conversation.class,conversation.getId())))
                entries.add(new Entry("message:"+message.getId(),conversation.getId(),conversation.getSubject(),channel,
                    message.getKind().name(),message.getDirection().name(),message.getAuthorName(),message.getBody(),
                    message.getSentAt(),message.getDeliveryStatus().name(),null,false));
            var notes=comments.list("catalogs","crm_conversations",conversation.getId());
            var avatars=authorAvatars.avatarsFor(notes.stream().map(Comment::authorId).filter(Objects::nonNull).toList());
            for(var note:notes)
                entries.add(new Entry("note:"+note.id(),conversation.getId(),conversation.getSubject(),channel,"NOTE","INTERNAL",
                    note.authorName(),note.body(),LocalDateTime.ofInstant(note.createdAt(),ZoneId.systemDefault()),"NOT_APPLICABLE",
                    note.authorId()==null?null:avatars.get(note.authorId()),
                    note.authorId()!=null&&note.authorId().equals(me.recordId())));
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
