package su.onno.ui;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Fluent configuration returned by {@link EntityConfigBuilder#validation}. */
public final class FormValidationBuilder {
    private final EntityConfigBuilder parent;
    private final String key;
    private final Class<? extends FormValidator> validator;
    private final List<String> dependencies = new ArrayList<>();
    private long debounceMillis = 250;

    FormValidationBuilder(EntityConfigBuilder parent, String key,
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

    public FormValidationBuilder debounce(Duration duration) {
        debounceMillis = duration == null ? 0 : Math.max(0, duration.toMillis());
        return this;
    }

    /** Continue configuring another field on the same entity. */
    public FieldHintBuilder field(String name) {
        return parent.field(name);
    }

    FormValidation build() {
        return new FormValidation(key, validator, dependencies, debounceMillis);
    }
}
