package su.onno.crm.web;

import java.security.Principal;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import su.onno.crm.domain.Conversation;
import su.onno.crm.service.CrmInboxWorkspaceService;
import su.onno.ui.*;
import su.onno.ui.ActionSpec.Action;

/** Runs ordinary host EntityView actions inside the conversation's workspace access boundary. */
@RestController
@RequestMapping("/api/crm/inbox-workspaces/{workspace}/conversations/{id}/actions")
public class CrmActionController {
    private final CrmInboxWorkspaceService workspaces;
    private final UiActionResolver actions;
    private final UiAccessService access;
    private final CatalogQueryService catalogs;
    private final boolean readOnly;
    public CrmActionController(CrmInboxWorkspaceService workspaces,@org.springframework.context.annotation.Lazy UiActionResolver actions,UiAccessService access,
            CatalogQueryService catalogs,@Value("${onno.ui.read-only:false}") boolean readOnly) {
        this.workspaces=workspaces;this.actions=actions;this.access=access;this.catalogs=catalogs;this.readOnly=readOnly;
    }
    private boolean allowed(Action action,Principal principal) {
        return action.isServer() && action.scope()!=ActionScope.TOOLBAR &&
            (action.roles().isEmpty() || access.hasAnyRole(principal,action.roles()));
    }
    private Map<String,Object> row(UUID id) { return catalogs.get(catalogs.forClass(Conversation.class),id); }
    @GetMapping public Object list(@PathVariable String workspace,@PathVariable UUID id,Principal principal) {
        workspaces.requireConversation(workspace,id,principal,false);
        var definition=workspaces.requireWorkspace(workspace,principal,false);
        boolean writable=!readOnly && workspaces.permitted(definition,principal,true);
        var record=row(id);
        return actions.resolvedForEntity(Conversation.class).stream().filter(a->allowed(a,principal)).map(a->{
            var state=UiActionResolver.recordActionState(a,record);
            return Map.of("id",a.key(),"label",state.label(),"visible",state.visible(),"enabled",writable&&state.enabled());
        }).filter(a->Boolean.TRUE.equals(a.get("visible"))).toList();
    }
    @PostMapping("/{key}") public ActionResult run(@PathVariable String workspace,@PathVariable UUID id,@PathVariable String key,
            @RequestBody(required=false) Map<String,Object> body,Principal principal) {
        workspaces.requireConversation(workspace,id,principal,true);
        if(readOnly)throw new ResponseStatusException(HttpStatus.FORBIDDEN,"Application is read-only");
        var action=actions.find(Conversation.class,key);
        if(action==null)throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Unknown action");
        if(!allowed(action,principal))throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        var state=UiActionResolver.recordActionState(action,row(id));
        if(!state.visible() || !state.enabled())throw new ResponseStatusException(HttpStatus.FORBIDDEN,"Action unavailable");
        var result=action.handler().apply(ActionContext.from("catalogs","CrmConversations",id,principal.getName(),body));
        return result==null?ActionResult.ok():result;
    }
}
