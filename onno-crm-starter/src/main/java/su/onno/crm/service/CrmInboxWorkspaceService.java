package su.onno.crm.service;

import java.security.Principal;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import su.onno.crm.domain.Conversation;
import su.onno.crm.repository.ConversationRepository;
import su.onno.service.AccessService;

/** Membership and permission checks shared by all dedicated CRM conversation endpoints. */
@Service
public class CrmInboxWorkspaceService {
    private final List<CrmInboxWorkspace> definitions;
    private final ConversationRepository conversations;
    private final AccessService access;
    private final CrmWorkspaceService configuration;
    private final CrmContactService contacts;
    /** Personal-account privacy. Required: a subclass must not be able to opt out by omission. */
    private final CrmConnectionAccounts connectionAccounts;
    public CrmInboxWorkspaceService(List<CrmInboxWorkspace> definitions, ConversationRepository conversations,
                                    AccessService access, CrmWorkspaceService configuration, CrmContactService contacts,
                                    CrmConnectionAccounts connectionAccounts) {
        this.definitions=List.copyOf(definitions);this.conversations=conversations;this.access=access;this.configuration=configuration;this.contacts=contacts;
        this.connectionAccounts=java.util.Objects.requireNonNull(connectionAccounts,"connectionAccounts");
        Set<String> keys=new HashSet<>();
        for(var definition:definitions) {
            if(!keys.add(definition.key())) throw new IllegalArgumentException("Duplicate inbox workspace key: "+definition.key());
        }
    }

    /**
     * As the other constructor, and checks at startup that every workspace's read and write roles
     * are roles the application declares (or maps with {@code fromIdp}): a misspelled role would
     * otherwise silently close the workspace to everyone.
     */
    @org.springframework.beans.factory.annotation.Autowired
    public CrmInboxWorkspaceService(List<CrmInboxWorkspace> definitions, ConversationRepository conversations,
                                    AccessService access, CrmWorkspaceService configuration, CrmContactService contacts,
                                    CrmConnectionAccounts connectionAccounts,
                                    org.springframework.beans.factory.ObjectProvider<su.onno.access.RoleGrants> grants) {
        this(definitions, conversations, access, configuration, contacts, connectionAccounts);
        var roleGrants = grants.getIfAvailable(su.onno.access.RoleGrants::open);
        for(var workspace:this.definitions) {
            roleGrants.requireRecognized("inbox workspace '"+workspace.key()+"' readRoles", workspace.readRoles());
            roleGrants.requireRecognized("inbox workspace '"+workspace.key()+"' writeRoles", workspace.writeRoles());
        }
    }
    public void requireAccess(Principal principal, boolean write) {
        if(principal==null || definitions.stream().noneMatch(w->permitted(w,principal,write)))
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,"Inbox workspace access required");
    }
    public boolean enabled(){return !definitions.isEmpty();}
    public boolean permitted(CrmInboxWorkspace workspace, Principal principal, boolean write) {
        var roles=access.roles(principal);
        // Only an explicit crm.administer grant opens every workspace (never an application without grants).
        if(access.access(principal).granted(su.onno.crm.CrmPermissions.ADMINISTER))return true;
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
    /**
     * The one conversation access rule: not in another user's personal inbox, customer readable, and
     * claimed by a workspace this principal may use. Every CRM read goes through this method or its
     * batch form {@link #accessible}; loops must use the batch form.
     */
    public boolean canAccess(Conversation conversation,Principal principal,boolean write) {
        return !hidden(conversation,principal)
            && conversation.getCustomer()!=null && contacts.canRead(conversation.getCustomer(),principal)
            && definitions.stream().anyMatch(w->permitted(w,principal,write)&&w.selection().test(conversation));
    }

    /**
     * The conversations this principal may see, filtered as a batch.
     *
     * <p>Same rule as {@link #canAccess}, asked once for a whole list instead of once per row. Per
     * row it is three repeated questions — may this principal use this workspace, may it read this
     * customer, and does any workspace claim this conversation — and only the last of them actually
     * varies with the row. The first is hoisted out, and the second goes through
     * {@link CrmContactService#readable}, which reads the redirect table once rather than once per
     * conversation. An inbox of eight hundred chats was issuing eight hundred of those queries to
     * paint twenty-five rows.</p>
     */
    public List<Conversation> accessible(List<Conversation> candidates,Principal principal,boolean write) {
        if(candidates.isEmpty()) return List.of();
        var workspaces=definitions.stream().filter(w->permitted(w,principal,write)).toList();
        if(workspaces.isEmpty()) return List.of();
        var readable=contacts.readable(candidates.stream().map(Conversation::getCustomer)
            .filter(java.util.Objects::nonNull).distinct().toList(),principal);
        var hidden=hiddenInboxes(principal);
        return candidates.stream()
            .filter(c->c.getInbox()==null || !hidden.contains(c.getInbox().id()))
            .filter(c->c.getCustomer()!=null && readable.contains(c.getCustomer()))
            .filter(c->workspaces.stream().anyMatch(w->w.selection().test(c)))
            .toList();
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
        customer=contacts.canonical(customer);
        contacts.requireRead(customer,principal);
        final UUID canonicalCustomer=customer;
        var related=conversations.findAllActive().stream().filter(c->c.getCustomer()!=null&&canonicalCustomer.equals(c.getCustomer())).toList();
        if(accessible(related,principal,write).isEmpty())throw new ResponseStatusException(HttpStatus.FORBIDDEN,"Contact is outside your inbox workspaces");
    }
    public void requireAllCustomerConversations(UUID customer,Principal principal) {
        customer=contacts.canonical(customer);
        contacts.requireRead(customer,principal);
        final UUID canonicalCustomer=customer;
        var related=conversations.findAllActive().stream().filter(c->c.getCustomer()!=null&&canonicalCustomer.equals(c.getCustomer())).toList();
        if(accessible(related,principal,true).size()!=related.size())
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,"All affected conversations must be writable");
    }

    /** Contact identities this principal may see: those of another user's personal account are left out. */
    public <I extends su.onno.crm.domain.ContactIdentity> List<I> visibleIdentities(List<I> identities,Principal principal) {
        var hidden=hiddenInboxes(principal);
        if(hidden.isEmpty())return identities;
        var keys=hidden.stream().map(UUID::toString).collect(java.util.stream.Collectors.toSet());
        return identities.stream().filter(i->!keys.contains(i.getConnectionKey())).toList();
    }
    private boolean hidden(Conversation conversation,Principal principal) {
        return conversation.getInbox()!=null && connectionAccounts.isHidden(conversation.getInbox().id(),principal);
    }
    private Set<UUID> hiddenInboxes(Principal principal) {
        return connectionAccounts.hiddenInboxes(principal);
    }
}
