package su.onno.spring;

/** Marks the aggregate currently being persisted as a delete, not an ordinary business write. */
final class DeleteWriteContext implements AutoCloseable {

    private static final ThreadLocal<Object> CURRENT = new ThreadLocal<>();
    private final Object previous;

    private DeleteWriteContext(Object aggregate) {
        previous = CURRENT.get();
        CURRENT.set(aggregate);
    }

    static DeleteWriteContext enter(Object aggregate) {
        return new DeleteWriteContext(aggregate);
    }

    static boolean isDeleting(Object aggregate) {
        return CURRENT.get() == aggregate;
    }

    @Override
    public void close() {
        if (previous == null) CURRENT.remove();
        else CURRENT.set(previous);
    }
}
