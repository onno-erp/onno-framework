package su.onno.crm.service;

import jakarta.annotation.PostConstruct;
import java.nio.charset.StandardCharsets;
import java.security.Principal;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;
import su.onno.crm.domain.*;
import su.onno.crm.repository.*;
import su.onno.ui.UiAccessService;

/** String identities and CRM links around a host-owned customer catalog. Never writes host fields. */
public class CrmContactService {
    private final CrmCustomerBinding<?> binding;
    private final ConversationRepository conversations;
    private final ContactIdentityRepository identities;
    private final CrmWorkspaceService workspace;
    private final JdbcTemplate jdbc;
    private final UiAccessService access;

    public CrmContactService(CrmCustomerBinding<?> binding, ConversationRepository conversations,
            ContactIdentityRepository identities, CrmWorkspaceService workspace, JdbcTemplate jdbc, UiAccessService access) {
        this.binding=binding;this.conversations=conversations;this.identities=identities;
        this.workspace=workspace;this.jdbc=jdbc;this.access=access;
    }
    @PostConstruct public void initialize() {
        jdbc.execute("CREATE TABLE IF NOT EXISTS onno_crm_catalog_binding (role VARCHAR(20) PRIMARY KEY, catalog_name VARCHAR(200) NOT NULL)");
        var names=jdbc.query("SELECT catalog_name FROM onno_crm_catalog_binding WHERE role='customer'",(r,n)->r.getString(1));
        if(names.isEmpty())jdbc.update("INSERT INTO onno_crm_catalog_binding(role,catalog_name) VALUES ('customer',?)",catalogName());
        else if(!names.getFirst().equals(catalogName()))throw new IllegalStateException("CRM customer catalog changed; migrate CRM links explicitly before rebinding");
        jdbc.execute("CREATE TABLE IF NOT EXISTS onno_crm_customer_redirect (source UUID PRIMARY KEY,target UUID NOT NULL)");
    }
    public String catalogName() { return binding.catalog().name(); }
    public UUID canonical(UUID id) {
        for(int n=0;n<100;n++) {
            var targets=jdbc.query("SELECT target FROM onno_crm_customer_redirect WHERE source=?",(r,i)->r.getObject(1,UUID.class),id);
            if(targets.isEmpty()) return id;
            id=targets.getFirst();
        }
        throw new IllegalArgumentException("Contact redirect cycle");
    }
    public boolean canRead(UUID id, Principal principal) {
        return access.canRead(principal,"catalog",catalogName()) && binding.catalog().canRead(canonical(id),principal);
    }
    public void requireRead(UUID id, Principal principal) {
        if(!canRead(id,principal))throw new ResponseStatusException(HttpStatus.FORBIDDEN,"Customer access denied");
    }
    public boolean canWrite(UUID id, Principal principal) {
        return canRead(id,principal) && access.canWrite(principal,"catalog",catalogName());
    }
    public record Contact(String catalogName,Map<String,Object> fields,List<ContactIdentity> identities,List<Conversation> conversations,boolean canWrite) {}
    public Contact get(UUID id,Principal principal) {
        id=canonical(id);requireRead(id,principal);
        return new Contact(catalogName(),binding.catalog().fields(id),identities.findByCustomerAndDeletionMarkFalse(id),
                conversations.findByCustomerAndDeletionMarkFalse(id),canWrite(id,principal));
    }
    public Map<String,Object> fields(UUID id,Principal principal) {
        requireRead(id,principal);return binding.catalog().fields(canonical(id));
    }
    /** Provider identity lookup runs before the host callback; email/name never silently merge people. */
    @Transactional public UUID resolveIncoming(CrmCustomerBinding.IncomingContact incoming) {
        workspace.lock();
        UUID key=identityId(incoming.channel(),incoming.connectionKey(),incoming.externalId());
        var existing=identities.findActiveById(key);
        if(existing.isPresent()) {
            UUID id=canonical(existing.get().getCustomer());binding.catalog().require(id);return id;
        }
        UUID id=binding.resolve(incoming).id();
        link(id,incoming.channel(),incoming.connectionKey(),incoming.externalId(),incoming.externalId(),!Channel.EMAIL.equals(incoming.channel()) && !Channel.PHONE.equals(incoming.channel()));
        return id;
    }
    @Transactional public ContactIdentity link(UUID customer,String channel,String connection,String externalId,String address,boolean verified) {
        workspace.lock();customer=canonical(customer);binding.catalog().require(customer);
        UUID id=identityId(channel,connection,externalId);
        externalId=normalize(channel,externalId);
        ContactIdentity identity=identities.findById(id).orElse(null);
        if(identity!=null) {
            if(identity.isDeletionMark() || !canonical(identity.getCustomer()).equals(customer))
                throw new IllegalArgumentException("This identity belongs to another contact; use the host's consolidation action");
            return identity;
        }
        identity=new ContactIdentity();identity.setId(id);identity.setDescription(address==null?externalId:address);
        identity.setCustomer(customer);identity.setChannel(channel);identity.setConnectionKey(connection);
        identity.setExternalId(externalId);identity.setAddress(address);identity.setVerified(verified);
        return identities.save(identity);
    }
    @Transactional public void setIdentityAvatar(UUID id,String url) {
        var identity=identities.findActiveById(id).orElseThrow();
        if(!Objects.equals(identity.getAvatarUrl(),url)){identity.setAvatarUrl(url);identities.save(identity);}
    }
    private static UUID identityId(String channel,String connection,String externalId) {
        if(channel==null || connection==null || connection.isBlank() || connection.length()>200)
            throw new IllegalArgumentException("String and connection are required");
        return UUID.nameUUIDFromBytes((channel+"\n"+connection+"\n"+normalize(channel,externalId)).getBytes(StandardCharsets.UTF_8));
    }
    private static String normalize(String channel,String externalId) {
        if(externalId==null||externalId.isBlank()||externalId.length()>240)throw new IllegalArgumentException("Identity is required (max 240)");
        String value=externalId.trim();
        if(Channel.EMAIL.equals(channel)) {
            value=value.toLowerCase(Locale.ROOT);
            if(!value.matches("[^\\s@]+@[^\\s@]+\\.[^\\s@]+"))throw new IllegalArgumentException("Invalid email");
        }
        if(Channel.PHONE.equals(channel)) {
            value=value.replaceAll("[ ()-]","");
            if(!value.matches("\\+[1-9][0-9]{6,14}"))throw new IllegalArgumentException("Use an international phone number starting with +");
        }
        return value;
    }

    /**
     * Join a host-owned consolidation transaction. The host authorizes, audits and reverses its
     * business merge; this command moves only CRM links, preserving each conversation's routing.
     * Call before the host soft-deletes the source. No HTTP merge command is installed by CRM.
     */
    @Transactional public void transferLinks(UUID source,UUID target) {
        workspace.lock();
        if(source.equals(target)||!source.equals(canonical(source))||!target.equals(canonical(target)))
            throw new IllegalArgumentException("Choose two distinct canonical customers");
        binding.catalog().require(source);binding.catalog().require(target);
        conversations.findByCustomerAndDeletionMarkFalse(source).forEach(c->{c.setCustomer(target);conversations.save(c);});
        identities.findByCustomerAndDeletionMarkFalse(source).forEach(i->{i.setCustomer(target);identities.save(i);});
        jdbc.update("INSERT INTO onno_crm_customer_redirect(source,target) VALUES (?,?)",source,target);
    }
}
