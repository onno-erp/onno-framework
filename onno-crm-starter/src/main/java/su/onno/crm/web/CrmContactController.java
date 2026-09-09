package su.onno.crm.web;

import java.security.Principal;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import su.onno.crm.domain.Channel;
import su.onno.crm.service.*;

/** Read projection and channel identity commands; the host's generated catalog owns contact CRUD. */
@RestController
@RequestMapping("/api/crm")
public class CrmContactController {
    private final CrmContactService contacts;
    private final CrmWorkspaceService workspace;
    private final CrmInboxWorkspaceService inboxWorkspaces;
    public CrmContactController(CrmContactService contacts,CrmWorkspaceService workspace,CrmInboxWorkspaceService inboxWorkspaces){this.contacts=contacts;this.workspace=workspace;this.inboxWorkspaces=inboxWorkspaces;}
    @GetMapping("/statuses") public Object statuses(Principal p) {
        inboxWorkspaces.requireAccess(p,false);
        return workspace.statuses().conversationStatuses().stream().map(s->Map.of(
                "id",s.choice().id(),"description",s.choice().label(),"color",s.choice().color(),"closed",s.closed())).toList();
    }
    @GetMapping("/workspace") public Map<String,Object> workspace(Principal p){
        inboxWorkspaces.requireAccess(p,false);
        return Map.of("workspace",workspace.get(),"canConfigure",false,"customerCatalog",contacts.catalogName());
    }
    @GetMapping("/contacts/{id}") public Object contact(@PathVariable UUID id,Principal p){
        inboxWorkspaces.requireCustomer(id,p,false);var c=contacts.get(id,p);
        return new CrmContactService.Contact(c.catalogName(),c.fields(),c.identities(),
                c.conversations().stream().filter(row->inboxWorkspaces.canAccess(row,p,false)).toList(),c.canWrite());
    }
    @ExceptionHandler({IllegalArgumentException.class,org.springframework.dao.OptimisticLockingFailureException.class})
    @ResponseStatus(HttpStatus.UNPROCESSABLE_ENTITY) public Map<String,String> invalid(RuntimeException e){return Map.of("message",e.getMessage()==null?"CRM data changed; reload":e.getMessage());}
}
