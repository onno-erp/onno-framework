package su.onno.crm.service;

import java.security.Principal;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import su.onno.crm.domain.Conversation;
import su.onno.crm.repository.ConversationRepository;
import su.onno.ui.UiAccessService;

/** Membership and permission checks shared by all dedicated CRM conversation endpoints. */
@Service
public class CrmInboxWorkspaceService {
    private final List<CrmInboxWorkspace> definitions;
    private final ConversationRepository conversations;
    private final UiAccessService access;
    private final CrmWorkspaceService configuration;
    public CrmInboxWorkspaceService(List<CrmInboxWorkspace> definitions, ConversationRepository conversations,
                                    UiAccessService access, CrmWorkspaceService configuration) {
        this.definitions=List.copyOf(definitions);this.conversations=conversations;this.access=access;this.configuration=configuration;
        Set<String> keys=new HashSet<>();
        for(var definition:definitions) {
            if(!keys.add(definition.key())) throw new IllegalArgumentException("Duplicate inbox workspace key: "+definition.key());
        }
    }
    public boolean enabled(){return !definitions.isEmpty();}
    public boolean permitted(CrmInboxWorkspace workspace, Principal principal, boolean write) {
        var roles=access.roles(principal);
        if(roles.contains("ADMIN"))return true;
        if(Collections.disjoint(roles,Set.of("CRM_AGENT","CRM_MANAGER")))return false;
        return !Collections.disjoint(roles,workspace.readRoles()) && (!write || !Collections.disjoint(roles,workspace.writeRoles()));
    }
    public List<CrmInboxWorkspace> available(Principal principal){return definitions.stream().filter(w->permitted(w,principal,false)).toList();}
    public CrmInboxWorkspace requireWorkspace(String key,Principal principal,boolean write){
        return definitions.stream().filter(w->w.key().equals(key)&&permitted(w,principal,write)).findFirst()
            .orElseThrow(()->new ResponseStatusException(HttpStatus.FORBIDDEN,"Inbox workspace access denied"));
    }
    public CrmWorkspaceService.Config config(CrmInboxWorkspace workspace) {
        var result=workspace.configure().apply(configuration.get().config());
        configuration.validate(result);
        return result;
    }
    public List<Conversation> members(CrmInboxWorkspace workspace) {
        return conversations.findAllActive().stream().filter(workspace.selection()).sorted(
            Comparator.comparing(Conversation::getLastMessageAt,Comparator.nullsLast(Comparator.reverseOrder())).thenComparing(Conversation::getId)).toList();
    }
    public boolean canAccess(Conversation conversation,Principal principal,boolean write) {
        return !enabled() || definitions.stream().anyMatch(w->permitted(w,principal,write)&&w.selection().test(conversation));
    }
    public Conversation requireConversation(UUID id,Principal principal,boolean write) {
        var conversation=conversations.findActiveById(id).orElseThrow(()->new ResponseStatusException(HttpStatus.NOT_FOUND));
        if(!canAccess(conversation,principal,write)) throw new ResponseStatusException(HttpStatus.FORBIDDEN,"Conversation is outside your inbox workspaces");
        return conversation;
    }
    public Conversation requireConversation(String workspace,UUID id,Principal principal,boolean write) {
        var definition=requireWorkspace(workspace,principal,write);
        var conversation=requireConversation(id,principal,write);
        if(!definition.selection().test(conversation))throw new ResponseStatusException(HttpStatus.FORBIDDEN,"Conversation is outside this workspace");
        return conversation;
    }
    public void requireCustomer(UUID customer,Principal principal,boolean write) {
        if(!enabled())return;
        var related=conversations.findAllActive().stream().filter(c->c.getCustomer()!=null&&customer.equals(c.getCustomer().id())).toList();
        if(related.stream().noneMatch(c->canAccess(c,principal,write)))throw new ResponseStatusException(HttpStatus.FORBIDDEN,"Contact is outside your inbox workspaces");
    }
    public void requireAllCustomerConversations(UUID customer,Principal principal) {
        if(!enabled())return;
        if(conversations.findAllActive().stream().filter(c->c.getCustomer()!=null&&customer.equals(c.getCustomer().id())).anyMatch(c->!canAccess(c,principal,true)))
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,"All affected conversations must be writable");
    }
}
