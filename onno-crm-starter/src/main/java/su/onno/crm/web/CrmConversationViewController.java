package su.onno.crm.web;

import java.security.Principal;
import java.util.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import su.onno.crm.service.CrmInboxWorkspaceService;
import su.onno.ui.DivKitController;
import su.onno.ui.UiAccessService;
import su.onno.ui.divkit.Div;
import su.onno.ui.divkit.DivCard;

/** Conversation links open the authorized CRM workspace, never an unrestricted catalog form. */
@RestController
public class CrmConversationViewController {
    private final CrmInboxWorkspaceService workspaces;
    private final UiAccessService access;
    private final org.springframework.beans.factory.ObjectProvider<DivKitController> generic;
    public CrmConversationViewController(CrmInboxWorkspaceService workspaces,UiAccessService access,org.springframework.beans.factory.ObjectProvider<DivKitController> generic){this.workspaces=workspaces;this.access=access;this.generic=generic;}
    @GetMapping({"/api/divkit/catalogs/crm_conversations/{id}","/api/divkit/catalogs/CrmConversations/{id}","/api/divkit/catalogs/crmconversations/{id}"})
    public Map<String,Object> open(@PathVariable UUID id,@RequestParam(required=false) String profile,Principal principal) {
        workspaces.requireAccess(principal,false);
        var conversation=workspaces.requireConversation(id,principal,false);
        var workspace=workspaces.available(principal).stream().filter(w->w.selection().test(conversation)).findFirst()
            .orElseThrow(()->new ResponseStatusException(HttpStatus.FORBIDDEN,"Conversation is outside your inbox workspaces"));
        var node=Div.custom("onno-widget",Map.of("widget",Map.of("title",Objects.toString(conversation.getSubject(),"Conversation"),"widgetType","crmInboxWorkspaces","extraConfig",Map.of("workspace",workspace.key(),"conversation",id.toString()),"canWrite",workspaces.permitted(workspace,principal,true))));
        Div.matchWidth(node);
        return DivCard.of("onno-content",node);
    }
}
