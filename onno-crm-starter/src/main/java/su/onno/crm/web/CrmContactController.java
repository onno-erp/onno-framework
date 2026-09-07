package su.onno.crm.web;

import java.security.Principal;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import su.onno.crm.domain.Channel;
import su.onno.crm.service.*;
import su.onno.ui.UiAccessService;

@RestController
@RequestMapping("/api/crm")
public class CrmContactController {
    private final CrmContactService contacts;
    private final CrmWorkspaceService workspace;
    private final UiAccessService access;
    private final CrmInboxWorkspaceService inboxWorkspaces;
    public CrmContactController(CrmContactService contacts,CrmWorkspaceService workspace,UiAccessService access,CrmInboxWorkspaceService inboxWorkspaces){this.contacts=contacts;this.workspace=workspace;this.access=access;this.inboxWorkspaces=inboxWorkspaces;}
    private void require(Principal p,boolean manager){if(!access.hasAnyRole(p,manager?List.of("CRM_MANAGER"):List.of("CRM_AGENT","CRM_MANAGER")))throw new ResponseStatusException(HttpStatus.FORBIDDEN,"CRM "+(manager?"manager ":"")+"access required");}
    @GetMapping("/workspace") public Map<String,Object> workspace(Principal p){require(p,false);var view=workspace.get();if(inboxWorkspaces.enabled())view=new CrmWorkspaceService.Workspace(view.version(),view.config().withFolders(List.of()),view.availableFields());return Map.of("workspace",view,"canConfigure",false);}
    @GetMapping("/contacts") public Object search(@RequestParam(defaultValue="") String q,Principal p){require(p,false);return contacts.search(q);}
    @GetMapping("/contacts/{id}") public Object contact(@PathVariable UUID id,Principal p){require(p,false);inboxWorkspaces.requireCustomer(id,p,false);return visible(contacts.get(id),p);}
    public record Edit(String revision,Map<String,Object> fields,Map<String,Object> customValues){}
    @PostMapping("/contacts/{id}") public Object edit(@PathVariable UUID id,@RequestBody Edit r,Principal p){require(p,false);inboxWorkspaces.requireCustomer(id,p,true);return visible(contacts.update(id,r.revision(),r.fields(),r.customValues()),p);}
    public record Identity(Channel channel,String address){}
    @PostMapping("/contacts/{id}/identities") public Object link(@PathVariable UUID id,@RequestBody Identity r,Principal p){require(p,false);inboxWorkspaces.requireCustomer(id,p,true);if(r.channel()!=Channel.EMAIL&&r.channel()!=Channel.PHONE)throw new IllegalArgumentException("Provider identities are linked by the connector");return contacts.link(id,r.channel(),"manual",r.address(),r.address(),false);}
    public record Merge(UUID source,UUID target,String revision,Map<String,String> choices){}
    @PostMapping("/contacts/merge-preview") public Object preview(@RequestBody Merge r,Principal p){require(p,false);inboxWorkspaces.requireAllCustomerConversations(r.source(),p);inboxWorkspaces.requireAllCustomerConversations(r.target(),p);return contacts.preview(r.source(),r.target());}
    @PostMapping("/contacts/merge") public Object merge(@RequestBody Merge r,Principal p){require(p,false);inboxWorkspaces.requireAllCustomerConversations(r.source(),p);inboxWorkspaces.requireAllCustomerConversations(r.target(),p);return Map.of("id",contacts.merge(r.source(),r.target(),r.revision(),r.choices(),p.getName()));}
    @GetMapping("/contacts/{id}/merges") public Object history(@PathVariable UUID id,Principal p){require(p,false);if(inboxWorkspaces.enabled()&&!access.roles(p).contains("ADMIN"))throw new ResponseStatusException(HttpStatus.FORBIDDEN,"Merge history requires administrator access in workspace mode");return contacts.history(id);}
    @PostMapping("/merges/{id}/undo") public Object undo(@PathVariable UUID id,Principal p){require(p,false);if(inboxWorkspaces.enabled()&&!access.roles(p).contains("ADMIN"))throw new ResponseStatusException(HttpStatus.FORBIDDEN,"Merge undo requires administrator access in workspace mode");contacts.undo(id,p.getName());return Map.of("undone",true);}
    private CrmContactService.Contact visible(CrmContactService.Contact contact,Principal principal) {
        return new CrmContactService.Contact(contact.fields(),contact.customValues(),contact.revision(),contact.identities(),
            contact.conversations().stream().filter(c->inboxWorkspaces.canAccess(c,principal,false)).toList(),contact.duplicates());
    }
    @ExceptionHandler({IllegalArgumentException.class,org.springframework.dao.OptimisticLockingFailureException.class})
    @ResponseStatus(HttpStatus.UNPROCESSABLE_ENTITY) public Map<String,String> invalid(RuntimeException e){return Map.of("message",e.getMessage()==null?"CRM data changed; reload":e.getMessage());}
}
