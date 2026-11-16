package su.onno.crm.service;

/**
 * Marks work that brings in a channel's past messages as history rather than as news: a mailbox's
 * older mail imported once, for instance. Listeners that react to new conversations and messages
 * (inbox refreshes, notifications, lead automation) check {@link #active()} and stay quiet for it.
 *
 * <p>The mark belongs to the current thread. It covers the transactions run inside {@link #run},
 * including their {@code AFTER_COMMIT} listeners, which Spring calls on the committing thread. A
 * listener that hands work to another thread must read the mark before it does.
 */
public final class CrmHistoryImport {
    private static final ThreadLocal<Boolean> ACTIVE = new ThreadLocal<>();

    private CrmHistoryImport() {}

    /** Whether the current thread is importing history. */
    public static boolean active() {
        return Boolean.TRUE.equals(ACTIVE.get());
    }

    /** Run {@code work} as a history import. */
    public static void run(Runnable work) {
        boolean outer = active();
        ACTIVE.set(Boolean.TRUE);
        try {
            work.run();
        } finally {
            if (!outer) ACTIVE.remove();
        }
    }
}
