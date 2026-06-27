package su.onno.ui;

import su.onno.annotations.Attribute;
import su.onno.annotations.Document;
import su.onno.annotations.TabularSection;
import su.onno.metadata.DefaultNamingStrategy;
import su.onno.metadata.MetadataRegistry;
import su.onno.metadata.MetadataScanner;
import su.onno.model.DocumentObject;
import su.onno.model.TabularSectionRow;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A tabular-section column hint, addressed on the document's own {@link EntityView} with a
 * section-scoped key ({@code field("lines.amount").format(...)}), flows into the resolved
 * tabular-section attribute metadata — and a same-named top-level field hint does NOT leak onto the
 * line column (the prefix scopes it). With no such hint, the line attribute keeps the prior blank
 * default.
 */
class TabularSectionFieldHintMetadataTest {

    @Document(name = "HintOrders")
    static class Order extends DocumentObject {
        @Attribute(displayName = "Total", precision = 14, scale = 2)
        private BigDecimal amount;
        @TabularSection(name = "lines")
        private List<Line> lines = new ArrayList<>();
    }

    static class Line extends TabularSectionRow {
        @Attribute(displayName = "Amount", precision = 14, scale = 2)
        private BigDecimal amount;
        @Attribute(displayName = "Qty", precision = 12, scale = 0)
        private BigDecimal quantity;
    }

    /** Targets the line's amount via the section-scoped key, and the document's own amount separately. */
    static class OrderView implements EntityView {
        @Override
        public Class<?> entity() {
            return Order.class;
        }

        @Override
        public void fields(EntityConfigBuilder f) {
            f.field("amount").format("currency:EUR")            // the document's own Total
                    .field("lines.amount").format("currency:USD"); // the line's Amount
        }
    }

    private Map<String, Object> describe(EntityView... views) {
        MetadataRegistry registry = new MetadataRegistry();
        MetadataScanner scanner = new MetadataScanner(new DefaultNamingStrategy());
        registry.registerDocument(scanner.scanDocument(Order.class));
        ResolvedMetadataService svc =
                new ResolvedMetadataService(registry, new FieldHintResolver(List.of(views)));
        return svc.describeDocument(scanner.scanDocument(Order.class));
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> lineAttr(Map<String, Object> doc, String fieldName) {
        List<Map<String, Object>> sections = (List<Map<String, Object>>) doc.get("tabularSections");
        List<Map<String, Object>> attrs = (List<Map<String, Object>>) sections.get(0).get("attributes");
        return attrs.stream().filter(a -> fieldName.equals(a.get("fieldName"))).findFirst().orElseThrow();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> topAttr(Map<String, Object> doc, String fieldName) {
        List<Map<String, Object>> attrs = (List<Map<String, Object>>) doc.get("attributes");
        return attrs.stream().filter(a -> fieldName.equals(a.get("fieldName"))).findFirst().orElseThrow();
    }

    @Test
    void sectionScopedHint_formatsTheLineColumn() {
        Map<String, Object> doc = describe(new OrderView());
        // The "lines.amount" hint reaches the line's amount column…
        assertThat(lineAttr(doc, "amount").get("format")).isEqualTo("currency:USD");
        // …while the top-level "amount" hint stays on the document's own field (no cross-leak).
        assertThat(topAttr(doc, "amount").get("format")).isEqualTo("currency:EUR");
        // A line column not targeted by any hint keeps the blank default.
        assertThat(lineAttr(doc, "quantity").get("format")).isEqualTo("");
    }

    @Test
    void noView_lineColumnFormatDefaultsToBlank() {
        Map<String, Object> doc = describe();
        assertThat(lineAttr(doc, "amount").get("format")).isEqualTo("");
    }
}
