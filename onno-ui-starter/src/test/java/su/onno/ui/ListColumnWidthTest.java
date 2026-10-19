package su.onno.ui;

import su.onno.annotations.Attribute;
import su.onno.annotations.Catalog;
import su.onno.metadata.CatalogDescriptor;
import su.onno.metadata.DefaultNamingStrategy;
import su.onno.metadata.MetadataRegistry;
import su.onno.metadata.MetadataScanner;
import su.onno.model.CatalogObject;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * List column sizing is separate from form layout (#275): a {@link ListSpec#width(String, int)}
 * override wins, else a field hint's width only when it is an explicit pixel value, else blank
 * (auto). Form-layout hints such as {@code "half"} / {@code "1/2"} never become list widths.
 */
class ListColumnWidthTest {

    @Catalog(name = "WidthOrders")
    static class Order extends CatalogObject {
        @Attribute(length = 40)
        private String status;
        @Attribute(length = 40)
        private String channel;
        @Attribute(length = 40)
        private String format;
        @Attribute(length = 40)
        private String note;
    }

    private final MetadataScanner scanner = new MetadataScanner(new DefaultNamingStrategy());
    private final CatalogDescriptor descriptor = scanner.scan(Order.class);

    private UiViewResolver resolverWith(Consumer<EntityConfigBuilder> fields) {
        MetadataRegistry registry = new MetadataRegistry();
        registry.registerCatalog(descriptor);
        EntityView view = new EntityView() {
            @Override public Class<?> entity() { return Order.class; }
            @Override public void fields(EntityConfigBuilder f) { fields.accept(f); }
        };
        ResolvedMetadataService metadata =
                new ResolvedMetadataService(registry, new FieldHintResolver(List.of(view)));
        return new UiViewResolver(metadata, List.of(view));
    }

    private static Map<String, String> widths(ResolvedListView view) {
        return view.columns().stream().collect(Collectors.toMap(
                ResolvedListView.Column::fieldName, ResolvedListView.Column::width));
    }

    @Test
    void listOverrideCoexistsWithAHalfWidthFormHint() {
        UiViewResolver resolver = resolverWith(f -> f
                .field("status").width("half")
                .field("channel").width("1/2")
                .field("format").width("50%")
                .field("note").width("half"));
        ListSpec<Object> spec = new ListSpec<>()
                .columns("status", "channel", "format", "note")
                .width("status", 105)
                .width("format", "75px");

        Map<String, String> widths = widths(resolver.catalogList(descriptor, spec));

        assertThat(widths).containsEntry("status", "105")
                .containsEntry("format", "75")
                // A form fraction with no list override sizes automatically — never a 1px track.
                .containsEntry("channel", "")
                .containsEntry("note", "");
    }

    @Test
    void pixelFieldHintStillSizesTheListColumn_butTheOverrideWins() {
        UiViewResolver resolver = resolverWith(f -> f
                .field("status").width("260")
                .field("channel").width("90px")
                .field("format").width("120"));
        ListSpec<Object> spec = new ListSpec<>().width("format", 75);

        Map<String, String> widths = widths(resolver.catalogList(descriptor, spec));

        assertThat(widths).containsEntry("status", "260")
                .containsEntry("channel", "90")
                .containsEntry("format", "75")
                .containsEntry("note", "");
    }

    @Test
    void halfWidthHintStillReachesTheForm() {
        MetadataRegistry registry = new MetadataRegistry();
        registry.registerCatalog(descriptor);
        EntityView view = new EntityView() {
            @Override public Class<?> entity() { return Order.class; }
            @Override public void fields(EntityConfigBuilder f) { f.field("status").width("half"); }
        };
        ResolvedMetadataService metadata =
                new ResolvedMetadataService(registry, new FieldHintResolver(List.of(view)));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> attrs =
                (List<Map<String, Object>>) metadata.describeCatalog(descriptor).get("attributes");
        Map<String, Object> status = attrs.stream()
                .filter(a -> "status".equals(a.get("fieldName"))).findFirst().orElseThrow();

        assertThat(status.get("widthHint")).isEqualTo("half");
    }
}
