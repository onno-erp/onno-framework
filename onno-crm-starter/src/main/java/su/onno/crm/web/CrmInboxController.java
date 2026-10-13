package su.onno.crm.web;

import java.security.Principal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import su.onno.crm.domain.Conversation;
import su.onno.crm.domain.ConversationMessage;
import su.onno.crm.service.ConversationService;
import su.onno.crm.service.CrmAgentIdentityResolver;
import su.onno.ui.CurrentUserResolver;
import su.onno.ui.UiAccessService;
import su.onno.ui.comments.CommentAuthorAvatars;

import java.util.Map;
import java.util.Objects;

/** Role-gated CRM command boundary used by the inbox renderer. */
@RestController
@RequestMapping("/api/crm/conversations")
public class CrmInboxController {

    private final ConversationService service;
    private final CurrentUserResolver currentUsers;
    private final CrmAgentIdentityResolver agentIdentities;
    private final UiAccessService access;
    private final su.onno.crm.service.CrmInboxWorkspaceService workspaces;
    private final CommentAuthorAvatars authorAvatars;
    private final su.onno.crm.service.CrmAttachments attachments;
    private final su.onno.crm.repository.ContactIdentityRepository identities;
    private final su.onno.crm.service.CrmContactService contacts;

    public CrmInboxController(
            ConversationService service,
            CurrentUserResolver currentUsers,
            CrmAgentIdentityResolver agentIdentities,
            UiAccessService access, su.onno.crm.service.CrmInboxWorkspaceService workspaces,
            CommentAuthorAvatars authorAvatars,
            su.onno.crm.service.CrmAttachments attachments,
            su.onno.crm.repository.ContactIdentityRepository identities,
            su.onno.crm.service.CrmContactService contacts
    ) {
        this.identities = identities;
        this.contacts = contacts;
        this.service = service;
        this.currentUsers = currentUsers;
        this.agentIdentities = agentIdentities;
        this.access = access;this.workspaces=workspaces;this.attachments=attachments;
        this.authorAvatars = authorAvatars;
    }

    @GetMapping("/{id}/messages")
    public List<MessageView> messages(@PathVariable UUID id, @org.springframework.web.bind.annotation.RequestParam(required=false) String workspace, Principal principal) {
        requireCrmAccess(principal);
        workspaces.requireConversation(workspace,id,principal,false);
        // Resolved for the whole page in one lookup, the way internal notes already do it, so a long
        // thread from one agent costs a single identity read rather than one per bubble.
        List<ConversationMessage> thread = service.messages(id);
        Map<String, String> avatars = authorAvatars.avatarsFor(thread.stream()
                .map(ConversationMessage::getAuthorId).filter(Objects::nonNull).distinct().toList());
        return thread.stream()
                .map(message -> view(message, message.getAuthorId() == null
                        ? null : avatars.get(message.getAuthorId())))
                .toList();
    }

    @PostMapping("/{id}/messages")
    public MessageView send(
            @PathVariable UUID id,
            @RequestBody SendMessage request,
            @org.springframework.web.bind.annotation.RequestParam(required=false) String workspace, Principal principal
    ) {
        requireCrmAccess(principal);
        var conversation=workspaces.requireConversation(workspace,id,principal,true);
        requireVisibleRecipient(conversation,id,request==null?null:request.recipient(),principal);
        var user = currentUsers.resolve(principal);
        return view(service.addMessage(id, new ConversationService.Reply(
                request == null ? null : request.body(),
                request == null ? java.util.List.of() : request.attachments(),
                user.displayName(),
                user.recordId(), request == null ? null : request.subject(), request == null ? null : request.recipient())), user.avatarUrl());
    }

    /**
     * A chosen recipient must be one this principal can see: an address known only from another
     * user's personal inbox is rejected exactly as if it were unknown.
     */
    private void requireVisibleRecipient(Conversation conversation, UUID id, String recipient, Principal principal) {
        if (recipient == null || recipient.isBlank()) return;
        var visible = withoutHiddenRecipients(conversation, service.delivery(id), principal);
        String chosen = recipient.strip();
        boolean known = chosen.equalsIgnoreCase(visible.recipient())
                || visible.recipients().stream().anyMatch(address -> address.equalsIgnoreCase(chosen));
        if (!known) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Choose one of the contact's email addresses");
    }

    @GetMapping("/{id}/delivery")
    public su.onno.crm.service.CrmMessageTransport.Connection delivery(@PathVariable UUID id, @org.springframework.web.bind.annotation.RequestParam(required=false) String workspace, Principal principal) {
        requireCrmAccess(principal);
        var conversation=workspaces.requireConversation(workspace,id,principal,false);
        return withoutHiddenRecipients(conversation,service.delivery(id),principal);
    }

    /**
     * A transport offers every address it knows for the customer. Addresses learned only through
     * another user's personal account are that user's to see, so they are dropped here; the thread's
     * own reply address stays, because the principal can already read this conversation.
     */
    private su.onno.crm.service.CrmMessageTransport.Connection withoutHiddenRecipients(Conversation conversation,
            su.onno.crm.service.CrmMessageTransport.Connection connection, Principal principal) {
        if(connection.recipients()==null || connection.recipients().isEmpty() || conversation.getCustomer()==null)return connection;
        var all=identities.findByCustomerAndDeletionMarkFalse(contacts.canonical(conversation.getCustomer())).stream()
            .filter(identity->su.onno.crm.domain.Channel.EMAIL.equals(identity.getChannel())).toList();
        var visible=workspaces.visibleIdentities(all,principal);
        if(visible.size()==all.size())return connection;
        var allowed=new java.util.TreeSet<String>(String.CASE_INSENSITIVE_ORDER);
        visible.forEach(identity->allowed.add(address(identity.getAddress())));
        var hidden=new java.util.TreeSet<String>(String.CASE_INSENSITIVE_ORDER);
        all.stream().filter(identity->!visible.contains(identity)).map(identity->address(identity.getAddress()))
            .filter(address->!allowed.contains(address) && !address.equalsIgnoreCase(Objects.toString(connection.recipient(),"")))
            .forEach(hidden::add);
        return connection.withRecipients(connection.recipient(),
            connection.recipients().stream().filter(address->!hidden.contains(address(address))).toList());
    }
    private static String address(String value) {
        String text=Objects.toString(value,"").strip();
        int open=text.lastIndexOf('<'),close=text.lastIndexOf('>');
        return (open>=0 && close>open ? text.substring(open+1,close) : text).strip();
    }

    @PostMapping("/{id}/messages/{messageId}/retry")
    public MessageView retry(@PathVariable UUID id, @PathVariable UUID messageId, @org.springframework.web.bind.annotation.RequestParam(required=false) String workspace, Principal principal) {
        requireCrmAccess(principal);
        workspaces.requireConversation(workspace,id,principal,true);
        ConversationMessage retried = service.retryMessage(id, messageId);
        return view(retried, retried.getAuthorId() == null
                ? null : authorAvatars.avatarFor(retried.getAuthorId()));
    }

    public record StatusChange(UUID status) {}
    @PostMapping("/{id}/status")
    public ConversationState changeStatus(@PathVariable UUID id, @RequestBody(required=false) StatusChange change,
            @org.springframework.web.bind.annotation.RequestParam(required=false) String workspace, Principal principal) {
        requireCrmAccess(principal);
        workspaces.requireConversation(workspace,id,principal,true);
        if(change==null || change.status()==null)
            throw new org.springframework.web.server.ResponseStatusException(HttpStatus.BAD_REQUEST,"Choose a conversation status");
        return ConversationState.from(service.changeStatus(id, change.status()));
    }

    @PostMapping("/{id}/read")
    public ConversationState markRead(@PathVariable UUID id, @org.springframework.web.bind.annotation.RequestParam(required=false) String workspace, Principal principal) {
        requireCrmAccess(principal);
        workspaces.requireConversation(workspace,id,principal,true);
        return ConversationState.from(service.markRead(id));
    }

    private void requireCrmAccess(Principal principal) {
        workspaces.requireAccess(principal,false);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    @ResponseStatus(HttpStatus.UNPROCESSABLE_ENTITY)
    public ErrorView invalid(IllegalArgumentException error) {
        return new ErrorView(error.getMessage());
    }

    /** {@code attachments} are media URLs from {@code POST /api/media}, never raw bytes or links. */
    public record SendMessage(String body, List<String> attachments, String subject, String recipient) {
        public SendMessage {
            attachments = attachments == null ? List.of() : List.copyOf(attachments);
        }
        public SendMessage(String body, List<String> attachments, String subject) { this(body,attachments,subject,null); }
        public SendMessage(String body, List<String> attachments) { this(body, attachments, null); }
        public SendMessage(String body) { this(body, List.of()); }
    }



    public record ErrorView(String message) {}

    public record ConversationState(String id, String status, int unreadCount) {
        static ConversationState from(Conversation conversation) {
            return new ConversationState(
                    conversation.getId().toString(),
                    conversation.getStatus()==null?null:conversation.getStatus().toString(),
                    conversation.getUnreadCount());
        }
    }

    public record MessageView(
            String id,
            String kind,
            String direction,
            String channel,
            String authorName,
            String body,
            LocalDateTime sentAt,
            String deliveryStatus,
            /** The agent's photo, when the reply has a resolvable author; null leaves the widget on
             *  its generated fallback. Never set for an inbound message — that face is the contact's,
             *  and the renderer already has it. */
            String authorAvatarUrl,
            List<su.onno.crm.service.CrmAttachments.View> attachments,
            /** Email subject stored with the message (an override when the agent changed it); null otherwise. */
            String subject,
            /** Recipient address captured when the message was queued; null for inbound and non-email messages. */
            String recipient,
            /** Sanitized email HTML; null when the message is plain text. Render isolated, never inline. */
            String bodyHtml
    ) {}

    private MessageView view(ConversationMessage message, String authorAvatarUrl) {
        return new MessageView(
                message.getId().toString(),
                message.getKind().name(),
                message.getDirection().name(),
                message.getChannel(),
                message.getAuthorName(),
                message.getBody(),
                message.getSentAt(),
                message.getDeliveryStatus().name(),
                authorAvatarUrl,
                attachments.viewsOf(message),
                message.getSubject(),
                message.getRecipient(),
                message.getBodyHtml());
    }
}
