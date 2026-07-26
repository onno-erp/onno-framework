package su.onno.repository;

/**
 * @deprecated use the shared {@link su.onno.fields.Field} token. Kept as a source-compatible alias
 * for repository declarations written before the UI and query DSLs adopted the same field type.
 */
@Deprecated(forRemoval = true)
@FunctionalInterface
public interface FieldReference<T, R> extends su.onno.fields.Field<T, R> {
}
