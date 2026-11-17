package su.onno.validation;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The live checks each entity declares through {@link Checks} beans, in declaration order. Several
 * beans for one entity are merged.
 */
public class CheckRegistry {

    private final Map<Class<?>, List<CheckDefinition>> byEntity = new LinkedHashMap<>();

    @SuppressWarnings({"rawtypes", "unchecked"})
    public CheckRegistry(List<Checks> checks) {
        for (Checks<?> c : checks) {
            collect(c);
        }
    }

    private <E> void collect(Checks<E> c) {
        if (c.entity() == null) {
            return;
        }
        CheckSpec<E> spec = new CheckSpec<>();
        c.define(spec);
        byEntity.computeIfAbsent(c.entity(), k -> new ArrayList<>()).addAll(spec.build());
    }

    /** The entity's live checks, in declaration order; empty when it declares none. */
    public List<CheckDefinition> checksFor(Class<?> entity) {
        return byEntity.getOrDefault(entity, List.of());
    }

    /** A check of the entity by key, or {@code null} when it declares none with that key. */
    public CheckDefinition check(Class<?> entity, String key) {
        return checksFor(entity).stream()
                .filter(check -> check.key().equals(key))
                .findFirst()
                .orElse(null);
    }
}
