package su.onno.crm.service;

import jakarta.annotation.PostConstruct;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import su.onno.annotations.Attribute;
import su.onno.crm.domain.*;
import su.onno.crm.repository.*;
import su.onno.types.Ref;

/** Customer identity and explicit, transactional contact consolidation. */
@Service
public class CrmContactService {
    private final CustomerRepository customers;
    private final AgentRepository agents;
    private final LifecycleStageRepository stages;
    private final ConversationRepository conversations;
    private final OpportunityRepository opportunities;
    private final ContactIdentityRepository identities;
    private final CrmWorkspaceService workspace;
    private final JdbcTemplate jdbc;
    public CrmContactService(CustomerRepository customers, ConversationRepository conversations,
            OpportunityRepository opportunities, ContactIdentityRepository identities,
            CrmWorkspaceService workspace, JdbcTemplate jdbc, AgentRepository agents, LifecycleStageRepository stages) {
        this.stages=stages; this.agents=agents; this.customers=customers; this.conversations=conversations; this.opportunities=opportunities;
        this.identities=identities; this.workspace=workspace; this.jdbc=jdbc;
    }
    @PostConstruct public void initialize() {
        jdbc.execute("CREATE TABLE IF NOT EXISTS onno_crm_customer_redirect (source UUID PRIMARY KEY,target UUID NOT NULL)");
        jdbc.execute("CREATE TABLE IF NOT EXISTS onno_crm_merge (id UUID PRIMARY KEY,source UUID NOT NULL,target UUID NOT NULL,actor VARCHAR(256),created_at TIMESTAMP NOT NULL,undone BOOLEAN NOT NULL,undo_actor VARCHAR(256),undone_at TIMESTAMP,before_data TEXT NOT NULL,after_hash VARCHAR(64) NOT NULL)");
    }
    public UUID canonical(UUID id) {
        for(int n=0;n<100;n++) {
            var targets=jdbc.query("SELECT target FROM onno_crm_customer_redirect WHERE source=?",(r,i)->r.getObject(1,UUID.class),id);
            if(targets.isEmpty()) return id;
            id=targets.getFirst();
        }
        throw new IllegalArgumentException("Contact redirect cycle");
    }
    private Customer active(UUID id) { return customers.findActiveById(id).orElseThrow(()->new IllegalArgumentException("Customer is no longer active")); }
    public record Contact(Map<String,Object> fields,Map<String,Object> customValues,String revision,List<ContactIdentity> identities,List<Conversation> conversations,List<Map<String,Object>> duplicates) {}
    public Contact get(UUID id) {
        id=canonical(id); Customer c=active(id);
        return new Contact(fields(c),workspace.values(id),hash(snapshot(id)),identities.findByCustomerAndDeletionMarkFalse(Ref.of(Customer.class,id)),conversations.findByCustomerAndDeletionMarkFalse(Ref.of(Customer.class,id)),duplicates(c));
    }
    private List<Map<String,Object>> duplicates(Customer customer) {
        List<Map<String,Object>> matches=new ArrayList<>();
        for(Customer other:customers.findAllActive()) {
            if(other.getId().equals(customer.getId()))continue;
            String reason=present(customer.getEmail())&&customer.getEmail().trim().equalsIgnoreCase(Objects.requireNonNullElse(other.getEmail(), "").trim())?"Same email":
                    present(customer.getPhone())&&customer.getPhone().replaceAll("[^0-9]", "").equals(Objects.requireNonNullElse(other.getPhone(), "").replaceAll("[^0-9]", ""))?"Same phone":
                    present(customer.getDescription())&&customer.getDescription().trim().equalsIgnoreCase(Objects.requireNonNullElse(other.getDescription(), "").trim())?"Same name · check identity":null;
            if(reason!=null){var row=fields(other);row.put("reason",reason);matches.add(row);}
            if(matches.size()==20)break;
        }
        return matches;
    }
    public List<Map<String,Object>> search(String query) {
        String q=Objects.requireNonNullElse(query,"").toLowerCase(Locale.ROOT);
        return customers.findAllActive().stream().filter(c->(c.getDescription()+" "+c.getEmail()+" "+c.getPhone()).toLowerCase(Locale.ROOT).contains(q))
                .limit(50).map(this::fields).toList();
    }
    @Transactional public Contact update(UUID id,String revision,Map<String,Object> fields,Map<String,Object> values) {
        workspace.lock(); if(!id.equals(canonical(id))) throw new IllegalArgumentException("Customer was merged; reload");
        if(!hash(snapshot(id)).equals(revision)) throw new IllegalArgumentException("Contact changed; reload before saving");
        Customer c=active(id); apply(c,fields); workspace.validateValues(values);
        customers.save(c); workspace.writeValues(id,values); return get(id);
    }
    @Transactional public ContactIdentity link(UUID customer,Channel channel,String connection,String externalId,String address,boolean verified) {
        workspace.lock(); customer=canonical(customer); active(customer);
        if(connection==null || connection.isBlank() || connection.length()>200 || externalId==null || externalId.isBlank() || externalId.length()>240)
            throw new IllegalArgumentException("Connection and identity are required");
        externalId=externalId.trim();
        if(channel==Channel.EMAIL) {
            externalId=externalId.toLowerCase(Locale.ROOT);
            if(!externalId.matches("[^\\s@]+@[^\\s@]+\\.[^\\s@]+")) throw new IllegalArgumentException("Invalid email");
        }
        if(channel==Channel.PHONE) {
            externalId=externalId.replaceAll("[ ()-]","");
            if(!externalId.matches("\\+[1-9][0-9]{6,14}")) throw new IllegalArgumentException("Use an international phone number starting with +");
        }
        UUID id=UUID.nameUUIDFromBytes((channel+"\n"+connection+"\n"+externalId).getBytes(StandardCharsets.UTF_8));
        ContactIdentity identity=identities.findById(id).orElse(null);
        if(identity!=null) {
            if(identity.isDeletionMark() || !canonical(identity.getCustomer().id()).equals(customer)) throw new IllegalArgumentException("This identity belongs to another contact; review and merge the contacts first");
            if(verified && address!=null && address.startsWith("@") && !address.equals(identity.getAddress())) {
                identity.setAddress(address);identity.setDescription(address);identities.save(identity);
            }
            return identity;
        }
        identity=new ContactIdentity(); identity.setId(id); identity.setDescription(address==null?externalId:address);
        identity.setCustomer(Ref.of(Customer.class,customer)); identity.setChannel(channel); identity.setConnectionKey(connection);
        identity.setExternalId(externalId); identity.setAddress(address); identity.setVerified(verified);
        return identities.save(identity);
    }
    public record Conflict(String key,Object source,Object target) {}
    public record Preview(UUID source,UUID target,String revision,List<Conflict> conflicts,int conversations,int opportunities,int identities) {}
    public Preview preview(UUID source,UUID target) {
        if(source.equals(target) || !source.equals(canonical(source)) || !target.equals(canonical(target))) throw new IllegalArgumentException("Choose two different active contacts");
        Map<String,Object> a=fields(active(source)),b=fields(active(target)); List<Conflict> conflicts=new ArrayList<>();
        editableKeys().forEach(k->{if(present(a.get(k)) && present(b.get(k)) && !Objects.equals(a.get(k),b.get(k))) conflicts.add(new Conflict(k,a.get(k),b.get(k)));});
        var av=workspace.values(source); var bv=workspace.values(target);
        av.forEach((k,v)->{if(present(v)&&present(bv.get(k))&&!Objects.equals(v,bv.get(k))) conflicts.add(new Conflict("custom."+k,v,bv.get(k)));});
        var ref=Ref.of(Customer.class,source);
        return new Preview(source,target,hash(List.of(snapshot(source),snapshot(target))),conflicts,
                conversations.findByCustomerAndDeletionMarkFalse(ref).size(),opportunities.findByCustomerAndDeletionMarkFalse(ref).size(),identities.findByCustomerAndDeletionMarkFalse(ref).size());
    }
    @Transactional public UUID merge(UUID source,UUID target,String revision,Map<String,String> choices,String actor) {
        workspace.lock(); Preview p=preview(source,target);
        if(!p.revision().equals(revision)) throw new IllegalArgumentException("Contacts changed; review a fresh preview");
        for(var c:p.conflicts()) if(!Set.of("source","target").contains(choices.getOrDefault(c.key(),""))) throw new IllegalArgumentException("Choose a value for "+c.key());
        Map<String,Object> before=new LinkedHashMap<>(); before.put("source",snapshot(source));before.put("target",snapshot(target));
        Customer a=active(source),b=active(target); Map<String,Object> af=fields(a),bf=fields(b);
        Map<String,Object> selected=new LinkedHashMap<>();
        for(String key:editableKeys()) selected.put(key,!present(bf.get(key))||"source".equals(choices.get(key))?af.get(key):bf.get(key));
        apply(b,selected); customers.save(b);
        Map<String,Object> values=new LinkedHashMap<>(workspace.values(target));
        workspace.values(source).forEach((k,v)->{if(!present(values.get(k))||"source".equals(choices.get("custom."+k)))values.put(k,v);});
        workspace.writeValues(target,values);
        transfer(source,target);
        a.setDeletionMark(true); customers.save(a);
        jdbc.update("INSERT INTO onno_crm_customer_redirect VALUES (?,?)",source,target);
        UUID id=UUID.randomUUID();
        jdbc.update("INSERT INTO onno_crm_merge (id,source,target,actor,created_at,undone,before_data,after_hash) VALUES (?,?,?,?,CURRENT_TIMESTAMP,FALSE,?,?)",id,source,target,actor,workspace.encode(before),hash(List.of(snapshot(source),snapshot(target))));
        return id;
    }
    private void transfer(UUID from,UUID to) {
        jdbc.update("INSERT INTO onno_tag_links(scope,record_id,tag_id) SELECT scope,?,tag_id FROM onno_tag_links source WHERE scope='catalogs:crmcustomers' AND record_id=? AND NOT EXISTS (SELECT 1 FROM onno_tag_links target WHERE target.scope=source.scope AND target.record_id=? AND target.tag_id=source.tag_id)",to,from,to);
        jdbc.update("DELETE FROM onno_tag_links WHERE scope='catalogs:crmcustomers' AND record_id=?",from);
        var source=Ref.of(Customer.class,from);var target=Ref.of(Customer.class,to);
        conversations.findByCustomerAndDeletionMarkFalse(source).forEach(c->{c.setCustomer(target);conversations.save(c);});
        opportunities.findByCustomerAndDeletionMarkFalse(source).forEach(o->{o.setCustomer(target);opportunities.save(o);});
        identities.findByCustomerAndDeletionMarkFalse(source).forEach(i->{i.setCustomer(target);identities.save(i);});
        jdbc.update("UPDATE onno_comments SET _entity_id=? WHERE _entity_id=? AND _entity_type IN ('catalog','catalogs') AND _entity_name IN ('crm_customers','CrmCustomers')",to,from);
    }
    public List<Map<String,Object>> history(UUID id) {
        return jdbc.query("SELECT id,source,target,actor,created_at,undone,undo_actor,undone_at FROM onno_crm_merge WHERE source=? OR target=? ORDER BY created_at DESC",(r,n)->{
            Map<String,Object> row=new LinkedHashMap<>(); for(String key:List.of("id","source","target","actor","created_at","undone","undo_actor","undone_at")) row.put(key,r.getObject(key)); return row;
        },id,id);
    }
    @SuppressWarnings("unchecked") @Transactional public void undo(UUID id,String actor) {
        workspace.lock(); var rows=jdbc.queryForList("SELECT * FROM onno_crm_merge WHERE id=? FOR UPDATE",id);
        if(rows.isEmpty())throw new IllegalArgumentException("Merge not found");var row=rows.getFirst();
        UUID source=(UUID)row.get("SOURCE"),target=(UUID)row.get("TARGET");
        if(Boolean.TRUE.equals(row.get("UNDONE")))throw new IllegalArgumentException("Merge already undone");
        Map<String,Object> before=workspace.decode((String)row.get("BEFORE_DATA"),LinkedHashMap.class);
        Map<String,Object> a=(Map<String,Object>)before.get("source"),b=(Map<String,Object>)before.get("target");
        var currentSource=snapshot(source); var currentTarget=snapshot(target);
        // Histories written before structured tags did not include this field in their hash.
        if (!a.containsKey("tagIds") && ((List<?>)currentSource.get("tagIds")).isEmpty() && ((List<?>)currentTarget.get("tagIds")).isEmpty()) {
            currentSource.remove("tagIds"); currentTarget.remove("tagIds");
        }
        if (legacyStageSnapshot(a) || legacyStageSnapshot(b)) {
            restoreLegacyStageName(currentSource); restoreLegacyStageName(currentTarget);
        }
        if(!hash(List.of(currentSource,currentTarget)).equals(row.get("AFTER_HASH")))throw new IllegalArgumentException("Contacts changed since this merge. Undo would overwrite later work and is unavailable.");
        restore(source,a);restore(target,b);
        for(Map<String,Object> c:(List<Map<String,Object>>)a.get("conversations")) {var entity=conversations.findById(UUID.fromString(c.get("id").toString())).orElseThrow();entity.setCustomer(Ref.of(Customer.class,source));conversations.save(entity);}
        for(Map<String,Object> o:(List<Map<String,Object>>)a.get("opportunities")) {var entity=opportunities.findById(UUID.fromString(o.get("id").toString())).orElseThrow();entity.setCustomer(Ref.of(Customer.class,source));opportunities.save(entity);}
        for(Map<String,Object> i:(List<Map<String,Object>>)a.get("identities")) {var entity=identities.findById(UUID.fromString(i.get("id").toString())).orElseThrow();entity.setCustomer(Ref.of(Customer.class,source));identities.save(entity);}
        for(Map<String,Object> note:(List<Map<String,Object>>)a.get("comments")) jdbc.update("UPDATE onno_comments SET _entity_id=? WHERE _id=?",source,UUID.fromString(note.get("id").toString()));
        jdbc.update("DELETE FROM onno_crm_customer_redirect WHERE source=?",source);
        jdbc.update("UPDATE onno_crm_merge SET undone=TRUE,undo_actor=?,undone_at=CURRENT_TIMESTAMP WHERE id=?",actor,id);
    }
    @SuppressWarnings("unchecked") private void restore(UUID id,Map<String,Object> data) {
        Customer c=customers.findById(id).orElseThrow();
        Map<String,Object> restoredFields=new TreeMap<>((Map<String,Object>)data.get("fields"));
        if(legacyStageSnapshot(data)) restoredFields.put("stage", su.onno.repository.EnumerationPersistence.resolveId(CustomerStage.class,CustomerStage.valueOf(restoredFields.get("stage").toString())).toString());
        apply(c,restoredFields);c.setDeletionMark(false);customers.save(c);
        workspace.writeValues(id,(Map<String,Object>)data.get("custom"));
        if (data.containsKey("tagIds")) {
            jdbc.update("DELETE FROM onno_tag_links WHERE scope='catalogs:crmcustomers' AND record_id=?",id);
            for (Object tag : (List<?>)data.get("tagIds")) jdbc.update("INSERT INTO onno_tag_links(scope,record_id,tag_id) VALUES ('catalogs:crmcustomers',?,?)",id,UUID.fromString(tag.toString()));
        }
    }
    private static boolean legacyStageSnapshot(Map<String,Object> snapshot) {
        Object value=((Map<?,?>)snapshot.get("fields")).get("stage");
        return Arrays.stream(CustomerStage.values()).anyMatch(stage -> stage.name().equals(value));
    }
    @SuppressWarnings("unchecked") private static void restoreLegacyStageName(Map<String,Object> snapshot) {
        Map<String,Object> fields=(Map<String,Object>)snapshot.get("fields");
        for(CustomerStage stage:CustomerStage.values()) {
            if(su.onno.repository.EnumerationPersistence.resolveId(CustomerStage.class,stage).toString().equals(fields.get("stage"))) {
                fields.put("stage",stage.name()); return;
            }
        }
    }
    private Map<String,Object> snapshot(UUID id) {
        var c=customers.findById(id).orElseThrow(); var ref=Ref.of(Customer.class,id);Map<String,Object> data=new TreeMap<>();
        data.put("tagIds",jdbc.query("SELECT tag_id FROM onno_tag_links WHERE scope='catalogs:crmcustomers' AND record_id=? ORDER BY tag_id",(r,n)->r.getString(1),id));
        data.put("fields",fields(c));data.put("deleted",c.isDeletionMark());data.put("custom",new TreeMap<>(workspace.values(id)));
        data.put("conversations",conversations.findByCustomerAndDeletionMarkFalse(ref).stream().sorted(Comparator.comparing(Conversation::getId)).map(v->Map.of("id",v.getId().toString(),"version",v.getVersion())).toList());
        data.put("opportunities",opportunities.findByCustomerAndDeletionMarkFalse(ref).stream().sorted(Comparator.comparing(Opportunity::getId)).map(v->Map.of("id",v.getId().toString(),"version",v.getVersion())).toList());
        data.put("identities",identities.findByCustomerAndDeletionMarkFalse(ref).stream().sorted(Comparator.comparing(ContactIdentity::getId)).map(v->Map.of("id",v.getId().toString(),"version",v.getVersion())).toList());
        data.put("comments",jdbc.query("SELECT _id,_body,_edited_at,_deleted FROM onno_comments WHERE _entity_id=? AND _entity_type IN ('catalog','catalogs') AND _entity_name IN ('crm_customers','CrmCustomers') ORDER BY _id",(r,n)->{Map<String,Object> m=new TreeMap<>();m.put("id",r.getString(1));m.put("body",r.getString(2));m.put("edited",r.getString(3));m.put("deleted",r.getBoolean(4));return m;},id));return data;
    }
    private String hash(Object value) { try {return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(workspace.encode(value).getBytes(StandardCharsets.UTF_8)));}catch(Exception e){throw new IllegalStateException(e);} }
    private static boolean present(Object v){return v!=null&&!v.toString().isBlank();}
    private List<String> editableKeys(){List<String> keys=new ArrayList<>(List.of("description"));for(Field f:Customer.class.getDeclaredFields())if(f.isAnnotationPresent(Attribute.class))keys.add(f.getName());return keys;}
    private Map<String,Object> fields(Customer c) {
        Map<String,Object> values=new TreeMap<>();values.put("id",c.getId().toString());values.put("version",c.getVersion());values.put("description",c.getDescription());
        for(Field f:Customer.class.getDeclaredFields())if(f.isAnnotationPresent(Attribute.class)) {try{f.setAccessible(true);Object v=f.get(c);values.put(f.getName(),v instanceof Ref<?> r?r.id().toString():v instanceof Enum<?> e?e.name():v);}catch(Exception e){throw new IllegalStateException(e);}}
        return values;
    }
    @SuppressWarnings({"unchecked","rawtypes"}) private void apply(Customer customer,Map<String,Object> values) {
        for(var entry:values.entrySet()) {
            String key=entry.getKey();Object v=entry.getValue();if(Set.of("id","version").contains(key))continue;
            if(key.equals("description")){if(!present(v)||v.toString().length()>200)throw new IllegalArgumentException("Contact name is required (max 200)");customer.setDescription(v.toString());continue;}
            try {Field f=Customer.class.getDeclaredField(key);Attribute a=f.getAnnotation(Attribute.class);if(a==null)throw new IllegalArgumentException("Unknown field");
                if(!present(v))v=null;if(a.required()&&v==null)throw new IllegalArgumentException(key+" is required");
                if(v!=null&&f.getType()==String.class){v=v.toString();if(a.length()>0&&v.toString().length()>a.length())throw new IllegalArgumentException(key+" is too long");if(a.email()&&!v.toString().matches("[^\\s@]+@[^\\s@]+\\.[^\\s@]+"))throw new IllegalArgumentException("Invalid email");}
                if(v!=null&&f.getType().isEnum())v=Enum.valueOf((Class<Enum>)f.getType(),v.toString());
                if(v!=null&&f.getType()==Ref.class){
                    UUID refId=UUID.fromString(v.toString());
                    if(key.equals("stage")) {
                        if(stages.findActiveById(refId).isEmpty())throw new IllegalArgumentException("Choose an active contact stage");
                        v=Ref.of(LifecycleStage.class,refId);
                    } else {
                        if(agents.findActiveById(refId).isEmpty())throw new IllegalArgumentException("Choose an active agent");
                        v=Ref.of(Agent.class,refId);
                    }
                }
                f.setAccessible(true);f.set(customer,v);
            }catch(ReflectiveOperationException e){throw new IllegalArgumentException("Unknown contact field: "+key);}
        }
    }
}
