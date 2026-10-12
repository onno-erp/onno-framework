package su.onno.ui;

import org.junit.jupiter.api.Test;
import java.util.function.Consumer;
import static org.assertj.core.api.Assertions.*;

class PageViewTest {
    @Test void reusableListUsesExistingDescriptorAndFreezesOnFirstPlacement() {
        Consumer<PageBuilder.ListDefaults> configure = d -> d.fill().filter("active = true")
                .defaultFilter("status", "OPEN").defaultFirstFilterOption("owner")
                .groupBy("created", DateGranularity.DAY).sort("created", true);
        var view = new ListView<>(String.class).defaults(configure);
        var first = new PageBuilder().view(view);
        var legacy = new PageBuilder().list(String.class, configure);
        assertThat(first.components()).isEqualTo(legacy.components());
        assertThatIllegalStateException().isThrownBy(() -> view.defaults(d -> d.defaultFilter("status", "CLOSED")));
        var second = new PageBuilder().aside(p -> p.view(view));
        assertThat(second.aside().components()).isEqualTo(first.components());
    }
    @Test void aRetainedDefaultsReferenceCannotChangeTheViewLater() {
        var leaked = new java.util.concurrent.atomic.AtomicReference<PageBuilder.ListDefaults>();
        var view = new ListView<>(String.class).defaults(d -> leaked.set(d.sort("created")));
        var expected = new PageBuilder().list(String.class, d -> d.sort("created")).components();
        leaked.get().fill().filter("archived = true");
        assertThat(new PageBuilder().view(view).components()).isEqualTo(expected);
        leaked.get().groupBy("status");
        assertThat(new PageBuilder().view(view).components()).isEqualTo(expected);
    }
    @Test void customClassesComposeThroughTheSameContract() {
        PageView view = p -> p.text("Reusable content");
        assertThat(new PageBuilder().view(view).components()).containsExactly(PageComponent.text("Reusable content"));
    }
}
