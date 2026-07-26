package su.onno.ui;

import su.onno.fields.Field;
import su.onno.fields.Fields;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;

/** Fluent configuration returned by {@link EntityConfigBuilder#validation}. */
public final class FormValidationBuilder<E> {
    private final EntityConfigBuilder<E> parent;
    private final String key;
    private final Class<? extends FormValidator> validator;
    private final List<String> dependencies = new ArrayList<>();
    private long debounceMillis = 250;

    FormValidationBuilder(EntityConfigBuilder<E> parent, String key,
                          Class<? extends FormValidator> validator) {
        this.parent = parent;
        this.key = key;
        this.validator = validator;
    }

    /**
     * Re-run only when one of these values changes. Dot paths address every value in a tabular
     * section column, for example {@code participants.employee}.
     */
    public FormValidationBuilder dependsOn(String... fields) {
        dependencies.clear();
        dependencies.addAll(Arrays.asList(fields));
        return this;
    }

    /** Re-run when one of these compiler-checked header fields changes. */
    @SafeVarargs
    public final FormValidationBuilder<E> dependsOn(Field<E, ?>... fields) {
        dependencies.clear();
        Arrays.stream(fields).map(Fields::name).forEach(dependencies::add);
        return this;
    }

    /** Re-run when a field in every row of a typed collection/tabular section changes. */
    public <R> FormValidationBuilder<E> dependsOn(
            Field<E, ? extends Collection<R>> section,
            Field<R, ?> rowField
    ) {
        dependencies.clear();
        dependencies.add(Fields.path(section, rowField));
        return this;
    }

    /** Add a typed tabular-section dependency without replacing dependencies already declared. */
    public <R> FormValidationBuilder<E> andDependsOn(
            Field<E, ? extends Collection<R>> section,
            Field<R, ?> rowField
    ) {
        dependencies.add(Fields.path(section, rowField));
        return this;
    }

    public FormValidationBuilder<E> debounce(Duration duration) {
        debounceMillis = duration == null ? 0 : Math.max(0, duration.toMillis());
        return this;
    }

    /** Continue configuring another field on the same entity. */
    public FieldHintBuilder<E, Object> field(String name) {
        return parent.field(name);
    }

    FormValidation build() {
        return new FormValidation(key, validator, dependencies, debounceMillis);
    }
}
