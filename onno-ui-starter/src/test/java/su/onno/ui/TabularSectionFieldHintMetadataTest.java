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
    @SuppressWarnings({"unused", "FieldMayBeFinal"}) // Reflective metadata fixture.
    static class Order extends DocumentObject {
        @Attribute(displayName = "Total", precision = 14)
        private BigDecimal amount;
        @TabularSection(name = "lines")
        private List<Line> lines = new ArrayList<>();
    }

    @SuppressWarnings({"unused", "FieldMayBeFinal"}) // Reflective metadata fixture.
    static class Line extends TabularSectionRow {
        @Attribute(displayName = "Amount", precision = 14)
        private BigDecimal amount;
        public BigDecimal getAmount() { return amount; }
        @Attribute(displayName = "Qty", precision = 12, scale = 0)
        private BigDecimal quantity;
    }

    /** Targets the line's amount via the section-scoped key, and the document's own amount separately. */
    static class OrderView implements EntityView<Order> {
        @Override
        public Class<Order> entity() {
            return Order.class;
        }

        @Override
        public void fields(EntityConfigBuilder<Order> f) {
            f.field("amount").format("currency:EUR")            // the document's own Total
                    .field("lines.amount").format("currency:USD"); // the line's Amount
        }
    }

    private Map<String, Object> describe(EntityView<?>... views) {
        MetadataRegistry registry = new MetadataRegistry();
        MetadataScanner scanner = new MetadataScanner(new DefaultNamingStrategy());
        registry.registerDocument(scanner.scanDocument(Order.class));
        ResolvedMetadataService svc =
                new ResolvedMetadataService(registry, new FieldHintResolver(new ArrayList<>(List.of(views))));
        return svc.describeDocument(scanner.scanDocument(Order.class));
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> lineAttr(Map<String, Object> doc, String fieldName) {
        List<Map<String, Object>> sections = (List<Map<String, Object>>) doc.get("tabularSections");
        List<Map<String, Object>> attrs = (List<Map<String, Object>>) sections.getFirst().get("attributes");
        return attrs.stream().filter(a -> fieldName.equals(a.get("fieldName"))).findFirst().orElseThrow();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> topAmount(Map<String, Object> doc) {
        List<Map<String, Object>> attrs = (List<Map<String, Object>>) doc.get("attributes");
        return attrs.stream().filter(a -> "amount".equals(a.get("fieldName"))).findFirst().orElseThrow();
    }

    @su.onno.annotations.Catalog(name = "HintProfiles")
    @SuppressWarnings("FieldMayBeFinal") // Framework replaces the section list through reflection.
    static class Profile extends su.onno.model.CatalogObject {
        @TabularSection(name = "lines") private List<Line> filters = new ArrayList<>();
        public List<Line> getFilters() { return filters; }
    }

    @Test
    void catalogRowFieldHints_reachSectionMetadata() {
        var scanner = new MetadataScanner(new DefaultNamingStrategy());
        var registry = new MetadataRegistry();
        var catalog = scanner.scan(Profile.class);
        registry.registerCatalog(catalog);
        EntityView<Profile> view = new EntityView<>() {
            public Class<Profile> entity() { return Profile.class; }
            public void fields(EntityConfigBuilder<Profile> f) {
                f.field(Profile::getFilters).label("Filters");
                f.rowField(Profile::getFilters, Line::getAmount).label("Limit").format("currency:USD");
            }
        };
        var metadata = new ResolvedMetadataService(registry, new FieldHintResolver(List.of(view)))
                .describeCatalog(catalog);
        assertThat(lineAttr(metadata, "amount")).containsEntry("displayName", "Limit")
                .containsEntry("format", "currency:USD");
    }

    @Test
    void sectionScopedHint_formatsTheLineColumn() {
        Map<String, Object> doc = describe(new OrderView());
        // The "lines.amount" hint reaches the line's amount column…
        assertThat(lineAttr(doc, "amount").get("format")).isEqualTo("currency:USD");
        // …while the top-level "amount" hint stays on the document's own field (no cross-leak).
        assertThat(topAmount(doc).get("format")).isEqualTo("currency:EUR");
        // A line column not targeted by any hint keeps the blank default.
        assertThat(lineAttr(doc, "quantity").get("format")).isEqualTo("");
    }

    @Test
    void noView_lineColumnFormatDefaultsToBlank() {
        Map<String, Object> doc = describe();
        assertThat(lineAttr(doc, "amount").get("format")).isEqualTo("");
    }
}
