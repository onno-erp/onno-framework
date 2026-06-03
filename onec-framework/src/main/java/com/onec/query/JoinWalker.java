package com.onec.query;

import com.onec.metadata.AttributeDescriptor;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The one genuinely new engine piece: turns reference-navigation {@link Path}s into
 * {@code LEFT JOIN}s and resolves each path to a qualified column expression.
 *
 * <p>For every {@code Ref} hop {@code a.b} it emits
 * {@code LEFT JOIN <target_table> <alias> ON <src_alias>.<fk_col> = <alias>._id},
 * aliasing as it descends. Joins are de-duplicated by their segment prefix, so two paths
 * that share a prefix ({@code customer.name} and {@code customer.region.name}) reuse the
 * same join rather than emitting it twice. Pure descriptor lookup plus alias bookkeeping
 * &mdash; no SQL parsing.
 */
final class JoinWalker {

    private final EntityResolver resolver;
    private final EntityMeta root;
    private final String rootAlias;
    private final List<String> joins = new ArrayList<>();
    private final Map<String, String> aliasByPrefix = new LinkedHashMap<>();
    private int aliasCounter = 0;

    JoinWalker(EntityResolver resolver, EntityMeta root) {
        this.resolver = resolver;
        this.root = root;
        this.rootAlias = "t0";
    }

    String rootAlias() {
        return rootAlias;
    }

    /** JOIN clauses accumulated so far, in emission order. */
    List<String> joins() {
        return joins;
    }

    /**
     * Resolve a path to a qualified column expression (e.g. {@code t0.amount} or
     * {@code t1._description}), emitting any joins required to reach it.
     */
    String column(Path path) {
        EntityMeta meta = root;
        String alias = rootAlias;
        StringBuilder prefix = new StringBuilder();

        List<String> segments = path.segments();
        for (int i = 0; i < segments.size() - 1; i++) {
            String hop = segments.get(i);
            AttributeDescriptor attr = meta.attribute(hop);
            if (attr == null || !attr.isRef()) {
                throw new IllegalArgumentException(
                        "Cannot navigate through '" + hop + "' on " + meta.type().getSimpleName()
                                + ": it is not a Ref attribute");
            }
            EntityMeta target = resolver.forRefTarget(attr.refTarget());
            if (target == null) {
                throw new IllegalArgumentException(
                        "Ref attribute '" + hop + "' targets unknown entity '" + attr.refTarget() + "'");
            }

            prefix.append(hop).append('.');
            String key = prefix.toString();
            String childAlias = aliasByPrefix.get(key);
            if (childAlias == null) {
                childAlias = "t" + (++aliasCounter);
                aliasByPrefix.put(key, childAlias);
                joins.add("LEFT JOIN " + target.table() + " " + childAlias
                        + " ON " + alias + "." + attr.columnName()
                        + " = " + childAlias + "." + target.pk());
            }
            alias = childAlias;
            meta = target;
        }

        String tail = path.tail();
        String column = meta.column(tail);
        if (column == null) {
            throw new IllegalArgumentException(
                    "Unknown field '" + tail + "' on " + meta.type().getSimpleName());
        }
        return alias + "." + column;
    }
}
