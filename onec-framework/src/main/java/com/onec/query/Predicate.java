package com.onec.query;

import java.util.List;

/**
 * A WHERE condition on a {@link Path}. {@code value}/{@code values} carry the operands;
 * which fields apply depends on {@link Op}:
 * <ul>
 *   <li>{@code EQ NE GT GTE LT LTE LIKE} use {@code value}</li>
 *   <li>{@code BETWEEN} uses {@code value} (low) and {@code value2} (high)</li>
 *   <li>{@code IN} uses {@code values}</li>
 *   <li>{@code IS_NULL IS_NOT_NULL} use no operand</li>
 * </ul>
 * {@code Ref} operands are unwrapped to their UUID at bind time by the engine.
 */
public record Predicate(Path path, Op op, Object value, Object value2, List<?> values) {

    public enum Op { EQ, NE, GT, GTE, LT, LTE, BETWEEN, IN, LIKE, IS_NULL, IS_NOT_NULL }

    public Predicate {
        if (path == null) throw new IllegalArgumentException("Predicate needs a path");
        if (op == null) throw new IllegalArgumentException("Predicate needs an operator");
        values = values == null ? null : List.copyOf(values);
    }

    Predicate withRoot(Class<?> root) {
        return new Predicate(path.withRoot(root), op, value, value2, values);
    }
}
