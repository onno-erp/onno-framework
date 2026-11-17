package su.onno.validation;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Collects the live checks of one entity, from {@link Checks#define(CheckSpec)}.
 *
 * <pre>
 * v.check("availability", ctx -&gt; availability(ctx))
 *  .dependsOn(Reservation::getRoom, Reservation::getStartsAt);
 * </pre>
 *
 * @param <E> the owning entity
 */
public final class CheckSpec<E> {

    private final Map<String, CheckBuilder<E>> checks = new LinkedHashMap<>();

    /** Declare a check with the given unique key; a repeated key returns the first declaration. */
    public CheckBuilder<E> check(String key, Check check) {
        Objects.requireNonNull(check, "check");
        return checks.computeIfAbsent(key, k -> new CheckBuilder<>(this, k, check));
    }

    public List<CheckDefinition> build() {
        return checks.values().stream().map(CheckBuilder::build).toList();
    }
}
