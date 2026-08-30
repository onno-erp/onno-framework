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

/** Role-gated CRM command boundary used by the inbox renderer. */
@RestController
@RequestMapping("/api/crm/conversations")
public class CrmInboxController {

    private final ConversationService service;
    private final CurrentUserResolver currentUsers;
    private final CrmAgentIdentityResolver agentIdentities;
    private final UiAccessService access;

    public CrmInboxController(
            ConversationService service,
            CurrentUserResolver currentUsers,
            CrmAgentIdentityResolver agentIdentities,
            UiAccessService access
    ) {
        this.service = service;
        this.currentUsers = currentUsers;
        this.agentIdentities = agentIdentities;
        this.access = access;
    }

    @GetMapping("/{id}/messages")
    public List<MessageView> messages(@PathVariable UUID id, Principal principal) {
        requireCrmAccess(principal);
        return service.messages(id).stream().map(MessageView::from).toList();
    }

    @PostMapping("/{id}/messages")
    public MessageView send(
            @PathVariable UUID id,
            @RequestBody SendMessage request,
            Principal principal
    ) {
        requireCrmAccess(principal);
        var user = currentUsers.resolve(principal);
        return MessageView.from(service.addMessage(
                id,
                request == null ? null : request.body(),
                user.displayName()));
    }

    @PostMapping("/{id}/read")
    public ConversationState markRead(@PathVariable UUID id, Principal principal) {
        requireCrmAccess(principal);
        return ConversationState.from(service.markRead(id));
    }

    @PostMapping("/{id}/closed")
    public ConversationState close(
            @PathVariable UUID id,
            @RequestBody CloseConversation request,
            Principal principal
    ) {
        requireCrmAccess(principal);
        var user = currentUsers.resolve(principal);
        return ConversationState.from(service.setClosed(id, request.closed(), user.displayName()));
    }

    @PostMapping("/{id}/assign-to-me")
    public ConversationState assignToMe(@PathVariable UUID id, Principal principal) {
        requireCrmAccess(principal);
        var user = currentUsers.resolve(principal);
        UUID agentId = agentIdentities.resolve(user)
                .orElseThrow(() -> new IllegalArgumentException(
                        "The signed-in user is not linked to an active CRM agent"));
        return ConversationState.from(service.assign(id, agentId, user.displayName()));
    }

    private void requireCrmAccess(Principal principal) {
        if (!access.hasAnyRole(principal, List.of("CRM_AGENT", "CRM_MANAGER"))) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "CRM access is required");
        }
    }

    @ExceptionHandler(IllegalArgumentException.class)
    @ResponseStatus(HttpStatus.UNPROCESSABLE_ENTITY)
    public ErrorView invalid(IllegalArgumentException error) {
        return new ErrorView(error.getMessage());
    }

    public record SendMessage(String body) {}

    public record CloseConversation(boolean closed) {}

    public record ErrorView(String message) {}

    public record ConversationState(String id, String status, int unreadCount) {
        static ConversationState from(Conversation conversation) {
            return new ConversationState(
                    conversation.getId().toString(),
                    conversation.getStatus().name(),
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
            String deliveryStatus
    ) {
        static MessageView from(ConversationMessage message) {
            return new MessageView(
                    message.getId().toString(),
                    message.getKind().name(),
                    message.getDirection().name(),
                    message.getChannel().name(),
                    message.getAuthorName(),
                    message.getBody(),
                    message.getSentAt(),
                    message.getDeliveryStatus().name());
        }
    }
}
