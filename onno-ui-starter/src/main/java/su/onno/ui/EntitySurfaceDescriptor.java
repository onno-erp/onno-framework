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

    String safeSort(String sortColumn) {
        return sortColumn != null && sortableColumns.contains(sortColumn) ? sortColumn : defaultSortColumn;
    }

    boolean isDefaultSort(String sortColumn) {
        return sortColumn == null || !sortableColumns.contains(sortColumn);
    }

    boolean isNonNullableSort(String column) {
        if (nonNullableSystemSorts.contains(column)) {
            return true;
        }
        return attributes.stream().anyMatch(a -> a.columnName().equals(column) && a.required());
    }
}
