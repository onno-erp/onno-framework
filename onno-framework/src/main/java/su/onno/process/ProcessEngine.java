package su.onno.process;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Transactional durable runtime for typed {@link ProcessDefinition process definitions}. */
public interface ProcessEngine {

    <P, S extends Enum<S> & ProcessStepKey> ProcessSnapshot start(
            ProcessDefinition<P, S> definition, P payload, ProcessActor actor);

    Optional<ProcessSnapshot> find(UUID instanceId);

    /** Process instances visible to an actor through start, participation, or administration. */
    List<ProcessSnapshot> instances(ProcessActor actor);

    List<ProcessTransitionSnapshot> history(UUID instanceId);

    /** Active and historical execution tokens for an inspectable process timeline. */
    List<ProcessTokenSnapshot> tokens(UUID instanceId);

    List<ProcessWorkItem> inbox(ProcessActor actor);

    ProcessWorkItem claim(UUID workItemId, ProcessActor actor);

    /**
     * Transfer a claimed task to another stable authenticated identity.
     *
     * <p>The current assignee (or an administrator) may delegate. The reason is
     * required and the transfer is recorded in {@link #workItemHistory}.</p>
     */
    ProcessWorkItem delegate(
            UUID workItemId,
            ProcessIdentity target,
            String reason,
            ProcessActor actor
    );

    /** Authorized, chronological audit history for one human task. */
    List<ProcessWorkItemEventSnapshot> workItemHistory(UUID workItemId, ProcessActor actor);

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

    /**
     * Cancel an active process and all of its live tasks, timers, branches, and subprocesses.
     */
    ProcessSnapshot cancel(UUID instanceId, String reason, ProcessActor actor);

    /** Apply the registered definition-migration chain to the latest definition version. */
    ProcessSnapshot migrate(UUID instanceId, ProcessActor actor);

    /**
     * Advance due timers and completed/cancelled subprocess waits.
     *
     * <p>The starter invokes this from its durable background-job scheduler. Calling it from
     * application code is safe; row locks and token state make duplicate polls no-ops.</p>
     */
    int runPending(int limit);
}
