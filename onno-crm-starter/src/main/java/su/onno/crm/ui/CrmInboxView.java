package su.onno.crm.ui;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import su.onno.ui.PageBuilder;
import su.onno.ui.PageView;

import java.util.*;
import java.util.function.Consumer;
import java.util.stream.Collectors;

/** Typed presentation of an authorized CRM workspace. Configure once at startup (typically in a
 * {@code @Bean} method), then place it with {@code page.view(inbox)}. The first placement freezes
 * the view: later configuration calls throw {@link IllegalStateException}. Workspace beans continue
 * to own data, filters and access rules; {@link CrmInboxViewValidator} fails startup when a view bean's
 * workspace is unknown and warns about {@link #filters filter keys} the workspace bean does not declare.
 */
public final class CrmInboxView implements PageView {
    private static final ObjectMapper JSON = new ObjectMapper();
    private final Map<String, String> config = new LinkedHashMap<>();
    private List<String> filterKeys = List.of();
    private String title = "Inbox";
    private volatile boolean frozen;

    public CrmInboxView(String workspace) { workspace(workspace); }
    public CrmInboxView workspace(String key) { return put("workspace", required(key)); }
    public CrmInboxView title(String title) { mutable(); this.title = required(title); return this; }
    public CrmInboxView search(boolean enabled) { return put("toolbarSearch", Boolean.toString(enabled)); }
    /** Keys of filters declared on the workspace (ListSpec filters and facets), not new filter definitions. */
    public CrmInboxView filters(String... keys) {
        List<String> next = Arrays.stream(keys).map(CrmInboxView::required).toList();
        put("toolbarFilters", String.join(",", next));
        filterKeys = next;
        return this;
    }
    public CrmInboxView toolbarPlacement(ToolbarPlacement placement) { return option("toolbarPlacement", placement); }
    /** Show persistent navigation left of the inbox on wide containers; use a sheet otherwise. */
    public CrmInboxView filterSidebar(boolean enabled) { return put("filterSidebar", Boolean.toString(enabled)); }
    /** Optional app-owned connections page, without the browser /ui prefix. */
    public CrmInboxView connectionsRoute(String route) {
        if (route == null || !route.startsWith("/") || route.startsWith("//")) throw new IllegalArgumentException("Expected an app page route");
        return put("connectionsRoute", route);
    }
    public CrmInboxView mobileToolbar(MobileToolbar toolbar) { return option("mobileToolbar", toolbar); }
    public CrmInboxView controls(Control... controls) { return options("toolbarControls", controls); }
    public CrmInboxView views(View... views) { return options("toolbarViewOptions", views); }
    public CrmInboxView secondary(Secondary... controls) { return options("toolbarSecondary", controls); }

    /** The configured workspace key. */
    public String workspaceKey() { return config.get("workspace"); }
    /** The configured {@link #filters} keys, in order; empty when the renderer shows every declared filter. */
    public List<String> filterKeys() { return filterKeys; }

    /** Renderer item id; defaults to the lowerCamel form of the enum constant name. */
    public interface Item {
        default String key() {
            String[] words = ((Enum<?>) this).name().toLowerCase(Locale.ROOT).split("_");
            StringBuilder key = new StringBuilder(words[0]);
            for (int i = 1; i < words.length; i++) key.append(Character.toUpperCase(words[i].charAt(0))).append(words[i].substring(1));
            return key.toString();
        }
    }
    public enum HeaderItem implements Item { BACK, AVATAR, TITLE, CONTACT, OPEN_CONTACT, SCHEDULE_CALL, LOG_ACTIVITY, EXTENSIONS, ACTIONS }
    public enum ListItem implements Item { TITLE, COUNT, CONTROLS }
    public enum ComposerItem implements Item { MODE, ATTACH, SEND, EXTENSIONS, TOOLS, CHANNEL }
    public enum Control implements Item { TITLE, COUNT, SEARCH, FILTERS, VIEWS, GROUPING, INPUTS, ACTIONS, CREATE, EXTENSIONS, WORKSPACES }
    public enum View implements Item {
        TABLE, MAP,
        /** The conversation (chat) renderer. */
        INBOX { @Override public String key() { return "custom"; } }
    }
    public enum Secondary implements Item { CHANNELS, ACCOUNTS }
    /** Where the list toolbar (search, filters, views) renders. */
    public enum ToolbarPlacement implements Item {
        /** In the page header above the inbox (renderer default). */
        PAGE_HEADER { @Override public String key() { return "header"; } },
        /** Inside the conversation list column. */
        IN_LIST { @Override public String key() { return "renderer"; } }
    }
    public enum MobileToolbar implements Item { INLINE, SHEET }

    /** Toolbar variants. {@link #DEFAULT} applies everywhere unless a narrower variant is set:
     * {@link #NARROW} replaces it when the desktop row still overflows with condensed labels (chat
     * header only), {@link #MOBILE} replaces it on compact (phone) layouts. */
    public enum Breakpoint { DEFAULT, NARROW, MOBILE }

    /** A complete toolbar layout. Every configured toolbar replaces the renderer defaults in full:
     * regions that are not set render nothing, and items not placed anywhere are not shown.
     * An item may appear in at most one region. */
    public static final class Toolbar<I extends Enum<I> & Item> {
        private static final List<String> REGIONS = List.of("start", "center", "end", "secondary", "overflow", "hidden");
        private final Map<String, List<I>> regions = new HashMap<>();
        @SafeVarargs public final Toolbar<I> start(I... items) { return region("start", items); }
        @SafeVarargs public final Toolbar<I> center(I... items) { return region("center", items); }
        @SafeVarargs public final Toolbar<I> end(I... items) { return region("end", items); }
        /** A second row under the main one. */
        @SafeVarargs public final Toolbar<I> secondary(I... items) { return region("secondary", items); }
        /** Items behind a "More" button at the end of the main row. */
        @SafeVarargs public final Toolbar<I> overflow(I... items) { return region("overflow", items); }
        /** Explicitly hidden items; equivalent to not placing them, kept for readability. */
        @SafeVarargs public final Toolbar<I> hidden(I... items) { return region("hidden", items); }

        private Toolbar<I> region(String name, I[] items) {
            regions.put(name, Arrays.stream(items).map(i -> Objects.requireNonNull(i, "item")).toList());
            return this;
        }
        private Map<String, List<String>> layout() {
            Set<I> placed = new HashSet<>();
            Map<String, List<String>> layout = new LinkedHashMap<>();
            for (String name : REGIONS) {
                List<I> items = regions.getOrDefault(name, List.of());
                for (I item : items) if (!placed.add(item))
                    throw new IllegalArgumentException("Toolbar item " + item + " is placed more than once");
                layout.put(name, items.stream().map(Item::key).toList());
            }
            return layout;
        }
    }

    /** Conversation (chat) header. */
    public CrmInboxView header(Consumer<Toolbar<HeaderItem>> configure) { return header(Breakpoint.DEFAULT, configure); }
    public CrmInboxView header(Breakpoint breakpoint, Consumer<Toolbar<HeaderItem>> configure) {
        return toolbar(switch (Objects.requireNonNull(breakpoint, "breakpoint")) {
            case DEFAULT -> "chatToolbar"; case NARROW -> "narrowChatToolbar"; case MOBILE -> "mobileChatToolbar";
        }, configure);
    }
    /** Conversation list header (title, count, list controls). */
    public CrmInboxView listHeader(Consumer<Toolbar<ListItem>> configure) { return listHeader(Breakpoint.DEFAULT, configure); }
    public CrmInboxView listHeader(Breakpoint breakpoint, Consumer<Toolbar<ListItem>> configure) {
        return toolbar(switch (Objects.requireNonNull(breakpoint, "breakpoint")) {
            case DEFAULT -> "listToolbar"; case MOBILE -> "mobileListToolbar";
            case NARROW -> throw new IllegalArgumentException("The list header has no NARROW variant");
        }, configure);
    }
    /** Message composer controls. */
    public CrmInboxView composer(Consumer<Toolbar<ComposerItem>> configure) { return composer(Breakpoint.DEFAULT, configure); }
    public CrmInboxView composer(Breakpoint breakpoint, Consumer<Toolbar<ComposerItem>> configure) {
        return toolbar(switch (Objects.requireNonNull(breakpoint, "breakpoint")) {
            case DEFAULT -> "composerToolbar"; case MOBILE -> "mobileComposerToolbar";
            case NARROW -> throw new IllegalArgumentException("The composer has no NARROW variant");
        }, configure);
    }
    private <I extends Enum<I> & Item> CrmInboxView toolbar(String key, Consumer<Toolbar<I>> configure) {
        Toolbar<I> toolbar = new Toolbar<>();
        Objects.requireNonNull(configure, "configure").accept(toolbar);
        return put(key, json(toolbar.layout()));
    }

    public static final class ConversationRows {
        private final Map<String, Object> values = new LinkedHashMap<>();
        private final List<Map<String, String>> badges = new ArrayList<>();
        public ConversationRows showAccount(boolean show) { values.put("showAccount", show); return this; }
        public ConversationRows showChannelBadge(boolean show) { values.put("showChannelBadge", show); return this; }
        public ConversationRows previewLines(int lines) {
            if (lines != 1 && lines != 2) throw new IllegalArgumentException("previewLines must be 1 or 2");
            values.put("previewLines", lines); return this;
        }
        public ConversationRows badge(String field, String equals, String label) {
            if (badges.size() >= 30) throw new IllegalArgumentException("At most 30 row badges are supported");
            badges.add(Map.of("field", required(field), "equals", Objects.requireNonNull(equals), "label", required(label)));
            return this;
        }
    }
    public CrmInboxView rows(Consumer<ConversationRows> configure) {
        ConversationRows rows = new ConversationRows();
        Objects.requireNonNull(configure, "configure").accept(rows);
        if (!rows.badges.isEmpty()) rows.values.put("badges", rows.badges);
        return put("conversationRow", json(rows.values));
    }
    private CrmInboxView option(String key, Item value) { return put(key, Objects.requireNonNull(value).key()); }
    private CrmInboxView options(String key, Item[] values) {
        return put(key, Arrays.stream(values).map(v -> Objects.requireNonNull(v).key()).collect(Collectors.joining(",")));
    }
    private CrmInboxView put(String key, String value) { mutable(); config.put(key, value); return this; }
    private void mutable() {
        if (frozen) throw new IllegalStateException("CrmInboxView is frozen after its first placement; configure it before composing pages");
    }
    private static String required(String value) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException("Value must not be blank");
        return value;
    }
    private static String json(Object value) {
        try { return JSON.writeValueAsString(value); }
        catch (JsonProcessingException e) { throw new IllegalStateException("Cannot serialize inbox presentation", e); }
    }
    @Override
    public void compose(PageBuilder page) {
        frozen = true;
        var widget = page.widget(title).type("crmInboxWorkspaces");
        config.forEach(widget::config);
    }
}
