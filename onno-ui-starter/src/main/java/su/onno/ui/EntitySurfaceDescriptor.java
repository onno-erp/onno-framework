package su.onno.ui;

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
        Set<String> nonNullableSystemSorts
) {

    static EntitySurfaceDescriptor catalog(CatalogDescriptor desc) {
        Set<String> sortable = new LinkedHashSet<>(Set.of("_code", "_description"));
        desc.attributes().forEach(a -> sortable.add(a.columnName()));
        return new EntitySurfaceDescriptor(
                "catalog",
                desc.logicalName(),
                desc.tableName(),
                desc.attributes(),
                Set.copyOf(sortable),
                WidgetBuckets.CATALOG_SYSTEM_COLUMNS,
                List.of("_code", "_description"),
                "_code",
                false,
                Set.of("_code")
        );
    }

    static EntitySurfaceDescriptor document(DocumentDescriptor desc) {
        Set<String> sortable = new LinkedHashSet<>(Set.of("_number", "_date", "_posted"));
        desc.attributes().forEach(a -> sortable.add(a.columnName()));
        return new EntitySurfaceDescriptor(
                "document",
                desc.logicalName(),
                desc.tableName(),
                desc.attributes(),
                Set.copyOf(sortable),
                WidgetBuckets.DOCUMENT_SYSTEM_COLUMNS,
                List.of("_number"),
                "_date",
                true,
                Set.of("_date", "_number", "_posted")
        );
    }

    Set<String> columnNames() {
        return attributes.stream()
                .map(a -> a.columnName().toLowerCase())
                .collect(Collectors.toSet());
    }

    Set<String> filterableColumns() {
        return columnNames();
    }

    /** Columns whose SQL type is UUID (refs and enums), so a filter binds them typed (PG-strict). */
    Set<String> uuidColumns() {
        return attributes.stream()
                .filter(a -> a.isRef() || a.javaType().isEnum())
                .map(a -> a.columnName().toLowerCase())
                .collect(Collectors.toSet());
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
                case "folder", "_is_folder" -> "_is_folder";
                case "parent", "_parent" -> "_parent";
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
