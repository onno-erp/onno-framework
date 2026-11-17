package su.onno.web;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** A list column reports its field's value type, which a client filter on the field defaults to. */
class ListColumnTypeTest {

    @Test
    void columnsReportTheirFieldsValueType() {
        assertThat(DefaultListViews.valueType(Map.of("isEnum", true, "javaType", "UUID"))).isEqualTo("enum");
        assertThat(DefaultListViews.valueType(Map.of("isRef", true, "javaType", "Ref"))).isEqualTo("ref");
        assertThat(DefaultListViews.valueType(Map.of("javaType", "BigDecimal"))).isEqualTo("number");
        assertThat(DefaultListViews.valueType(Map.of("javaType", "LocalDateTime"))).isEqualTo("date");
        assertThat(DefaultListViews.valueType(Map.of("javaType", "boolean"))).isEqualTo("boolean");
        assertThat(DefaultListViews.valueType(Map.of("javaType", "String"))).isEqualTo("text");
        // System columns carry no Java type.
        assertThat(DefaultListViews.valueType(Map.of("columnName", "_date"))).isEqualTo("date");
        assertThat(DefaultListViews.valueType(Map.of("columnName", "_posted"))).isEqualTo("boolean");
        assertThat(DefaultListViews.valueType(Map.of("columnName", "_number"))).isEqualTo("text");
    }

    @Test
    void theListDescriptorCarriesTheType() {
        DefaultListView view = new DefaultListView("Orders",
                List.of(new DefaultListView.Column("Total", "total", "total", "", "", "", "", "number")),
                List.of(), 0);
        Map<String, Object> descriptor = ViewDescriptors.listDescriptor(view, "documents", "orders", null, true, List.of());
        assertThat(descriptor.get("columns")).asList().singleElement()
                .satisfies(c -> assertThat(((Map<?, ?>) c).get("type")).isEqualTo("number"));
    }
}
