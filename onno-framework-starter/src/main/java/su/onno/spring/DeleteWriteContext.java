package su.onno.spring;

/**
 * Marks the aggregate currently being saved as a soft delete rather than an ordinary business write,
 * so {@link OnnoBeforeConvertCallback} skips {@code beforeWrite} and validation for it. The marker is
 * bound to the aggregate's identity: anything a callback saves on the same thread is an ordinary write.
 */
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
        return aggregate != null && CURRENT.get() == aggregate;
    }

    @Override
    public void close() {
        if (previous == null) CURRENT.remove();
        else CURRENT.set(previous);
    }
}
