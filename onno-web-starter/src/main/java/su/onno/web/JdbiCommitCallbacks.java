package su.onno.web;

import org.jdbi.v3.core.Handle;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Defers publication for transactions started through Jdbi. */
public final class JdbiCommitCallbacks {

    private JdbiCommitCallbacks() {
    }

    /**
     * Registers the action on a Jdbi-owned transaction and returns whether it was registered.
     * Spring-managed transactions are handled by the event publisher instead.
     */
    public static boolean defer(Handle handle, Runnable action) {
        if (TransactionSynchronizationManager.isActualTransactionActive() || !handle.isInTransaction()) {
            return false;
        }
        handle.afterCommit(action);
        return true;
    }
}
