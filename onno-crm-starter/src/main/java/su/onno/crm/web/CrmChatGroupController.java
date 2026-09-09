package su.onno.crm.web;

import java.security.Principal;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import su.onno.crm.service.*;
import su.onno.ui.UiAccessService;

/** User-owned organization never expands a workspace's conversation access. */
@RestController
@RequestMapping("/api/crm/chat-groups")
public class CrmChatGroupController {
    private final CrmChatGroupService groups;
    private final CrmInboxWorkspaceService workspaces;
    private final UiAccessService access;
    public CrmChatGroupController(CrmChatGroupService groups,CrmInboxWorkspaceService workspaces,UiAccessService access) {
        this.groups=groups;this.workspaces=workspaces;this.access=access;
    }
    private String scope(String workspace,Principal principal) {
        groups.requireEnabled();
        workspaces.requireAccess(principal,false);
        if(workspace!=null&&!workspace.isBlank()) {workspaces.requireWorkspace(workspace,principal,false);return workspace;}
        return "";
    }
    @GetMapping public Object list(@RequestParam(required=false) String workspace,Principal principal) {
        return groups.list(principalName(principal),scope(workspace,principal));
    }
    public record Change(String operation,String key,String label,UUID conversationId) {}
    @PostMapping public Object change(@RequestParam(required=false) String workspace,@RequestBody Change request,Principal principal) {
        String scope=scope(workspace,principal);
        UUID customer=null;
        if(Set.of("create","move").contains(Objects.toString(request.operation(),""))) {
            if(request.conversationId()==null)throw new IllegalArgumentException("Choose a chat");
            var conversation=scope.isEmpty()?workspaces.requireConversation(request.conversationId(),principal,false):workspaces.requireConversation(scope,request.conversationId(),principal,false);
            if(conversation.getCustomer()==null)throw new IllegalArgumentException("Chat has no contact");
            customer=conversation.getCustomer();
        }
        return groups.change(principal.getName(),scope,request.operation(),request.key(),request.label(),customer);
    }
    private String principalName(Principal principal) {if(principal==null)throw new ResponseStatusException(HttpStatus.FORBIDDEN);return principal.getName();}
    @ExceptionHandler(IllegalArgumentException.class) @ResponseStatus(HttpStatus.UNPROCESSABLE_ENTITY)
    public Map<String,String> invalid(IllegalArgumentException e) {return Map.of("message",e.getMessage());}
}
