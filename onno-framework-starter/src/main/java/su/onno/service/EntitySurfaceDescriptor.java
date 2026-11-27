package su.onno.service;

import su.onno.metadata.AttributeDescriptor;
import su.onno.metadata.CatalogDescriptor;
import su.onno.metadata.DocumentDescriptor;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

record EntitySurfaceDescriptor(
        String kind,
        String logicalName,
        String tableName,
        List<AttributeDescriptor> attributes,
        Set<String> sortableColumns,
        Set<String> widgetSystemColumns,
        List<String> searchSystemColumns,
        String defaultSortColumn,
        boolean defaultDescending,
        Set<String> nonNullableSystemSorts,
        Class<?> javaClass,
        boolean hierarchical
) {

    static EntitySurfaceDescriptor catalog(CatalogDescriptor desc) {
        Set<String> sortable = new LinkedHashSet<>(Set.of("_code", "_description"));
        desc.attributes().stream()
                .filter(a -> !a.secret())
                .forEach(a -> sortable.add(a.columnName()));
        return new EntitySurfaceDescriptor(
                "catalog",
                desc.logicalName(),
                desc.tableName(),
                desc.attributes(),
                Set.copyOf(sortable),
                desc.hierarchical() ? AggregateBuckets.CATALOG_SYSTEM_COLUMNS : Set.of("_code", "_description"),
                List.of("_code", "_description"),
                "_code",
                false,
                Set.of("_code"),
                desc.javaClass(),
                desc.hierarchical()
        );
    }

    static EntitySurfaceDescriptor document(DocumentDescriptor desc) {
        Set<String> sortable = new LinkedHashSet<>(Set.of("_number", "_date", "_posted"));
        desc.attributes().stream()
                .filter(a -> !a.secret())
                .forEach(a -> sortable.add(a.columnName()));
        return new EntitySurfaceDescriptor(
                "document",
                desc.logicalName(),
                desc.tableName(),
                desc.attributes(),
                Set.copyOf(sortable),
                AggregateBuckets.DOCUMENT_SYSTEM_COLUMNS,
                List.of("_number"),
                "_date",
                true,
                Set.of("_date", "_number", "_posted"),
                desc.javaClass(),
                false
        );
    }

    Set<String> columnNames() {
        return attributes.stream()
                .filter(a -> !a.secret())
                .map(a -> a.columnName().toLowerCase())
                .collect(Collectors.toSet());
    }

    Set<String> filterableColumns() {
        return columnNames();
    }

    /** Columns whose SQL type is numeric, so a range filter bound binds as a number (PG-strict). */
    Set<String> numericColumns() {
        return attributes.stream()
                .filter(a -> !a.secret())
                .filter(a -> ListFilter.isNumeric(a.javaType()))
                .map(a -> a.columnName().toLowerCase())
                .collect(Collectors.toSet());
    }

    /** Columns whose SQL type is UUID (refs and enums), so a filter binds them typed (PG-strict). */
    Set<String> uuidColumns() {
        return attributes.stream()
                .filter(a -> !a.secret())
                .filter(a -> a.isRef() || a.javaType().isEnum())
                .map(a -> a.columnName().toLowerCase())
                .collect(Collectors.toSet());
    }

    /**
     * The columns a structured {@link Where} predicate may reference: every non-secret attribute
     * plus the kind's system columns, resolved from logical field names via {@link #storageColumn}.
     */
    Where.Schema whereSchema() {
        java.util.Map<String, Where.Type> system = switch (kind) {
            case "catalog" -> java.util.Map.of(
                    "_id", Where.Type.UUID, "_code", Where.Type.TEXT, "_description", Where.Type.TEXT,
                    "_deletion_mark", Where.Type.BOOLEAN, "_is_folder", Where.Type.BOOLEAN);
            case "document" -> java.util.Map.of(
                    "_id", Where.Type.UUID, "_number", Where.Type.TEXT, "_date", Where.Type.DATETIME,
                    "_posted", Where.Type.BOOLEAN, "_deletion_mark", Where.Type.BOOLEAN);
            default -> java.util.Map.of();
        };
        if (kind.equals("catalog") && !hierarchical) {
            system = new java.util.LinkedHashMap<>(system);
            system.remove("_is_folder");
        }
        return Where.Schema.of(attributes, system, this::storageColumn);
    }

    String safeSort(String sortColumn) {
        String storage = storageColumn(sortColumn);
        return storage != null && sortableColumns.contains(storage) ? storage : defaultSortColumn;
    }

    boolean isDefaultSort(String sortColumn) {
        String storage = storageColumn(sortColumn);
        return storage == null || !sortableColumns.contains(storage);
    }

    boolean isNonNullableSort(String column) {
        if (nonNullableSystemSorts.contains(column)) {
            return true;
        }
        return attributes.stream().anyMatch(a -> a.columnName().equals(column) && a.required());
    }

    /** Resolve a preferred logical API field or a legacy storage column to the SQL column. */
    String storageColumn(String field) {
        if (field == null || field.isBlank()) {
            return field;
        }
        String system = switch (kind) {
            case "catalog" -> switch (field) {
                case "id", "_id" -> "_id";
                case "code", "_code" -> "_code";
                case "description", "_description" -> "_description";
                case "deletionMark", "_deletion_mark" -> "_deletion_mark";
                case "folder", "_is_folder" -> hierarchical ? "_is_folder" : null;
                case "parent", "_parent" -> hierarchical ? "_parent" : null;
                case "version", "_version" -> "_version";
                default -> null;
            };
            case "document" -> switch (field) {
                case "id", "_id" -> "_id";
                case "number", "_number" -> "_number";
                case "date", "_date" -> "_date";
                case "posted", "_posted" -> "_posted";
                case "deletionMark", "_deletion_mark" -> "_deletion_mark";
                case "version", "_version" -> "_version";
                default -> null;
            };
            default -> null;
        };
        if (system != null) {
            return system;
        }
        return attributes.stream()
                .filter(a -> a.fieldName().equals(field) || a.columnName().equals(field))
                .findFirst()
                .map(AttributeDescriptor::columnName)
                .orElse(field);
    }
}
