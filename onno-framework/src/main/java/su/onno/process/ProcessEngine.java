package su.onno.process;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Transactional durable runtime for typed {@link ProcessDefinition process definitions}. */
public interface ProcessEngine {

    <P, S extends Enum<S> & ProcessStepKey> ProcessSnapshot start(
            ProcessDefinition<P, S> definition, P payload, ProcessActor actor);

    Optional<ProcessSnapshot> find(UUID instanceId);

    List<ProcessTransitionSnapshot> history(UUID instanceId);

    List<ProcessWorkItem> inbox(ProcessActor actor);

    ProcessWorkItem claim(UUID workItemId, ProcessActor actor);

    /** Complete from typed Java; the enum constant is validated again against the active task. */
    default <O extends Enum<O>> ProcessSnapshot complete(
            UUID workItemId,
            O outcome,
            ProcessActor actor
    ) {
        return complete(workItemId, outcome.name(), actor);
    }

    /** Dynamic/transport boundary used when an enum constant arrives as JSON text. */
    ProcessSnapshot complete(UUID workItemId, String outcome, ProcessActor actor);
}
