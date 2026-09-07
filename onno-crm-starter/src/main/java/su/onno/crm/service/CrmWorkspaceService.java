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
import su.onno.crm.domain.Customer;
import su.onno.crm.domain.Conversation;
import su.onno.crm.domain.Channel;
import su.onno.crm.domain.ConversationStatus;
import su.onno.crm.domain.ConversationPriority;
import su.onno.events.EntityChangedEvent;

/** Developer-authored widget configuration and persisted customer custom values. */
@Service
public class CrmWorkspaceService {
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final List<CrmWorkspaceCustomizer> customizers;
    private Config declaredConfig;
    public CrmWorkspaceService(JdbcTemplate jdbc, ObjectMapper json, List<CrmWorkspaceCustomizer> customizers) {
        this.jdbc = jdbc; this.json = json; this.customizers = customizers;
    }
    public static UUID statusId(String value) {
        try { return UUID.fromString(value); }
        catch(IllegalArgumentException e) { return EnumerationPersistence.resolveId(ConversationStatus.class,ConversationStatus.valueOf(value)); }
    }
    public record DisplayField(String key, String label, String section, String format, boolean visible, boolean editable) {}
    public record CustomField(String key, String label, String type, List<String> options, boolean required) {}
    public record Action(String key, String label, boolean visible) {}
    /** Folder rules intersect; empty criteria match every value. Order in Config controls display order. */
    public record Folder(String key, String label, List<Channel> channels,
                         List<String> statuses, List<ConversationPriority> priorities, boolean unreadOnly, List<UUID> conversationIds) {
        public Folder {
            conversationIds = List.copyOf(conversationIds);
            channels = List.copyOf(channels);
            statuses = List.copyOf(statuses);
            priorities = List.copyOf(priorities);
        }
        public Folder(String key, String label, List<Channel> channels, List<String> statuses,
                      List<ConversationPriority> priorities, boolean unreadOnly) {
            this(key,label,channels,statuses,priorities,unreadOnly,List.of());
        }
        public static Folder conversations(String key, String label, List<UUID> ids) {
            if (ids.isEmpty()) throw new IllegalArgumentException("Choose at least one conversation");
            return new Folder(key,label,List.of(),List.of(),List.of(),false,ids);
        }
        @JsonProperty public List<UUID> channelIds() { return channels.stream().map(v -> EnumerationPersistence.resolveId(Channel.class, v)).toList(); }
        @JsonProperty public List<UUID> statusIds() { return statuses.stream().map(CrmWorkspaceService::statusId).toList(); }
        @JsonProperty public List<UUID> priorityIds() { return priorities.stream().map(v -> EnumerationPersistence.resolveId(ConversationPriority.class, v)).toList(); }
        public static Folder channel(String key, String label, Channel channel) {
            return new Folder(key, label, List.of(channel), List.of(), List.of(), false);
        }
    }
    public record Config(List<DisplayField> fields, List<CustomField> customFields, List<Action> actions,
                         String listTitle, String listSubtitle, String listPreview, boolean showAvatar,
                         boolean showIdentities, boolean showEmpty, boolean showSystemEvents, boolean showTimestamps,
                         boolean showDeliveryStatus, List<Folder> folders) {
        public Config { folders = List.copyOf(folders); }
        public Config withFields(List<DisplayField> value) { return new Config(List.copyOf(value),customFields,actions,listTitle,listSubtitle,listPreview,showAvatar,showIdentities,showEmpty,showSystemEvents,showTimestamps,showDeliveryStatus,folders); }
        public Config withCustomFields(List<CustomField> value) { return new Config(fields,List.copyOf(value),actions,listTitle,listSubtitle,listPreview,showAvatar,showIdentities,showEmpty,showSystemEvents,showTimestamps,showDeliveryStatus,folders); }
        public Config withActions(List<Action> value) { return new Config(fields,customFields,List.copyOf(value),listTitle,listSubtitle,listPreview,showAvatar,showIdentities,showEmpty,showSystemEvents,showTimestamps,showDeliveryStatus,folders); }
        public Config withFolders(List<Folder> value) { return new Config(fields,customFields,actions,listTitle,listSubtitle,listPreview,showAvatar,showIdentities,showEmpty,showSystemEvents,showTimestamps,showDeliveryStatus,value); }
        /** Compatibility constructor: no folders keeps the flat conversation list. */
        public Config(List<DisplayField> fields, List<CustomField> customFields, List<Action> actions,
                      String listTitle, String listSubtitle, String listPreview, boolean showAvatar,
                      boolean showIdentities, boolean showEmpty, boolean showSystemEvents, boolean showTimestamps,
                      boolean showDeliveryStatus) {
            this(fields,customFields,actions,listTitle,listSubtitle,listPreview,showAvatar,showIdentities,showEmpty,showSystemEvents,showTimestamps,showDeliveryStatus,List.of());
        }
    }
    public record Workspace(long version, Config config, List<DisplayField> availableFields) {}
    public static final Set<String> FORMATS = Set.of("text", "badge", "email", "phone", "url", "date", "number", "boolean");
    public static final Set<String> TYPES = Set.of("text", "number", "date", "boolean", "select", "email", "phone", "url");
    public static final Map<String, String> ACTIONS = new LinkedHashMap<>();
    static {
        ACTIONS.put("logActivity", "Log activity");
        ACTIONS.put("edit", "Edit contact"); ACTIONS.put("merge", "Merge contacts");
        ACTIONS.put("identity", "Link identity"); ACTIONS.put("history", "Merge history");
        ACTIONS.put("assign", "Assign to me"); ACTIONS.put("details", "Details");
        ACTIONS.put("close", "Close"); ACTIONS.put("reopen", "Reopen"); ACTIONS.put("reply", "Reply"); ACTIONS.put("note", "Internal note"); ACTIONS.put("sendReply", "Send reply"); ACTIONS.put("sendNote", "Add note"); ACTIONS.put("retry", "Retry");
    }
    @PostConstruct public void initialize() {
        Config config = defaults();
        for (var customizer : customizers) config = customizer.customize(config);
        validate(config);
        declaredConfig = config;
        jdbc.execute("CREATE TABLE IF NOT EXISTS onno_crm_contact_values (customer_id UUID PRIMARY KEY, version BIGINT NOT NULL, data TEXT NOT NULL)");
        jdbc.execute("CREATE TABLE IF NOT EXISTS onno_crm_contact_guard (id INT PRIMARY KEY)");
        jdbc.execute("INSERT INTO onno_crm_contact_guard (id) SELECT 1 WHERE NOT EXISTS (SELECT 1 FROM onno_crm_contact_guard WHERE id=1)");

    }
    public static List<DisplayField> builtins() {
        List<DisplayField> fields = new ArrayList<>();
        fields.add(new DisplayField("customer.description", "Name", "Contact", "text", true, true));
        for (Class<?> type : List.of(Customer.class, Conversation.class)) {
            for (Field field : type.getDeclaredFields()) {
                Attribute a = field.getAnnotation(Attribute.class);
                if (a == null || a.secret()) continue;
                String prefix = type == Customer.class ? "customer." : "conversation.";
                String label = a.displayName().isBlank() ? field.getName() : a.displayName();
                String format = a.email() ? "email" : field.getType().isEnum() ? "badge"
                        : field.getName().equals("phone") ? "phone" : "text";
                fields.add(new DisplayField(prefix + field.getName(), label,
                        type == Customer.class ? "Contact" : "Conversation", format, true, type == Customer.class));
            }
        }
        return fields;
    }
    private Config defaults() {
        List<String> keys = List.of("customer.description", "customer.company", "customer.stage", "customer.tags", "customer.email", "customer.phone", "customer.city", "customer.owner", "customer.source", "conversation.priority", "conversation.assignee");
        Map<String, DisplayField> all = new HashMap<>(); builtins().forEach(f -> all.put(f.key(), f));
        List<DisplayField> fields = keys.stream().map(k -> {
            var f = all.get(k);
            return new DisplayField(k, f.label(), k.equals("conversation.assignee") ? "Assignment" : Set.of("customer.owner", "customer.source", "conversation.priority").contains(k) ? "Relationship" : "Contact", f.format(), true, f.editable());
        }).toList();
        return new Config(fields, List.of(), ACTIONS.entrySet().stream().map(e -> new Action(e.getKey(), e.getValue(), true)).toList(),
                "conversation.customer", "conversation.subject", "conversation.lastMessagePreview", true, true, false, true, true, true);
    }
    public Workspace get() {
        Config c = declaredConfig;
        List<DisplayField> available = new ArrayList<>(builtins());
        c.customFields().forEach(f -> available.add(new DisplayField("custom." + f.key(), f.label(), "Additional details", f.type().equals("select") ? "badge" : f.type(), true, true)));
        return new Workspace(0,c,List.copyOf(available));
    }
    public void lock() { jdbc.queryForObject("SELECT id FROM onno_crm_contact_guard WHERE id=1 FOR UPDATE", Integer.class); }
    public void validate(Config c) {
        if (c == null || c.fields() == null || c.customFields() == null || c.actions() == null
                || c.fields().size() > 100 || c.customFields().size() > 50 || c.actions().size() > ACTIONS.size())
            throw new IllegalArgumentException("Invalid workspace configuration");
        if (c.folders().size() > 30) throw new IllegalArgumentException("At most 30 conversation folders are allowed");
        Set<String> folderKeys = new HashSet<>();
        for (Folder folder : c.folders()) {
            if (folder.key() == null || !folder.key().matches("[a-z][a-z0-9_]{0,39}") || !folderKeys.add(folder.key()))
                throw new IllegalArgumentException("Folders need unique stable keys");
            text(folder.label(), "Folder label", 80);
        }
        Set<String> keys = new HashSet<>(); builtins().forEach(f -> keys.add(f.key()));
        Set<String> custom = new HashSet<>();
        for (CustomField f : c.customFields()) {
            if (f.key() == null || !f.key().matches("[a-z][a-z0-9_]{0,39}") || !custom.add(f.key()) || !TYPES.contains(f.type()))
                throw new IllegalArgumentException("Custom fields need unique stable keys and a supported type");
            text(f.label(), "Field label", 80); keys.add("custom." + f.key());
            if (f.options() == null || f.options().size() > 50) throw new IllegalArgumentException("Invalid field options");
            f.options().forEach(o -> text(o, "Option", 80));
            if (f.type().equals("select") && f.options().isEmpty()) throw new IllegalArgumentException("Select fields need options");
        }
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
    @SuppressWarnings("unchecked") public Map<String, Object> values(UUID customer) {
        List<String> values = jdbc.query("SELECT data FROM onno_crm_contact_values WHERE customer_id=?", (rs, n) -> rs.getString(1), customer);
        return values.isEmpty() ? new LinkedHashMap<>() : decode(values.getFirst(), LinkedHashMap.class);
    }
    public void writeValues(UUID customer, Map<String, Object> values) {
        if (jdbc.update("UPDATE onno_crm_contact_values SET version=version+1,data=? WHERE customer_id=?", encode(values), customer) == 0)
            jdbc.update("INSERT INTO onno_crm_contact_values VALUES (?,0,?)", customer, encode(values));
    }
    public void validateValues(Map<String, Object> values) {
        Map<String, CustomField> fields = new HashMap<>(); get().config().customFields().forEach(f -> fields.put(f.key(), f));
        if (!fields.keySet().containsAll(values.keySet())) throw new IllegalArgumentException("Unknown custom field");
        for (CustomField field : fields.values()) {
            Object value = values.get(field.key());
            if (value == null || value.toString().isBlank()) {
                if (field.required()) throw new IllegalArgumentException(field.label() + " is required");
                continue;
            }
            String s = value.toString();
            if (s.length() > 4000) throw new IllegalArgumentException(field.label() + " is too long");
            try {
                switch (field.type()) {
                    case "number" -> new java.math.BigDecimal(s);
                    case "date" -> java.time.LocalDate.parse(s);
                    case "boolean" -> { if (!(value instanceof Boolean)) throw new IllegalArgumentException(); }
                    case "select" -> { if (!field.options().contains(s)) throw new IllegalArgumentException(); }
                    case "email" -> { if (!s.matches("[^\\s@]+@[^\\s@]+\\.[^\\s@]+")) throw new IllegalArgumentException(); }
                    case "url" -> { if (!s.matches("https?://[^\\s]+")) throw new IllegalArgumentException(); }
                    default -> { }
                }
            } catch (RuntimeException e) { throw new IllegalArgumentException("Invalid value for " + field.label()); }
        }
    }
}
