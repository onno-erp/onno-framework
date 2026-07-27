package su.onno.ui;

import org.junit.jupiter.api.Test;
import su.onno.repository.EnumerationPersistence;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The state-aware row-action surface (#116): {@link ActionRow}'s read accessors and the per-row
 * functions an {@link ActionSpec} row action can carry.
 */
class ActionRowAndDynamicActionTest {

    enum Status { RUNNING, STOPPED }

    @Test
    void actionRow_readsId_displayText_andEnumValue() {
        UUID id = UUID.randomUUID();
        Map<String, Object> data = new HashMap<>();
        data.put("_id", id.toString());
        data.put("status", EnumerationPersistence.resolveId(Status.class, Status.STOPPED).toString());
        data.put("status_display", "STOPPED");              // …resolved to the constant name
        data.put("name", "Acme");

        ActionRow row = new ActionRow(data);
        assertThat(row.id()).isEqualTo(id);
        assertThat(row.text("status")).isEqualTo("STOPPED");
        assertThat(row.enumValue("status", Status.class)).isEqualTo(Status.STOPPED);
        assertThat(row.text("name")).isEqualTo("Acme");
        assertThat(row.text("missing")).isEmpty();
        assertThat(row.enumValue("missing", Status.class)).isNull();
    }

    @Test
    void enumValue_rejectsLegacyConstantNamesAndDisplayLabels() {
        assertThat(new ActionRow(Map.of("status", "STOPPED"))
                .enumValue("status", Status.class)).isNull();
        assertThat(new ActionRow(Map.of("status_display", "STOPPED"))
                .enumValue("status", Status.class)).isNull();
    }

    @Test
    void actionRow_lookupsAreCaseInsensitive_andNullSafe() {
        Map<String, Object> data = new HashMap<>();
        data.put("CODE", "C-1");
        ActionRow row = new ActionRow(data);
        assertThat(row.get("code")).isEqualTo("C-1");

        ActionRow empty = new ActionRow(null);
        assertThat(empty.id()).isNull();
        assertThat(empty.text("anything")).isEmpty();
    }

    @Test
    void builder_capturesPerRowFunctions_andMarksActionDynamic() {
        ActionSpec spec = new ActionSpec();
        spec.action("toggle").scope(ActionScope.ROW)
                .icon(row -> row.enumValue("status", Status.class) == Status.STOPPED ? "play" : "pause")
                .label(row -> row.enumValue("status", Status.class) == Status.STOPPED ? "Resume" : "Suspend")
                .visibleWhen(row -> row.enumValue("status", Status.class) != null)
                .enabledWhen(row -> true)
                .handler(ctx -> ActionResult.ok());

        ActionSpec.Action a = spec.actions().get(0);
        assertThat(a.isDynamic()).isTrue();

        ActionRow stopped = new ActionRow(Map.of(
                "status", EnumerationPersistence.resolveId(Status.class, Status.STOPPED)));
        ActionRow running = new ActionRow(Map.of(
                "status", EnumerationPersistence.resolveId(Status.class, Status.RUNNING)));
        assertThat(a.iconFn().apply(stopped)).isEqualTo("play");
        assertThat(a.iconFn().apply(running)).isEqualTo("pause");
        assertThat(a.labelFn().apply(stopped)).isEqualTo("Resume");
        assertThat(a.visibleFn().test(stopped)).isTrue();
        assertThat(a.enabledFn().test(running)).isTrue();
    }

    @Test
    void staticAction_isNotDynamic_andKeepsFixedIconLabel() {
        ActionSpec spec = new ActionSpec();
        spec.action("ping").label("Ping").icon("activity").scope(ActionScope.ROW)
                .handler(ctx -> ActionResult.ok());

        ActionSpec.Action a = spec.actions().get(0);
        assertThat(a.isDynamic()).isFalse();
        assertThat(a.icon()).isEqualTo("activity");
        assertThat(a.label()).isEqualTo("Ping");
        assertThat(a.iconFn()).isNull();
    }
}
