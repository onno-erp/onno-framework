package su.onno.process;

import java.util.Objects;
import java.util.UUID;

/** Durable link from a task to the catalog or document it concerns. */
public record ProcessDomainLink(String kind, String entityName, UUID id, String label) {

    public ProcessDomainLink {
        kind = Objects.requireNonNull(kind, "kind");
        entityName = Objects.requireNonNull(entityName, "entityName");
        id = Objects.requireNonNull(id, "id");
        label = label == null || label.isBlank() ? null : label.trim();
    }
}
