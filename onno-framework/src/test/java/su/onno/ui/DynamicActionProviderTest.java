package su.onno.ui;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class DynamicActionProviderTest {

    @Test
    void provider_isLateBound_andPreservesItsDeclarationPosition() {
        AtomicInteger evaluations = new AtomicInteger();
        List<String> liveKeys = new ArrayList<>(List.of("one", "two"));
        ActionSpec spec = new ActionSpec();
        spec.action("before").label("Before");
        spec.dynamic(live -> {
            evaluations.incrementAndGet();
            for (String key : liveKeys) {
                live.action(key).label(key);
            }
        });
        spec.action("after").label("After");

        assertThat(spec.hasDynamicActions()).isTrue();
        assertThat(spec.actions()).extracting(ActionSpec.Action::key)
                .containsExactly("before", "after");
        assertThat(evaluations).hasValue(0);

        assertThat(spec.resolveActions()).extracting(ActionSpec.Action::key)
                .containsExactly("before", "one", "two", "after");
        liveKeys.remove("one");
        liveKeys.add(0, "three");
        assertThat(spec.resolveActions()).extracting(ActionSpec.Action::key)
                .containsExactly("before", "three", "two", "after");
        assertThat(evaluations).hasValue(2);
    }
}
