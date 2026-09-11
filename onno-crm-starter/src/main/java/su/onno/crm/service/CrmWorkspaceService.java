package su.onno.crm.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.annotation.JsonProperty;
import su.onno.repository.EnumerationPersistence;
import jakarta.annotation.PostConstruct;
import java.lang.reflect.Field;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.context.ApplicationEventPublisher;
import su.onno.annotations.Attribute;
import su.onno.crm.domain.Conversation;
import su.onno.crm.domain.Channel;

import su.onno.events.EntityChangedEvent;

/** Developer-authored inbox presentation around a bound host catalog. */
@Service
public class CrmWorkspaceService {
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final List<CrmWorkspaceCustomizer> customizers;
    private Config declaredConfig;
    private final CrmCustomerBinding<?> customers;
    private final boolean assignment;
    private final CrmStateConfiguration states;
    public CrmStateConfiguration statuses() { return states; }
    public CrmWorkspaceService(JdbcTemplate jdbc, ObjectMapper json, List<CrmWorkspaceCustomizer> customizers, CrmCustomerBinding<?> customers,
            org.springframework.beans.factory.ObjectProvider<CrmAgentBinding<?>> agents,CrmStateConfiguration states) {
        this.jdbc = jdbc; this.json = json; this.customizers = customizers; this.customers = customers; this.assignment = agents.getIfAvailable() != null;this.states=states;
    }
    public static UUID statusId(String value) {
        return UUID.fromString(value);
    }
    public record DisplayField(String key, String label, String section, String format, boolean visible, boolean editable) {}
    public record Action(String key, String label, boolean visible) {}
    /** Folder rules intersect; empty criteria match every value. Order in Config controls display order. */
    public record Folder(String key, String label, List<String> channels,
                         List<String> statuses, List<String> priorities, boolean unreadOnly, List<UUID> conversationIds, boolean matchNone) {
        public Folder {
            conversationIds = List.copyOf(conversationIds);
            channels = List.copyOf(channels);
            statuses = List.copyOf(statuses);
            priorities = List.copyOf(priorities);
        }
        /** Explicitly empty folders remain visible without matching the entire inbox. */
        public static Folder empty(String key, String label) {
            return new Folder(key,label,List.of(),List.of(),List.of(),false,List.of(),true);
        }
        public Folder(String key, String label, List<String> channels, List<String> statuses,
                      List<String> priorities, boolean unreadOnly, List<UUID> conversationIds) {
            this(key,label,channels,statuses,priorities,unreadOnly,conversationIds,false);
        }
        public Folder(String key, String label, List<String> channels, List<String> statuses,
                      List<String> priorities, boolean unreadOnly) {
            this(key,label,channels,statuses,priorities,unreadOnly,List.of());
        }
        public static Folder conversations(String key, String label, List<UUID> ids) {
            if (ids.isEmpty()) throw new IllegalArgumentException("Choose at least one conversation");
            return new Folder(key,label,List.of(),List.of(),List.of(),false,ids);
        }
        @JsonProperty public List<String> channelIds() { return channels; }
        @JsonProperty public List<UUID> statusIds() { return statuses.stream().map(CrmWorkspaceService::statusId).toList(); }
        @JsonProperty public List<UUID> priorityIds() { return priorities.stream().map(UUID::fromString).toList(); }
        public static Folder channel(String key, String label, String channel) {
            return new Folder(key, label, List.of(channel), List.of(), List.of(), false);
        }
    }
    public record Config(List<DisplayField> fields, List<Action> actions,
                         String listTitle, String listSubtitle, String listPreview, boolean showAvatar,
                         boolean showIdentities, boolean showEmpty, boolean showSystemEvents, boolean showTimestamps,
                         boolean showDeliveryStatus, List<Folder> folders) {
        public Config { folders = List.copyOf(folders); }
        public Config withFields(List<DisplayField> value) { return new Config(List.copyOf(value),actions,listTitle,listSubtitle,listPreview,showAvatar,showIdentities,showEmpty,showSystemEvents,showTimestamps,showDeliveryStatus,folders); }
        public Config withActions(List<Action> value) { return new Config(fields,List.copyOf(value),listTitle,listSubtitle,listPreview,showAvatar,showIdentities,showEmpty,showSystemEvents,showTimestamps,showDeliveryStatus,folders); }
        public Config withFolders(List<Folder> value) { return new Config(fields,actions,listTitle,listSubtitle,listPreview,showAvatar,showIdentities,showEmpty,showSystemEvents,showTimestamps,showDeliveryStatus,value); }
        /** Without folders, conversations remain in a flat list. */
        public Config(List<DisplayField> fields, List<Action> actions,
                      String listTitle, String listSubtitle, String listPreview, boolean showAvatar,
                      boolean showIdentities, boolean showEmpty, boolean showSystemEvents, boolean showTimestamps,
                      boolean showDeliveryStatus) {
            this(fields,actions,listTitle,listSubtitle,listPreview,showAvatar,showIdentities,showEmpty,showSystemEvents,showTimestamps,showDeliveryStatus,List.of());
        }
    }
    public record Workspace(long version, Config config, List<DisplayField> availableFields) {}
    public static final Set<String> FORMATS = Set.of("text", "badge", "email", "phone", "url", "date", "number", "boolean");
    public static final Set<String> TYPES = Set.of("text", "number", "date", "boolean", "select", "email", "phone", "url");
    public static final Map<String, String> ACTIONS = new LinkedHashMap<>();
    static {
        ACTIONS.put("logActivity", "Log activity");
        ACTIONS.put("edit", "Open contact");

        ACTIONS.put("details", "Details");
        ACTIONS.put("reply", "Reply"); ACTIONS.put("note", "Internal note"); ACTIONS.put("sendReply", "Send reply"); ACTIONS.put("sendNote", "Add note"); ACTIONS.put("retry", "Retry");
    }
    @PostConstruct public void initialize() {
        Config config = defaults();
        for (var customizer : customizers) config = customizer.customize(config);
        validate(config);
        declaredConfig = config;
        jdbc.execute("CREATE TABLE IF NOT EXISTS onno_crm_contact_guard (id INT PRIMARY KEY)");
        jdbc.execute("INSERT INTO onno_crm_contact_guard (id) SELECT 1 WHERE NOT EXISTS (SELECT 1 FROM onno_crm_contact_guard WHERE id=1)");

    }
    public List<DisplayField> builtins() {
        List<DisplayField> fields = new ArrayList<>(customers.catalog().displayFields());
        for (Field field : Conversation.class.getDeclaredFields()) {
            Attribute a = field.getAnnotation(Attribute.class);
            if (a == null || a.secret()) continue;
            fields.add(new DisplayField("conversation." + field.getName(),
                    a.displayName().isBlank() ? field.getName() : a.displayName(), "Conversation",
                    field.getType().isEnum() ? "badge" : "text", true, false));
        }
        return fields;
    }
    private Config defaults() {
        return new Config(customers.catalog().displayFields(),
                ACTIONS.entrySet().stream().map(e -> new Action(e.getKey(), e.getValue(),
                        (!e.getKey().equals("assign") || assignment) && (!Set.of("close","reopen").contains(e.getKey()) || states.transitions()!=null))).toList(),
                "conversation.customer", "conversation.subject", "conversation.lastMessagePreview",
                true, true, false, true, true, true);
    }
    public Workspace get() {
        Config c = declaredConfig;
        List<DisplayField> available = new ArrayList<>(builtins());
        return new Workspace(0,c,List.copyOf(available));
    }
    public void lock() { jdbc.queryForObject("SELECT id FROM onno_crm_contact_guard WHERE id=1 FOR UPDATE", Integer.class); }
    public void validate(Config c) {
        if (c == null || c.fields() == null || c.actions() == null
                || c.fields().size() > 100 || c.actions().size() > ACTIONS.size())
            throw new IllegalArgumentException("Invalid workspace configuration");
        if (c.folders().size() > 30) throw new IllegalArgumentException("At most 30 conversation folders are allowed");
        Set<String> folderKeys = new HashSet<>();
        for (Folder folder : c.folders()) {
            if (folder.key() == null || !folder.key().matches("[a-z][a-z0-9_]{0,39}") || !folderKeys.add(folder.key()))
                throw new IllegalArgumentException("Folders need unique stable keys");
            text(folder.label(), "Folder label", 80);
        }
        Set<String> keys = new HashSet<>(); builtins().forEach(f -> keys.add(f.key()));
        Set<String> displayed = new HashSet<>();
        for (DisplayField f : c.fields()) {
            if (!keys.contains(f.key()) || !displayed.add(f.key()) || !FORMATS.contains(f.format())) throw new IllegalArgumentException("Invalid or repeated display field");
            text(f.label(), "Label", 80); text(f.section(), "Section", 80);
        }
        for (String key : List.of(c.listTitle(), c.listSubtitle(), c.listPreview()))
            if (!keys.contains(key) || !key.startsWith("conversation.")) throw new IllegalArgumentException("List fields must come from the conversation");
        Set<String> actions = new HashSet<>();
        for (Action a : c.actions()) {
            if (!ACTIONS.containsKey(a.key()) || !actions.add(a.key())) throw new IllegalArgumentException("Unknown or repeated action");
            text(a.label(), "Button label", 80);
        }
    }
    private static void text(String value, String label, int max) {
        if (value == null || value.isBlank() || value.length() > max) throw new IllegalArgumentException(label + " is required (max " + max + ")");
    }
    public String encode(Object data) { try { return json.writeValueAsString(data); } catch (Exception e) { throw new IllegalArgumentException("Invalid CRM data"); } }
    public <T> T decode(String value, Class<T> type) { try { return json.readValue(value, type); } catch (Exception e) { throw new IllegalArgumentException("Invalid stored CRM data"); } }
}
