package su.onno.crm.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Personal inbox organization, separate from shared business data and authored folder rules. */
@Service
public class CrmChatGroupService {
    public record Group(String key, String label, List<UUID> customerIds) {}
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    public CrmChatGroupService(JdbcTemplate jdbc, ObjectMapper json) { this.jdbc=jdbc;this.json=json; }
    @PostConstruct public void initialize() {
        jdbc.execute("CREATE TABLE IF NOT EXISTS onno_crm_chat_groups (owner VARCHAR(500) NOT NULL, scope VARCHAR(80) NOT NULL, data TEXT NOT NULL, PRIMARY KEY(owner,scope))");
        jdbc.execute("CREATE TABLE IF NOT EXISTS onno_crm_chat_group_guard (id INT PRIMARY KEY)");
        jdbc.execute("INSERT INTO onno_crm_chat_group_guard (id) SELECT 1 WHERE NOT EXISTS (SELECT 1 FROM onno_crm_chat_group_guard WHERE id=1)");
    }
    public List<Group> list(String owner,String scope) {
        return jdbc.query("SELECT data FROM onno_crm_chat_groups WHERE owner=? AND scope=?",(rs,n)->decode(rs.getString(1)),owner,scope).stream().findFirst().orElse(List.of());
    }
    /** Serialize read-modify-write commands so concurrent tabs never lose each other's changes. */
    @Transactional public List<Group> change(String owner,String scope,String operation,String key,String label,UUID customer) {
        jdbc.queryForObject("SELECT id FROM onno_crm_chat_group_guard WHERE id=1 FOR UPDATE",Integer.class);
        var groups=new ArrayList<>(list(owner,scope));
        if ("create".equals(operation)||"rename".equals(operation)) {
            if(label==null||label.isBlank()||label.strip().length()>80)throw new IllegalArgumentException("Group name must contain 1–80 characters");
            label=label.strip();
            for(var group:groups)if(!group.key().equals(key)&&group.label().equalsIgnoreCase(label))throw new IllegalArgumentException("A group with this name already exists");
        }
        if("create".equals(operation)) {
            if(groups.size()>=30)throw new IllegalArgumentException("At most 30 groups are allowed");
            key="group_"+UUID.randomUUID().toString().replace("-","");
            groups.add(new Group(key,label,List.of()));
        } else if(!("move".equals(operation)&&key==null)&&!groups.stream().map(Group::key).toList().contains(key)) {
            throw new IllegalArgumentException("Group no longer exists; refresh the inbox");
        }
        if("move".equals(operation)||"create".equals(operation)) {
            if(customer==null)throw new IllegalArgumentException("Choose a chat");
            for(int i=0;i<groups.size();i++) {
                var group=groups.get(i);var ids=new ArrayList<>(group.customerIds());ids.remove(customer);
                if(group.key().equals(key))ids.add(customer);
                groups.set(i,new Group(group.key(),group.label(),List.copyOf(ids)));
            }
        } else if("rename".equals(operation)) {
            for(int i=0;i<groups.size();i++)if(groups.get(i).key().equals(key))groups.set(i,new Group(key,label,groups.get(i).customerIds()));
        } else if("delete".equals(operation)) {
            final String target=key;groups.removeIf(g->g.key().equals(target));
        } else throw new IllegalArgumentException("Unknown group operation");
        String data=encode(groups);
        if(jdbc.update("UPDATE onno_crm_chat_groups SET data=? WHERE owner=? AND scope=?",data,owner,scope)==0)
            jdbc.update("INSERT INTO onno_crm_chat_groups(owner,scope,data) VALUES(?,?,?)",owner,scope,data);
        return List.copyOf(groups);
    }
    private List<Group> decode(String data) {try{return json.readValue(data,new TypeReference<List<Group>>(){});}catch(Exception e){throw new IllegalStateException("Cannot read chat groups",e);}}
    private String encode(List<Group> groups) {try{return json.writeValueAsString(groups);}catch(Exception e){throw new IllegalStateException("Cannot save chat groups",e);}}
}
