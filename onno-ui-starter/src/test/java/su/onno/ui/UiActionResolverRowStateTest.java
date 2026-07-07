package su.onno.ui;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Per-row resolution of state-aware row actions (#116): {@link UiActionResolver#hasDynamicRowActions}
 * gates the work, and {@link UiActionResolver#rowActionState} computes each dynamic row action's
 * icon/label/visible/enabled from a row's data — the {@code _actions} the list feed attaches.
 */
class UiActionResolverRowStateTest {

    enum Status { RUNNING, STOPPED }

    /** A view declaring a state-aware "toggle" row action plus a static "ping" row action. */
    static class InstanceView implements EntityView {
        @Override public Class<?> entity() { return Status.class; } // any class works as the key
        @Override public void actions(ActionSpec a) {
            a.action("toggle").scope(ActionScope.ROW)
                    .icon(row -> row.enumValue("status", Status.class) == Status.STOPPED ? "play" : "pause")
                    .label(row -> row.enumValue("status", Status.class) == Status.STOPPED ? "Resume" : "Suspend")
                    .visibleWhen(row -> row.enumValue("status", Status.class) != null)
                    .enabledWhen(row -> row.enumValue("status", Status.class) == Status.RUNNING)
                    .handler(ctx -> ActionResult.ok());
            a.action("ping").label("Ping").icon("activity").scope(ActionScope.ROW)
                    .handler(ctx -> ActionResult.ok());
            a.action("status-done").label("Done").icon("circle-dot").color("#059669").logo("/people/ada.png")
                    .scope(ActionScope.ROW).menu("Change status").handler(ctx -> ActionResult.ok());
        }
    }

    private final UiActionResolver resolver = new UiActionResolver(List.of(new InstanceView()));

    @Test
    void hasDynamicRowActions_trueWhenAnyRowActionVariesPerRow() {
        assertThat(resolver.hasDynamicRowActions(Status.class)).isTrue();
        assertThat(resolver.hasDynamicRowActions(String.class)).isFalse();
    }

    @Test
    void rowActionState_resolvesDynamicAction_perRow_andOmitsStaticOnes() {
        Map<String, Object> stopped = resolver.rowActionState(Status.class, Map.of("status_display", "STOPPED"));
        assertThat(stopped).containsOnlyKeys("toggle"); // static "ping" is not included
        @SuppressWarnings("unchecked")
        Map<String, Object> toggle = (Map<String, Object>) stopped.get("toggle");
        assertThat(toggle).containsEntry("icon", "play").containsEntry("label", "Resume")
                .containsEntry("visible", true).containsEntry("enabled", false); // disabled when not RUNNING

        Map<String, Object> running = resolver.rowActionState(Status.class, Map.of("status_display", "RUNNING"));
        @SuppressWarnings("unchecked")
        Map<String, Object> t2 = (Map<String, Object>) running.get("toggle");
        assertThat(t2).containsEntry("icon", "pause").containsEntry("label", "Suspend")
                .containsEntry("enabled", true);
    }

    @Test
    void rowActionState_hidesAction_whenVisiblePredicateFails() {
        Map<String, Object> noStatus = resolver.rowActionState(Status.class, Map.of()); // status absent → enumValue null
        @SuppressWarnings("unchecked")
        Map<String, Object> toggle = (Map<String, Object>) noStatus.get("toggle");
        assertThat(toggle).containsEntry("visible", false);
    }

    @Test
    void rowActionState_throwingPredicate_fallsBackToVisibleEnabled() {
        EntityView blowsUp = new EntityView() {
            @Override public Class<?> entity() { return Status.class; }
            @Override public void actions(ActionSpec a) {
                a.action("boom").scope(ActionScope.ROW)
                        .visibleWhen(row -> { throw new IllegalStateException("kaboom"); })
                        .handler(ctx -> ActionResult.ok());
            }
        };
        UiActionResolver r = new UiActionResolver(List.of(blowsUp));
        @SuppressWarnings("unchecked")
        Map<String, Object> state = (Map<String, Object>) r.rowActionState(Status.class, Map.of()).get("boom");
        assertThat(state).containsEntry("visible", true).containsEntry("enabled", true);
    }

    @Test
    void descriptors_includeMenuPresentationHints() {
        List<Map<String, Object>> actions = resolver.descriptors(Status.class, java.util.Set.of(ActionScope.ROW));
        assertThat(actions).anySatisfy(a -> assertThat(a)
                .containsEntry("key", "status-done")
                .containsEntry("menu", "Change status")
                .containsEntry("color", "#059669")
                .containsEntry("logo", "/people/ada.png"));
    }
}
