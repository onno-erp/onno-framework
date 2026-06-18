package su.onno.types;

import java.util.Optional;

public interface RefResolver {

    <T> Optional<T> resolve(Ref<T> ref);

    default <T> T resolveOrThrow(Ref<T> ref) {
        return resolve(ref).orElseThrow(() ->
                new IllegalArgumentException("Could not resolve " + ref));
    }
}
