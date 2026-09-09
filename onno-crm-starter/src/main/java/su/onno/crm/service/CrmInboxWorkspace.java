package su.onno.crm.service;

import java.util.List;
import java.util.Set;
import java.util.function.Predicate;
import java.util.function.UnaryOperator;
import su.onno.crm.domain.Conversation;
import su.onno.ui.ListSpec;
import java.util.function.Consumer;

/** Application-authored inbox view. Selection never changes provider routing or persisted ownership. */
public record CrmInboxWorkspace(String key, String label, Set<String> readRoles, Set<String> writeRoles,
                                Predicate<Conversation> selection,
                                UnaryOperator<CrmWorkspaceService.Config> configure, Consumer<ListSpec<Conversation>> list) {
    public CrmInboxWorkspace {
        if (key == null || !key.matches("[a-z][a-z0-9_-]{0,39}") || label == null || label.isBlank() || label.length() > 80)
            throw new IllegalArgumentException("Inbox workspaces require a stable key and label");
        java.util.Objects.requireNonNull(list);
        readRoles = Set.copyOf(readRoles); writeRoles = Set.copyOf(writeRoles);
        if (readRoles.isEmpty() || selection == null || configure == null) throw new IllegalArgumentException("Workspace access and selection must be explicit");
    }
    public CrmInboxWorkspace(String key, String label, Set<String> readRoles, Set<String> writeRoles,
            Predicate<Conversation> selection, UnaryOperator<CrmWorkspaceService.Config> configure) {
        this(key, label, readRoles, writeRoles, selection, configure, spec -> {});
    }
    /** Author the same list used by both table and inbox renderers. */
    public CrmInboxWorkspace list(Consumer<ListSpec<Conversation>> author) {
        return new CrmInboxWorkspace(key,label,readRoles,writeRoles,selection,configure,author);
    }
    public CrmInboxWorkspace view(su.onno.ui.EntityView<Conversation> view) {
        if(view.entity()!=Conversation.class)throw new IllegalArgumentException("Expected a conversation view");
        return list(view::list);
    }
    public ListSpec<Conversation> listSpec() {
        var spec=new ListSpec<Conversation>(); list.accept(spec); return spec;
    }
    public List<ListSpec.Filter> filters() { return listSpec().filters(); }
    public CrmInboxWorkspace(String key, String label, Set<String> roles, Predicate<Conversation> selection) {
        this(key,label,roles,roles,selection,UnaryOperator.identity());
    }
}
