package su.onno.crm.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.annotation.PostConstruct;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/** The CRM workspace defaults (actions, folders) around a bound host catalog, customized by {@link CrmWorkspaceCustomizer} beans. */
@Service
public class CrmWorkspaceService {
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final List<CrmWorkspaceCustomizer> customizers;
    private Config declaredConfig;
    private final CrmCustomerBinding<?> customers;
    private final CrmStateConfiguration states;
    public CrmStateConfiguration statuses() { return states; }
    public CrmWorkspaceService(JdbcTemplate jdbc, ObjectMapper json, List<CrmWorkspaceCustomizer> customizers, CrmCustomerBinding<?> customers,
            org.springframework.beans.factory.ObjectProvider<CrmAgentBinding<?>> agents,CrmStateConfiguration states) {
        this.jdbc = jdbc; this.json = json; this.customizers = customizers; this.customers = customers; this.states=states;
    }
    public static UUID statusId(String value) {
        return UUID.fromString(value);
    }
    /** The conversation actions a workspace may offer; a workspace's {@link Config#actions()} enables a subset. */
    public static final Set<String> ACTIONS = Collections.unmodifiableSet(new LinkedHashSet<>(List.of(
            "logActivity", "edit", "details", "reply", "note", "sendReply", "sendNote", "retry")));
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
    /**
     * What a workspace does: the {@link #ACTIONS} it enables (a read-only monitoring workspace drops
     * {@code reply} and {@code sendReply}) and the folders that group its conversations. How the
     * inbox presents — labels, icons, which contact fields show — is the client's.
     */
    public record Config(Set<String> actions, List<Folder> folders) {
        public Config {
            actions = Collections.unmodifiableSet(new LinkedHashSet<>(actions));
            folders = List.copyOf(folders);
        }
        public Config withActions(Set<String> value) { return new Config(value, folders); }
        /** The same configuration without these actions. */
        public Config without(String... value) {
            var next = new LinkedHashSet<>(actions);
            List.of(value).forEach(next::remove);
            return new Config(next, folders);
        }
        public Config withFolders(List<Folder> value) { return new Config(actions, value); }
    }
    /** The declared configuration and the bound contact fields ({@code customer.<key>}), in binding order. */
    public record Workspace(long version, Config config, List<String> fields) {}
    @PostConstruct public void initialize() {
        Config config = new Config(ACTIONS, List.of());
        for (var customizer : customizers) config = customizer.customize(config);
        validate(config);
        declaredConfig = config;
        jdbc.execute("CREATE TABLE IF NOT EXISTS onno_crm_contact_guard (id INT PRIMARY KEY)");
        jdbc.execute("INSERT INTO onno_crm_contact_guard (id) SELECT 1 WHERE NOT EXISTS (SELECT 1 FROM onno_crm_contact_guard WHERE id=1)");

    }
    public Workspace get() {
        return new Workspace(0, declaredConfig, customers.catalog().keys());
    }
    public void lock() { jdbc.queryForObject("SELECT id FROM onno_crm_contact_guard WHERE id=1 FOR UPDATE", Integer.class); }
    public void validate(Config c) {
        if (c == null || c.actions() == null || c.folders() == null || !ACTIONS.containsAll(c.actions()))
            throw new IllegalArgumentException("Invalid workspace configuration");
        if (c.folders().size() > 30) throw new IllegalArgumentException("At most 30 conversation folders are allowed");
        Set<String> folderKeys = new HashSet<>();
        for (Folder folder : c.folders()) {
            if (folder.key() == null || !folder.key().matches("[a-z][a-z0-9_]{0,39}") || !folderKeys.add(folder.key()))
                throw new IllegalArgumentException("Folders need unique stable keys");
            if (folder.label() == null || folder.label().isBlank() || folder.label().length() > 80)
                throw new IllegalArgumentException("Folder label is required (max 80)");
        }
    }
    public String encode(Object data) { try { return json.writeValueAsString(data); } catch (Exception e) { throw new IllegalArgumentException("Invalid CRM data"); } }
    public <T> T decode(String value, Class<T> type) { try { return json.readValue(value, type); } catch (Exception e) { throw new IllegalArgumentException("Invalid stored CRM data"); } }
}
