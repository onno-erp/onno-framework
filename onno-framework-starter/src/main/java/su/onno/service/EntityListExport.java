package su.onno.service;

import org.jdbi.v3.core.Jdbi;
import su.onno.access.AccessSubject;
import su.onno.model.Periodicity;
import su.onno.metadata.AttributeDescriptor;
import su.onno.query.KeysetWindow;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/** Prepares the authorized data behind an export without reimplementing list queries. */
public final class EntityListExport {
    private static final String GROUP_KEY = "_onno_export_group";

    private EntityListExport() {}

    public record Query(String sort, boolean descending, String search, String from, String to,
                 List<String> eq, List<String> in, List<String> like, List<String> prefix,
                 List<String> ge, List<String> le, String where) {}

    public record Column(String key, String label) {}

    public record Data(List<String> headings, Iterable<List<?>> rows) {}

    public static Data catalog(CatalogQueryService query, AccessService access, AccessSubject.User user,
                        String name, Query terms, List<Column> columns, int rowCap) {
        return catalog(query, access, user, name, terms, columns, rowCap, null);
    }

    public static Data catalog(CatalogQueryService query, AccessService access, AccessSubject.User user,
                        String name, Query terms, List<Column> columns, int rowCap, Grouping grouping) {
        var catalog = query.require(name);
        access.requireRead(user, catalog);
        Group group = Group.resolve(EntitySurfaceDescriptor.catalog(catalog), grouping);
        return prepare(catalog.attributes(), Set.of("_code", "_description"), columns, rowCap, group,
                (cursor, limit) -> query.keysetPage(user, catalog, cursor, null, null, limit,
                        terms.sort(), terms.descending(), terms.search(),
                        terms.eq(), terms.in(), terms.like(), terms.prefix(), terms.ge(), terms.le(),
                        terms.where(), group));
    }

    public static Data document(DocumentQueryService query, AccessService access, AccessSubject.User user,
                         String name, Query terms, List<Column> columns, int rowCap, Grouping grouping) {
        var document = query.require(name);
        access.requireRead(user, document);
        Group group = Group.resolve(EntitySurfaceDescriptor.document(document), grouping);
        return prepare(document.attributes(), Set.of("_number", "_date", "_posted"), columns, rowCap, group,
                (cursor, limit) -> query.keysetPage(user, document, cursor, null, null, limit,
                        terms.sort(), terms.descending(), terms.search(), terms.from(), terms.to(),
                        terms.eq(), terms.in(), terms.like(), terms.prefix(), terms.ge(), terms.le(),
                        terms.where(), group));
    }

    public static Data register(RegisterQueryService query, AccessService access, AccessSubject.User user,
                         Map<String, String> movementLabels, String name, Query terms, List<Column> columns, int rowCap,
                         boolean movements, String asOf) {
        var register = query.require(name);
        access.requireRead(user, register);
        var attributes = Stream.concat(register.dimensions().stream(), register.resources().stream()).toList();
        var systems = movements ? Set.of("_period", "_movement_type") : Set.<String>of();
        var allowed = new HashSet<>(systems);
        attributes.stream().filter(field -> !field.secret()).map(AttributeDescriptor::columnName)
                .forEach(allowed::add);
        if (terms.sort() != null && !terms.sort().isBlank() && !allowed.contains(terms.sort())) {
            throw new ServiceException(ServiceException.Status.BAD_REQUEST, "Invalid export sort column");
        }
        var filters = ListFilter.parse(terms.eq(), terms.in(), terms.like(), terms.prefix(),
                terms.ge(), terms.le(), allowed, query.numericFilterColumns(register));
        return prepare(attributes, systems, columns, rowCap, null, (cursor, limit) -> {
            var page = movements
                    ? query.movementsPage(user, register, terms.from(), terms.to(), filters, terms.where(), terms.sort(),
                            terms.descending(), cursor, limit)
                    : query.balancePage(user, register, filters, terms.where(), terms.sort(), terms.descending(), cursor, limit, asOf);
            if (movements) {
                for (var row : page.rows()) {
                    Object type = row.get("_movement_type");
                    row.put("_movement_type_display", movementLabels.get(Objects.toString(type, "")));
                }
            }
            return new KeysetPage(page.rows(), page.nextCursor(), page.hasMore());
        });
    }

    public static Data informationRegister(InformationRegisterQueryService query, AccessService access,
                                    AccessSubject.User user, String name, List<Column> columns,
                                    int rowCap, LocalDateTime from, LocalDateTime to, String where) {
        var register = query.require(name);
        access.requireRead(user, register);
        var attributes = Stream.of(register.dimensions(), register.resources(), register.attributes())
                .flatMap(List::stream).toList();
        var systems = register.periodicity() == Periodicity.NONE ? Set.<String>of() : Set.of("_period");
        return prepare(attributes, systems, columns, rowCap, null,
                (cursor, limit) -> query.records(user, register, from, to, where, cursor, limit));
    }

    private static Data prepare(List<AttributeDescriptor> attributes, Set<String> systemColumns,
                                List<Column> columns, int rowCap, Group group, PageReader source) {
        Map<String, AttributeDescriptor> fields = attributes.stream()
                .filter(field -> !field.secret())
                .collect(Collectors.toMap(AttributeDescriptor::columnName, field -> field));
        if (columns.isEmpty() || columns.stream().anyMatch(
                column -> !fields.containsKey(column.key()) && !systemColumns.contains(column.key()))) {
            throw new ServiceException(ServiceException.Status.BAD_REQUEST, "Invalid export columns");
        }
        var chosen = List.copyOf(columns);
        Function<Map<String, Object>, List<?>> project = row -> {
            List<Object> values = new ArrayList<>(chosen.size());
            for (Column column : chosen) {
                values.add(cell(row, column.key(), fields.get(column.key())));
            }
            return values;
        };
        Iterable<Map<String, Object>> records = rows(source, rowCap);
        Iterable<List<?>> output = () -> project(records.iterator(), project, group, chosen.size());
        return new Data(chosen.stream().map(Column::label).toList(), output);
    }

    private static Object cell(Map<String, Object> row, String column, AttributeDescriptor field) {
        Object value = row.get(column);
        if ("_movement_type".equals(column) || (field != null && (field.isRef() || field.javaType().isEnum()))) {
            return row.get(column + "_display");
        }
        if (value == null) return null;
        boolean systemDate = column.equals("_date") || column.equals("_period");
        Class<?> type = field != null ? field.javaType() : systemDate ? LocalDateTime.class : null;
        if (type == LocalDate.class) return TemporalValues.toLocalDate(value);
        if (type == LocalDateTime.class) return TemporalValues.toLocalDateTime(value);
        if (type == Instant.class) return TemporalValues.toInstant(value);
        return value;
    }

    public record Grouping(String column, String granularity) {}

    record Group(String column, boolean date, String granularity) {
        static Group resolve(EntitySurfaceDescriptor surface, Grouping grouping) {
            if (grouping == null) return null;
            String column = grouping.column();
            if (column == null || !surface.sortableColumns().contains(column)) {
                throw new ServiceException(ServiceException.Status.BAD_REQUEST, "Invalid export group column");
            }
            boolean date = column.equals("_date") || surface.attributes().stream().anyMatch(
                    field -> field.columnName().equals(column) && ListGroups.isTemporalType(field.javaType()));
            return new Group(column, date, ListGroups.safeGranularity(grouping.granularity()));
        }

        KeysetPage page(Jdbi jdbi, String table, String where, Consumer<org.jdbi.v3.core.statement.Query> bind,
                        String sort, boolean desc, boolean nonNullable, String cursor, int limit, String fingerprint) {
            String source = "(SELECT *, " + ListGroups.groupExpression(column, date, granularity) + " AS "
                    + GROUP_KEY + " FROM " + table + " WHERE " + where + ") export_rows";
            var order = new ArrayList<KeysetWindow.Order>();
            order.add(new KeysetWindow.Order(GROUP_KEY, false));
            if (!column.equals(sort) || date) order.add(new KeysetWindow.Order(sort, desc, nonNullable));
            order.add(KeysetWindow.Order.notNull("_id", desc));
            var page = su.onno.service.KeysetWindow.collection(jdbi, source, "*", "1=1", bind, order, cursor,
                    limit, su.onno.query.Cursor.context(fingerprint, "export", this));
            return new KeysetPage(page.rows(), page.nextCursor(), page.hasMore());
        }

        String label(Map<String, Object> row) {
            Object key = row.get(GROUP_KEY);
            if (key == null) return "—";
            if (date) return ListGroups.bucketLabel(key, granularity);
            return Objects.toString(row.getOrDefault(column + "_display", row.get(column)), "—");
        }
    }

    /** Insert a header once per group, including when a group crosses a window boundary. */
    private static Iterator<List<?>> project(Iterator<Map<String, Object>> records,
                                           Function<Map<String, Object>, List<?>> project, Group group, int width) {
        return new Iterator<>() {
            private Map<String, Object> pending;
            private Object previousKey;
            private boolean first = true;

            public boolean hasNext() {
                return pending != null || records.hasNext();
            }

            public List<?> next() {
                if (pending != null) {
                    var row = pending;
                    pending = null;
                    return project.apply(row);
                }
                var row = records.next();
                if (group == null) return project.apply(row);
                Object key = row.get(GROUP_KEY);
                if (!first && Objects.equals(previousKey, key)) return project.apply(row);
                first = false;
                previousKey = key;
                pending = row;
                var header = new ArrayList<>(Collections.nCopies(width, (Object) null));
                header.set(0, group.label(row));
                return header;
            }
        };
    }

    @FunctionalInterface
    private interface PageReader {
        KeysetPage read(String cursor, int limit);
    }

    /**
     * The writer consumes one bounded window at a time. The caller should finish writing before
     * committing the HTTP response, so an over-limit export can fail without sending a partial file.
     */
    private static Iterable<Map<String, Object>> rows(PageReader source, int rowCap) {
        if (rowCap < 1) throw new IllegalArgumentException("The export row cap must be positive");
        return () -> new Iterator<>() {
            private final Set<String> seen = new HashSet<>();
            private Iterator<Map<String, Object>> window = List.<Map<String, Object>>of().iterator();
            private String cursor;
            private boolean more = true;
            private int remaining = rowCap;

            @Override
            public boolean hasNext() {
                if (window.hasNext()) return true;
                if (!more) return false;
                int limit = Math.min(remaining, KeysetWindow.MAX_LIMIT);
                KeysetPage next = source.read(cursor, limit);
                if (next.rows().size() > remaining || (next.hasMore() && next.rows().size() == remaining)) {
                    throw new ServiceException(ServiceException.Status.PAYLOAD_TOO_LARGE, "Export row limit exceeded");
                }
                more = next.hasMore();
                cursor = next.nextCursor();
                if (more && (next.rows().isEmpty() || cursor == null || !seen.add(cursor))) {
                    throw new IllegalStateException("Export list pagination did not advance");
                }
                remaining -= next.rows().size();
                window = next.rows().iterator();
                return window.hasNext();
            }

            @Override
            public Map<String, Object> next() {
                if (!hasNext()) throw new NoSuchElementException();
                return window.next();
            }
        };
    }
}
