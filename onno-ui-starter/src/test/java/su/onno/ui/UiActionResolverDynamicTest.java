package su.onno.ui;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class UiActionResolverDynamicTest {

    record Choice(String key, String label, String color) {}

    @Test
    void liveDescriptors_reflectAddRenameReorderDelete_andResolveCurrentKeys() {
        List<Choice> choices = new ArrayList<>(List.of(
                new Choice("new", "New", "#111111"),
                new Choice("done", "Done", "#222222")));
        EntityView<String> view = new EntityView<>() {
            @Override public Class<String> entity() { return String.class; }

            @Override public void actions(ActionSpec actions) {
                actions.action("fixed").label("Fixed").scope(ActionScope.ROW)
                        .handler(ctx -> ActionResult.ok());
                actions.dynamic(live -> {
                    for (Choice choice : choices) {
                        live.action(choice.key()).label(choice.label()).color(choice.color())
                                .scope(ActionScope.ROW).menu("Change status")
                                .handler(ctx -> ActionResult.ok());
                    }
                });
            }
        };
        UiActionResolver resolver = new UiActionResolver(List.of(view));

        assertThat(resolver.hasDynamicActions(String.class)).isTrue();
        assertThat(resolver.hasDynamicRowActions(String.class)).isFalse();
        assertThat(resolver.descriptors(String.class, Set.of(ActionScope.ROW)))
                .extracting(m -> m.get("key")).containsExactly("fixed");
        assertThat(resolver.resolvedDescriptors(String.class, Set.of(ActionScope.ROW)))
                .extracting(m -> m.get("key")).containsExactly("fixed", "new", "done");

        choices.set(0, new Choice("done", "Completed", "#00ff00"));
        choices.set(1, new Choice("hold", "On hold", "#ffcc00"));

        List<Map<String, Object>> refreshed =
                resolver.resolvedDescriptors(String.class, Set.of(ActionScope.ROW));
        assertThat(refreshed).extracting(m -> m.get("key"))
                .containsExactly("fixed", "done", "hold");
        assertThat(refreshed.get(1))
                .containsEntry("label", "Completed")
                .containsEntry("color", "#00ff00");
        assertThat(resolver.find(String.class, "new")).isNull();
        assertThat(resolver.find(String.class, "hold")).isNotNull();
    }

    @Test
    void firstViewAndFirstDeclarationWinDuplicateKeys() {
        EntityView<Integer> first = new EntityView<>() {
            @Override public Class<Integer> entity() { return Integer.class; }

            @Override public void actions(ActionSpec actions) {
                actions.dynamic(live -> live.action("same").label("Dynamic first"));
                actions.action("same").label("Static second");
            }
        };
        EntityView<Integer> second = new EntityView<>() {
            @Override public Class<Integer> entity() { return Integer.class; }

            @Override public void actions(ActionSpec actions) {
                actions.action("same").label("Other view");
            }
        };

        UiActionResolver resolver = new UiActionResolver(List.of(first, second));

        assertThat(resolver.resolvedForEntity(Integer.class))
                .singleElement()
                .extracting(ActionSpec.Action::label)
                .isEqualTo("Dynamic first");
    }

    @Test
    void lateBoundMenusHaveNoArtificialSiblingLimit() {
        EntityView<Long> view = new EntityView<>() {
            @Override public Class<Long> entity() { return Long.class; }

            @Override public void actions(ActionSpec actions) {
                actions.dynamic(live -> {
                    for (int i = 0; i < 12; i++) {
                        live.action("choice-" + i).scope(ActionScope.ROW).menu("Choose");
                    }
                });
            }
        };

        UiActionResolver resolver = new UiActionResolver(List.of(view));

        assertThat(resolver.resolvedDescriptors(Long.class, Set.of(ActionScope.ROW)))
                .hasSize(12)
                .extracting(action -> action.get("key"))
                .containsExactlyElementsOf(
                        java.util.stream.IntStream.range(0, 12)
                                .mapToObj(i -> "choice-" + i)
                                .toList());
    }
}
