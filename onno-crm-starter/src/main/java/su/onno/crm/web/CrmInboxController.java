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

    public CrmInboxController(
            ConversationService service,
            CurrentUserResolver currentUsers,
            CrmAgentIdentityResolver agentIdentities,
            UiAccessService access, su.onno.crm.service.CrmInboxWorkspaceService workspaces,
            CommentAuthorAvatars authorAvatars
    ) {
        this.service = service;
        this.currentUsers = currentUsers;
        this.agentIdentities = agentIdentities;
        this.access = access;this.workspaces=workspaces;
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
                .map(message -> MessageView.from(message, message.getAuthorId() == null
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
        workspaces.requireConversation(workspace,id,principal,true);
        var user = currentUsers.resolve(principal);
        return MessageView.from(service.addMessage(
                id,
                request == null ? null : request.body(),
                user.displayName(),
                user.recordId()), user.avatarUrl());
    }

    @GetMapping("/{id}/delivery")
    public su.onno.crm.service.CrmMessageTransport.Connection delivery(@PathVariable UUID id, @org.springframework.web.bind.annotation.RequestParam(required=false) String workspace, Principal principal) {
        requireCrmAccess(principal);
        workspaces.requireConversation(workspace,id,principal,false);
        return service.delivery(id);
    }

    @PostMapping("/{id}/messages/{messageId}/retry")
    public MessageView retry(@PathVariable UUID id, @PathVariable UUID messageId, @org.springframework.web.bind.annotation.RequestParam(required=false) String workspace, Principal principal) {
        requireCrmAccess(principal);
        workspaces.requireConversation(workspace,id,principal,true);
        ConversationMessage retried = service.retryMessage(id, messageId);
        return MessageView.from(retried, retried.getAuthorId() == null
                ? null : authorAvatars.avatarFor(retried.getAuthorId()));
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

    public record SendMessage(String body) {}



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
            String authorAvatarUrl
    ) {
        static MessageView from(ConversationMessage message) {
            return from(message, null);
        }

        static MessageView from(ConversationMessage message, String authorAvatarUrl) {
            return new MessageView(
                    message.getId().toString(),
                    message.getKind().name(),
                    message.getDirection().name(),
                    message.getChannel(),
                    message.getAuthorName(),
                    message.getBody(),
                    message.getSentAt(),
                    message.getDeliveryStatus().name(),
                    authorAvatarUrl);
        }
    }
}
