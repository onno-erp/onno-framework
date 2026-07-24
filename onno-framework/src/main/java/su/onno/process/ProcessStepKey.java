package su.onno.process;

/**
 * Stable identity of a step in a business-process definition.
 *
 * <p>Implement this on an enum used by one process definition. The enum gives callers compile-time
 * safety; {@link #key()} is the stable value that a future persistent runtime can store without
 * coupling process instances to a Java enum constant name.</p>
 */
public interface ProcessStepKey {

    /**
     * Stable persisted key. Override when the enum constant name should not be the storage identity.
     */
    default String key() {
        return ((Enum<?>) this).name();
    }
}
