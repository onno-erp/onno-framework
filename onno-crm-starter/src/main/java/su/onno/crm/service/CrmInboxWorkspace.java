package su.onno.crm.service;

import java.util.List;
import java.util.Set;
import java.util.function.Predicate;
import java.util.function.UnaryOperator;
import su.onno.crm.domain.Conversation;

/** Application-authored inbox view. Selection never changes provider routing or persisted ownership. */
public record CrmInboxWorkspace(String key, String label, Set<String> readRoles, Set<String> writeRoles,
                                Predicate<Conversation> selection,
                                UnaryOperator<CrmWorkspaceService.Config> configure) {
    public CrmInboxWorkspace {
        if (key == null || !key.matches("[a-z][a-z0-9_-]{0,39}") || label == null || label.isBlank() || label.length() > 80)
            throw new IllegalArgumentException("Inbox workspaces require a stable key and label");
        readRoles = Set.copyOf(readRoles); writeRoles = Set.copyOf(writeRoles);
        if (readRoles.isEmpty() || selection == null || configure == null) throw new IllegalArgumentException("Workspace access and selection must be explicit");
    }
    public CrmInboxWorkspace(String key, String label, Set<String> roles, Predicate<Conversation> selection) {
        this(key,label,roles,roles,selection,UnaryOperator.identity());
    }
}
