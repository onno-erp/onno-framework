package su.onno.ui;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Per-record resolution of state-aware DETAIL actions (#255):
 * {@link UiActionResolver#recordActionState} evaluates an action's {@code visibleWhen} /
 * {@code enabledWhen} / {@code label(fn)} / {@code icon(fn)} against the loaded detail record —
 * what lets one detail-header button hide, relabel or grey itself by the record's state, the way
 * a ROW action already varies per list row.
 */
class UiActionResolverRecordStateTest {

    enum Status { NEW, DOWNLOADED, DONE }

    /** A view declaring a state-aware "advance" DETAIL action plus a static "export" one. */
    static class OrderView implements EntityView {
        @Override public Class<?> entity() { return Status.class; } // any class works as the key
        @Override public void actions(ActionSpec a) {
            a.action("advance").scope(ActionScope.DETAIL)
                    .label("Next status")
                    .label(row -> "→ " + row.enumValue("status", Status.class))
                    .icon("arrow-right")
                    .icon(row -> row.enumValue("status", Status.class) == Status.DONE ? "check" : "arrow-right")
                    .visibleWhen(row -> row.enumValue("status", Status.class) != Status.DONE)
                    .enabledWhen(row -> row.enumValue("status", Status.class) == Status.NEW)
                    .handler(ctx -> ActionResult.ok());
            a.action("export").label("Export").icon("download").scope(ActionScope.DETAIL)
                    .handler(ctx -> ActionResult.ok());
        }
    }

    private final UiActionResolver resolver = new UiActionResolver(List.of(new OrderView()));

    @Test
    void dynamicDetailAction_resolvesPerRecord() {
        ActionSpec.Action advance = resolver.find(Status.class, "advance");

        var onNew = UiActionResolver.recordActionState(advance, Map.of("status_display", "NEW"));
        assertThat(onNew.visible()).isTrue();
        assertThat(onNew.enabled()).isTrue();
        assertThat(onNew.label()).isEqualTo("→ NEW");
        assertThat(onNew.icon()).isEqualTo("arrow-right");

        var onDownloaded = UiActionResolver.recordActionState(advance, Map.of("status_display", "DOWNLOADED"));
        assertThat(onDownloaded.visible()).isTrue();
        assertThat(onDownloaded.enabled()).as("enabledWhen requires NEW").isFalse();

        var onDone = UiActionResolver.recordActionState(advance, Map.of("status_display", "DONE"));
        assertThat(onDone.visible()).as("visibleWhen hides the action at DONE").isFalse();
    }

    @Test
    void staticDetailAction_keepsFixedLabelAndIcon_visibleAndEnabled() {
        ActionSpec.Action export = resolver.find(Status.class, "export");

        var state = UiActionResolver.recordActionState(export, Map.of("status_display", "DONE"));
        assertThat(state.visible()).isTrue();
        assertThat(state.enabled()).isTrue();
        assertThat(state.label()).isEqualTo("Export");
        assertThat(state.icon()).isEqualTo("download");
    }

    @Test
    void throwingFunctions_fallBackToVisibleEnabledAndFixedValues() {
        EntityView blowsUp = new EntityView() {
            @Override public Class<?> entity() { return Status.class; }
            @Override public void actions(ActionSpec a) {
                a.action("boom").label("Boom").icon("zap").scope(ActionScope.DETAIL)
                        .label(row -> { throw new IllegalStateException("kaboom"); })
                        .visibleWhen(row -> { throw new IllegalStateException("kaboom"); })
                        .enabledWhen(row -> { throw new IllegalStateException("kaboom"); })
                        .handler(ctx -> ActionResult.ok());
            }
        };
        UiActionResolver r = new UiActionResolver(List.of(blowsUp));

        var state = UiActionResolver.recordActionState(r.find(Status.class, "boom"), Map.of());
        assertThat(state.visible()).isTrue();
        assertThat(state.enabled()).isTrue();
        assertThat(state.label()).isEqualTo("Boom");
        assertThat(state.icon()).isEqualTo("zap");
    }
}
