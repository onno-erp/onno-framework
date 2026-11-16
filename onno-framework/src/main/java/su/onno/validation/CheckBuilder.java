package su.onno.validation;

import su.onno.fields.Field;
import su.onno.fields.Fields;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;

/**
 * Fluent configuration of one live check, from {@link CheckSpec#check}.
 *
 * @param <E> the owning entity
 */
public final class CheckBuilder<E> {
    private final CheckSpec<E> parent;
    private final String key;
    private final Check check;
    private final List<String> dependencies = new ArrayList<>();
    private long debounceMillis = 250;

    CheckBuilder(CheckSpec<E> parent, String key, Check check) {
        this.parent = parent;
        this.key = key;
        this.check = check;
    }

    /**
     * Re-run only when one of these values changes. Dot paths address every value in a tabular
     * section column, for example {@code participants.employee}.
     */
    public CheckBuilder<E> dependsOn(String... fields) {
        dependencies.clear();
        dependencies.addAll(Arrays.asList(fields));
        return this;
    }

    /** Re-run when one of these compiler-checked header fields changes. */
    @SafeVarargs
    public final CheckBuilder<E> dependsOn(Field<E, ?>... fields) {
        dependencies.clear();
        Arrays.stream(fields).map(Fields::name).forEach(dependencies::add);
        return this;
    }

    /** Re-run when a field in any row of a typed tabular section changes. */
    public <R> CheckBuilder<E> dependsOn(Field<E, ? extends Collection<R>> section, Field<R, ?> rowField) {
        dependencies.clear();
        dependencies.add(Fields.path(section, rowField));
        return this;
    }

    /** Add dependencies (field names or dot paths) without replacing those already declared. */
    public CheckBuilder<E> andDependsOn(String... fields) {
        dependencies.addAll(Arrays.asList(fields));
        return this;
    }

    /** Add a typed tabular-section dependency without replacing those already declared. */
    public <R> CheckBuilder<E> andDependsOn(Field<E, ? extends Collection<R>> section, Field<R, ?> rowField) {
        dependencies.add(Fields.path(section, rowField));
        return this;
    }

    /** How long a client waits after the last change before re-running the check (default 250ms). */
    public CheckBuilder<E> debounce(Duration duration) {
        debounceMillis = duration == null ? 0 : Math.max(0, duration.toMillis());
        return this;
    }

    /** Declare another check on the same entity. */
    public CheckBuilder<E> check(String key, Check check) {
        return parent.check(key, check);
    }

    CheckDefinition build() {
        return new CheckDefinition(key, check, dependencies, debounceMillis);
    }
}
