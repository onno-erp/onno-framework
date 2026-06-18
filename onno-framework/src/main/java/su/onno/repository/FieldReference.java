package su.onno.repository;

import java.io.Serializable;
import java.util.function.Function;

@FunctionalInterface
public interface FieldReference<T, R> extends Function<T, R>, Serializable {
}
