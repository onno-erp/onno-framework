package com.onec.query;

import java.util.Locale;

/**
 * One item in the SELECT list: a {@link Path} (plain column or ref-navigation),
 * optionally wrapped in an aggregate, with an output alias used as the result-{@code Row}
 * key and the {@code fetchInto} property name.
 *
 * <p>{@code path} may be {@code null} only for {@code COUNT(*)}.
 */
public record Select(Path path, Agg agg, String alias) {

    public enum Agg { NONE, COUNT, SUM, AVG, MIN, MAX }

    public Select {
        if (agg == null) agg = Agg.NONE;
        if (path == null && agg != Agg.COUNT) {
            throw new IllegalArgumentException("Only COUNT may omit a path");
        }
    }

    /** Column name in the result set: explicit alias, else derived from the path/aggregate. */
    public String outputName() {
        if (alias != null && !alias.isBlank()) {
            return alias;
        }
        String base = path == null ? "all" : path.tail();
        return agg == Agg.NONE ? base : agg.name().toLowerCase(Locale.ROOT) + "_" + base;
    }

    Select withRoot(Class<?> root) {
        return path == null ? this : new Select(path.withRoot(root), agg, alias);
    }
}
