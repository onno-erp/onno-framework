package su.onno.web;

import java.util.List;

/**
 * An entity's default list, resolved from the model alone by {@link DefaultListViews}: its title, the
 * columns visible in a list in model order, the filters every list gets (enumerations, a document's
 * date) and the keyset window size. Everything else about how a list presents — which columns,
 * labels, sort, grouping, alternate views — is the client's to customize.
 */
public record DefaultListView(String title, List<Column> columns, List<Filter> filters, int pageSize) {

    public DefaultListView {
        columns = columns == null ? List.of() : List.copyOf(columns);
        filters = filters == null ? List.of() : List.copyOf(filters);
        pageSize = pageSize <= 0 ? 50 : pageSize;
    }

    /**
     * A filter: a stable {@code key} (the field name, the client's state key), the {@code label},
     * the data {@code columnName} the query filters on, the control {@code type}
     * ({@code "multiOptions"}, {@code "dateRange"}, …) and, for the option controls, its choices.
     */
    public record Filter(String key, String label, String columnName, String type, List<Option> options) {
        public Filter {
            options = options == null ? List.of() : List.copyOf(options);
        }
    }

    /**
     * One choice of an options filter: the {@code value} the query matches, the {@code label} shown,
     * an optional {@code color} (an {@code @EnumLabel} hex, blank when none) and {@code avatarUrl}.
     */
    public record Option(String value, String label, String color, String avatarUrl) {
        public Option {
            label = label == null ? value : label;
            color = color == null ? "" : color;
            avatarUrl = avatarUrl == null ? "" : avatarUrl;
        }
    }

    /**
     * A column: the header label, the data column it reads, the logical response key, an optional
     * width in whole pixels (blank = size to content; form-layout hints such as {@code "half"}
     * never appear here) and the attribute's display hints ({@code widget}, {@code format},
     * {@code hint}), each blank when unset, and the field's value {@code type} — {@code text},
     * {@code number}, {@code boolean}, {@code date}, {@code enum} or {@code ref} (blank when unknown) —
     * what a client filter on the field defaults to.
     */
    public record Column(String label, String columnName, String fieldName, String width, String widget,
                         String format, String hint, String type) {}
}
