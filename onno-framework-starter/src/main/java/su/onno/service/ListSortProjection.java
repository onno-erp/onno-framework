package su.onno.service;

import org.jdbi.v3.core.statement.Query;
import su.onno.access.AccessMode;
import su.onno.access.AccessSubject;
import su.onno.metadata.AttributeDescriptor;
import su.onno.metadata.MetadataRegistry;
import su.onno.query.KeysetWindow.Order;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** SQL sort values use visible labels before LIMIT; private projection keys never leave the service. */
final class ListSortProjection {
    private final MetadataRegistry registry;
    private final RecordScopeCompiler scopes;
    private final AccessService access;
    private final AccessSubject subject;
    private final Map<String, Object> bindings = new LinkedHashMap<>();
    private final List<String> projections = new ArrayList<>();
    private final List<Order> order = new ArrayList<>();

    ListSortProjection(MetadataRegistry registry, RecordScopeCompiler scopes, AccessService access,
                       AccessSubject subject, List<AttributeDescriptor> attributes, List<ListSort.Term> terms) {
        this.registry = registry;
        this.scopes = scopes;
        this.access = access;
        this.subject = subject;
        for (var term : terms) {
            String column = term.column();
            AttributeDescriptor attribute = attributes.stream().filter(a -> a.columnName().equals(column)).findFirst().orElse(null);
            String expression = "sort_row." + column;
            if (term.day() && (column.equals("_date") || column.equals("_period")
                    || attribute != null && (attribute.javaType() == java.time.LocalDateTime.class
                    || attribute.javaType() == java.time.LocalDate.class))) {
                expression = "CAST(" + expression + " AS DATE)";
            } else if (attribute != null && attribute.javaType().isEnum()) {
                var enumeration = registry.allEnumerations().stream().filter(e -> e.javaClass().equals(attribute.javaType())).findFirst();
                if (enumeration.isPresent()) {
                    StringBuilder sql = new StringBuilder("CASE ").append(expression);
                    for (var value : enumeration.get().values()) {
                        String bind = "ls_" + order.size() + "_" + bindings.size();
                        bindings.put(bind, value.id());
                        sql.append(" WHEN :").append(bind).append(" THEN ").append(literal(value.label()));
                    }
                    expression = "LOWER(" + sql.append(" ELSE CAST(").append(expression).append(" AS VARCHAR) END") + ")";
                }
            } else if (attribute != null && attribute.isRef() && attribute.refTarget() != null) {
                expression = reference(attribute, expression);
            } else if ((attribute != null && attribute.javaType() == String.class) || column.equals("_description")) {
                expression = "LOWER(" + expression + ")";
            }
            // Native numeric/date/id/code order stays indexable; labels get a separate cursor key.
            if (expression.equals("sort_row." + column)) {
                boolean notNull = List.of("_id", "_code", "_number", "_date", "_posted").contains(column)
                        || attribute != null && attribute.required();
                order.add(new Order(column, term.descending(), notNull));
            } else {
                String alias = "_onno_sort_" + order.size();
                projections.add(expression + " AS " + alias);
                order.add(new Order(alias, term.descending()));
            }
        }
    }

    private String reference(AttributeDescriptor attr, String value) {
        String alias = "sort_ref_" + order.size();
        var catalog = registry.allCatalogs().stream().filter(c -> c.logicalName().equals(attr.refTarget())).findFirst();
        var document = registry.allDocuments().stream().filter(d -> d.logicalName().equals(attr.refTarget())).findFirst();
        if (catalog.isEmpty() && document.isEmpty()) return value;
        String kind = catalog.isPresent() ? "catalog" : "document";
        String table = catalog.isPresent() ? catalog.get().tableName() : document.orElseThrow().tableName();
        Class<?> type = catalog.isPresent() ? catalog.get().javaClass() : document.orElseThrow().javaClass();
        if (!access.canRead(subject, kind, attr.refTarget())) return "CAST(NULL AS VARCHAR)";
        ScopeClause visible = scopes.clause(type, subject, AccessMode.READ, alias);
        bindings.putAll(visible.bindings());
        String label = catalog.isPresent()
                ? "COALESCE(NULLIF(TRIM(" + alias + "._description), ''), " + alias + "._code)"
                : alias + "._number";
        String query = "(SELECT " + label + " FROM " + table + " " + alias + " WHERE " + alias
                + "._id = " + value + visible.and() + ")";
        // A missing/unreadable scoped target has a constant key, just as its masked UI label does.
        return "LOWER(" + (visible.isAll() ? "COALESCE(" + query + ", CAST(" + value + " AS VARCHAR))" : query) + ")";
    }

    String source(String source, String alias) {
        return projections.isEmpty() ? source : "(SELECT sort_row.*, " + String.join(", ", projections)
                + " FROM (SELECT * FROM " + source + ") sort_row) " + alias;
    }

    List<Order> order(List<String> uniqueColumns, boolean descending) {
        List<Order> result = new ArrayList<>(order);
        for (String column : uniqueColumns) {
            if (result.stream().noneMatch(o -> o.column().equals(column))) result.add(new Order(column, descending, column.equals("_id")));
        }
        return result;
    }

    void bind(Query query) { bindings.forEach(query::bind); }

    void clean(List<Map<String, Object>> rows) {
        for (var row : rows) for (int i = 0; i < order.size(); i++) row.remove("_onno_sort_" + i);
    }

    private static String literal(String value) { return "'" + value.replace("'", "''") + "'"; }
}
