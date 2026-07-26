package su.onno.process;

import java.util.Set;
import java.util.UUID;

/**
 * Post-commit invalidation for the process-task inboxes whose visible work may have changed.
 *
 * <p>The audience is deliberately server-side metadata: UI transports use it to route a
 * payload-free refresh signal, never to expose candidate assignments to browsers.</p>
 */
public record ProcessTasksChangedEvent(
        UUID instanceId,
        Set<String> audienceUsers,
        Set<String> audienceRoles
) {
    public ProcessTasksChangedEvent {
        audienceUsers = audienceUsers == null ? Set.of() : Set.copyOf(audienceUsers);
        audienceRoles = audienceRoles == null ? Set.of() : Set.copyOf(audienceRoles);
    }
}
