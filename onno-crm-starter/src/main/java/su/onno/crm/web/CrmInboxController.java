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
    private final su.onno.crm.service.CrmInboxWorkspaceService workspaces;

    public CrmInboxController(
            ConversationService service,
            CurrentUserResolver currentUsers,
            CrmAgentIdentityResolver agentIdentities,
            UiAccessService access, su.onno.crm.service.CrmInboxWorkspaceService workspaces
    ) {
        this.service = service;
        this.currentUsers = currentUsers;
        this.agentIdentities = agentIdentities;
        this.access = access;this.workspaces=workspaces;
    }

    @GetMapping("/{id}/messages")
    public List<MessageView> messages(@PathVariable UUID id, @org.springframework.web.bind.annotation.RequestParam(required=false) String workspace, Principal principal) {
        requireCrmAccess(principal);
        workspaces.requireConversation(workspace,id,principal,false);
        return service.messages(id).stream().map(MessageView::from).toList();
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
                user.displayName()));
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
        return MessageView.from(service.retryMessage(id, messageId));
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
            String deliveryStatus
    ) {
        static MessageView from(ConversationMessage message) {
            return new MessageView(
                    message.getId().toString(),
                    message.getKind().name(),
                    message.getDirection().name(),
                    message.getChannel(),
                    message.getAuthorName(),
                    message.getBody(),
                    message.getSentAt(),
                    message.getDeliveryStatus().name());
        }
    }
}
