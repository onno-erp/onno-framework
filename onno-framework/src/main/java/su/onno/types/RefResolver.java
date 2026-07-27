package su.onno.types;

import java.util.Optional;

public interface RefResolver {

    <T> Optional<T> resolve(Ref<T> ref);

    default Optional<?> resolve(PolyRef ref) {
        throw new UnsupportedOperationException(
                "This RefResolver does not support polymorphic references");
    }

    default <T> T resolveOrThrow(Ref<T> ref) {
        return resolve(ref).orElseThrow(() ->
                new IllegalArgumentException("Could not resolve " + ref));
    }

    default Object resolveOrThrow(PolyRef ref) {
        return resolve(ref).orElseThrow(() ->
                new IllegalArgumentException("Could not resolve " + ref));
    }
}
