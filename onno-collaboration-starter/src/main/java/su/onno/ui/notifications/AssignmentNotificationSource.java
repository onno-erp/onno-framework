package su.onno.ui.notifications;

import su.onno.events.EntityChangedEvent;
import su.onno.metadata.AttributeDescriptor;
import su.onno.metadata.CatalogDescriptor;
import su.onno.metadata.DocumentDescriptor;
import su.onno.metadata.MetadataRegistry;
import su.onno.ui.UiLayout;
import su.onno.ui.comments.Mentions;

import org.jdbi.v3.core.Jdbi;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Built-in notification producer for record assignment. When a catalog or document with a {@code Ref<>}
 * attribute annotated {@link AssigneeField} is created or updated and that attribute points at a user,
 * the user is notified the record was assigned to them.
 *
 * <p>It listens on the framework's {@link EntityChangedEvent}, which carries no field values, so it
 * reads the current assignee back from the row (its own committed transaction — the event fires after
 * the write commits). Because the event also carries no <em>previous</em> value, a small in-memory
 * last-assignee cache suppresses re-notifying on unrelated updates: a fresh notification fires on
 * create, and on update only when the assignee actually changed. The cache is best-effort — after a
 * restart the first update to a record may re-notify its current assignee once.
 *
 * <p>Only an {@link AssigneeField} whose target is the identity catalog raises a notification; one
 * pointing elsewhere is ignored. Gated by {@code onno.notifications.assignments.enabled} (default true).
 */
public class AssignmentNotificationSource {

    private final NotificationService notifications;
    private final UiLayout layout;
    private final MetadataRegistry registry;
    private final Jdbi jdbi;

    /** Last assignee we saw per record id, so an update that didn't touch the assignee doesn't re-notify. */
    private final Map<UUID, String> lastAssignee = new ConcurrentHashMap<>();

    public AssignmentNotificationSource(NotificationService notifications, UiLayout layout,
                                        MetadataRegistry registry, Jdbi jdbi) {
        this.notifications = notifications;
        this.layout = layout;
        this.registry = registry;
        this.jdbi = jdbi;
    }

    // Fire AFTER the write commits, not inside its transaction: the producer reads the current assignee
    // back on a fresh JDBI connection, which can't see the row until the save commits (a plain
    // @EventListener would run mid-transaction and read nothing, so every assignment would be missed).
    // fallbackExecution=true keeps it working when the save runs with no surrounding transaction (the
    // row is already committed by then).
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onChange(EntityChangedEvent event) {
        boolean created = EntityChangedEvent.CREATED.equals(event.changeType());
        boolean updated = EntityChangedEvent.UPDATED.equals(event.changeType());
        if ((!created && !updated) || event.id() == null) {
            return;
        }
        Target target = target(event.entityType(), event.entityName());
        if (target == null) {
            return;
        }
        AttributeDescriptor assignee = assigneeAttribute(target);
        if (assignee == null) {
            return;
        }
        // The assignee must point at the identity catalog — otherwise its target has no inbox.
        String identityLogicalName = NotificationIdentity.logicalName(layout, registry);
        if (identityLogicalName == null || !identityLogicalName.equals(assignee.refTarget())) {
            return;
        }

        UUID recordId = event.id();
        Assignment current = readAssignment(target, assignee, recordId);
        if (current == null || current.assigneeId() == null) {
            lastAssignee.remove(recordId); // unassigned now — forget the last value so a re-assign notifies
            return;
        }
        String recipientId = current.assigneeId();
        String previous = lastAssignee.put(recordId, recipientId);
        if (updated && recipientId.equals(previous)) {
            return; // an update that didn't change the assignee — nothing to announce
        }

        String label = current.label() == null || current.label().isBlank()
                ? target.displayName() : current.label();
        notifications.notify(NotificationRequest.to(recipientId)
                .type("assignment")
                .title("You were assigned " + label)
                .link(target.kind() + "/" + Mentions.routeName(target.logicalName()) + "/" + recordId)
                .build());
    }

    /** Resolve the changed entity to the fields the producer needs, or null if it isn't a catalog/document. */
    private Target target(String entityType, String entityName) {
        if (EntityChangedEvent.CATALOG.equals(entityType)) {
            for (CatalogDescriptor c : registry.allCatalogs()) {
                if (c.logicalName().equals(entityName)) {
                    return new Target("catalogs", c.logicalName(), c.displayTitle(), c.tableName(),
                            c.javaClass(), c.attributes(), "_description");
                }
            }
        } else if (EntityChangedEvent.DOCUMENT.equals(entityType)) {
            for (DocumentDescriptor d : registry.allDocuments()) {
                if (d.logicalName().equals(entityName)) {
                    return new Target("documents", d.logicalName(), d.displayTitle(), d.tableName(),
                            d.javaClass(), d.attributes(), "_number");
                }
            }
        }
        return null;
    }

    /** The first attribute annotated {@link AssigneeField}, or null when the entity declares none. */
    private static AttributeDescriptor assigneeAttribute(Target target) {
        for (AttributeDescriptor attr : target.attributes()) {
            Field field = NotificationIdentity.field(target.javaClass(), attr.fieldName());
            if (field != null && field.isAnnotationPresent(AssigneeField.class)) {
                return attr;
            }
        }
        return null;
    }

    /** Read the current assignee id and a display label for the row, or null if the row is gone. */
    private Assignment readAssignment(Target target, AttributeDescriptor assignee, UUID recordId) {
        // Column names come from trusted descriptors; only the id is bound.
        String sql = "SELECT " + assignee.columnName() + " AS assignee, " + target.labelColumn() + " AS label"
                + " FROM " + target.tableName() + " WHERE _id = :id AND _deletion_mark = false";
        Map<String, Object> row = jdbi.withHandle(h ->
                h.createQuery(sql).bind("id", recordId).mapToMap().findOne().orElse(null));
        if (row == null) {
            return null;
        }
        Object assigneeValue = row.get("assignee");
        Object label = row.get("label");
        return new Assignment(assigneeValue == null ? null : assigneeValue.toString(),
                label == null ? null : label.toString());
    }

    /** The changed entity resolved to what the producer reads: route kind, names, table, and columns. */
    private record Target(String kind, String logicalName, String displayName, String tableName,
                          Class<?> javaClass, List<AttributeDescriptor> attributes, String labelColumn) {}

    /** The current assignee id (a Ref UUID as text) and a display label for the assigned record. */
    private record Assignment(String assigneeId, String label) {}
}
