package su.onno.ui;

import su.onno.fixtures.TestProduct;
import su.onno.metadata.DefaultNamingStrategy;
import su.onno.metadata.MetadataRegistry;
import su.onno.metadata.MetadataScanner;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class UiLayoutResolverTest {

    private MetadataRegistry registry;
    private UiLayoutResolver resolver;

    @BeforeEach
    void setUp() {
        registry = new MetadataRegistry();
        MetadataScanner scanner = new MetadataScanner(new DefaultNamingStrategy());
        registry.registerCatalog(scanner.scan(TestProduct.class));
        resolver = new UiLayoutResolver(registry);
    }

    @Test
    void resolveFieldHints_returnsHintsForMatchingEntity() {
        UiLayoutBuilder layout = new UiLayoutBuilder();
        layout.section("Reference").catalog(TestProduct.class, c -> c
                .field("fullName").order(0).widget("textarea")
                .field("unitPrice").hideInList());

        Map<String, FieldHint> hints = resolver.resolveFieldHints(
                buildLayout(layout), "catalog", "TestProducts");

        assertThat(hints).containsOnlyKeys("fullName", "unitPrice");
        assertThat(hints.get("fullName").widget()).isEqualTo("textarea");
        assertThat(hints.get("unitPrice").visibleInList()).isFalse();
    }

    @Test
    void resolveFieldHints_emptyWhenEntityNotInLayout() {
        UiLayoutBuilder layout = new UiLayoutBuilder();
        layout.section("Reference").catalog(TestProduct.class);

        Map<String, FieldHint> hints = resolver.resolveFieldHints(
                buildLayout(layout), "catalog", "SomeOtherCatalog");

        assertThat(hints).isEmpty();
    }

    @Test
    void resolveFieldHints_emptyWhenNoLambdaUsed() {
        UiLayoutBuilder layout = new UiLayoutBuilder();
        layout.section("Reference").catalog(TestProduct.class);

        Map<String, FieldHint> hints = resolver.resolveFieldHints(
                buildLayout(layout), "catalog", "TestProducts");

        assertThat(hints).isEmpty();
    }

    @Test
    void resolve_emitsPageLinksAsPageItems() {
        UiLayoutBuilder layout = new UiLayoutBuilder();
        layout.section("Reports")
                .catalog(TestProduct.class)
                .page("/ops", "Sales Ops", "activity")
                .page("/kpi", "KPIs");

        var sections = resolver.resolve(buildLayout(layout));

        assertThat(sections).hasSize(1);
        var items = sections.get(0).items();
        // The entity resolves first, then the two page links in declaration order.
        assertThat(items).hasSize(3);
        assertThat(items.get(0).type()).isEqualTo("catalog");

        UiLayout.ResolvedItem ops = items.get(1);
        assertThat(ops.type()).isEqualTo("page");
        assertThat(ops.href()).isEqualTo("/ops");        // route used verbatim as the nav href
        assertThat(ops.title()).isEqualTo("Sales Ops");
        assertThat(ops.icon()).isEqualTo("activity");
        assertThat(ops.javaClass()).isNull();            // a page link resolves by route, not metadata

        UiLayout.ResolvedItem kpi = items.get(2);
        assertThat(kpi.type()).isEqualTo("page");
        assertThat(kpi.href()).isEqualTo("/kpi");
        assertThat(kpi.icon()).isBlank();                // no explicit icon → left to the nav heuristic
    }

    private static UiLayout buildLayout(UiLayoutBuilder builder) {
        return new UiLayout(builder.build(), builder.buildWidgets());
    }
}
