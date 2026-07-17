package su.onno.ui;

import su.onno.ui.ActionSpec.FormDefaults;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Server-computed action-form opening values ({@code ActionSpec.formDefaults}): the descriptor
 * advertises {@code dynamicForm: true} so the dialog knows to fetch
 * {@code GET /api/actions/{kind}/{name}/{key}/form}, and the hook's {@link FormDefaults} carries
 * scalar values and row-group rows in the same wire shape the form submits back.
 */
class ActionFormDefaultsTest {

    static class PrintersView implements EntityView {
        @Override public Class<?> entity() { return String.class; }
        @Override public void actions(ActionSpec a) {
            // Dynamic: the modal opens seeded with one row per free printer.
            a.action("startPrint").scope(ActionScope.ROW)
                    .form(f -> f.group("prints", g -> g.label("Prints").required()
                            .column("printer", c -> c.label("Printer"))
                            .column("photoCount", c -> c.label("Photos").type(InputType.NUMBER))))
                    .formDefaults(ctx -> FormDefaults.ofRows("prints",
                            List.of(Map.of("printer", "p-1", "photoCount", "50"))))
                    .handler(ctx -> ActionResult.ok());
            // Static form: no defaults hook → no dynamicForm flag.
            a.action("cancel").scope(ActionScope.ROW)
                    .form(f -> f.input("reason").label("Reason").required())
                    .handler(ctx -> ActionResult.ok());
            // A defaults hook without a form is inert (nothing to seed).
            a.action("noForm").scope(ActionScope.ROW)
                    .formDefaults(ctx -> FormDefaults.ofValues(Map.of("x", "1")))
                    .handler(ctx -> ActionResult.ok());
        }
    }

    private final UiActionResolver resolver = new UiActionResolver(List.of(new PrintersView()));

    @Test
    void descriptorAdvertisesDynamicFormOnlyForFormActionsWithADefaultsHook() {
        List<Map<String, Object>> descriptors =
                resolver.descriptors(String.class, java.util.Set.of(ActionScope.ROW));
        Map<String, Object> startPrint = byKey(descriptors, "startPrint");
        Map<String, Object> cancel = byKey(descriptors, "cancel");
        Map<String, Object> noForm = byKey(descriptors, "noForm");

        assertThat(startPrint).containsEntry("dynamicForm", true);
        assertThat(cancel).doesNotContainKey("dynamicForm");
        assertThat(noForm).doesNotContainKey("dynamicForm").doesNotContainKey("form");
    }

    @Test
    void hasDynamicFormRequiresBothAFormAndAHook() {
        assertThat(resolver.find(String.class, "startPrint").hasDynamicForm()).isTrue();
        assertThat(resolver.find(String.class, "cancel").hasDynamicForm()).isFalse();
        assertThat(resolver.find(String.class, "noForm").hasDynamicForm()).isFalse();
    }

    @Test
    void formDefaultsHookReceivesTheOpenContextAndReturnsWireShapedRows() {
        var action = resolver.find(String.class, "startPrint");
        UUID id = UUID.randomUUID();
        FormDefaults defaults = action.formDefaultsFn()
                .apply(new ActionContext("documents", "orders", id, "admin", Map.of(), Map.of()));
        assertThat(defaults.values()).isEmpty();
        assertThat(defaults.rows()).containsKey("prints");
        assertThat(defaults.rows().get("prints"))
                .containsExactly(Map.of("printer", "p-1", "photoCount", "50"));
    }

    @Test
    void formDefaultsRecordNormalizesNulls() {
        FormDefaults d = new FormDefaults(null, null);
        assertThat(d.values()).isEmpty();
        assertThat(d.rows()).isEmpty();
    }

    private static Map<String, Object> byKey(List<Map<String, Object>> descriptors, String key) {
        return descriptors.stream().filter(m -> key.equals(m.get("key"))).findFirst().orElseThrow();
    }
}
