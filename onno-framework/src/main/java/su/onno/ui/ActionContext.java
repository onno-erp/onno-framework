package su.onno.ui;

import java.util.Map;
import java.util.UUID;

/**
 * What a custom action handler receives when invoked: the entity it ran on, (for row/detail
 * actions) the record's id, and the current values of any toolbar {@link InputSpec inputs}. The
 * handler does whatever it likes with the services its {@link EntityView} bean injected — it's
 * just a method on a Spring bean.
 *
 * @param kind   {@code "catalogs"} or {@code "documents"}
 * @param name   the entity's route name
 * @param id     the target record's id, or {@code null} for a toolbar (list-level) action
 * @param user   the authenticated username, for the handler's own checks
 * @param inputs current values of the toolbar inputs, keyed by input key (never null)
 */
public record ActionContext(String kind, String name, UUID id, String user, Map<String, String> inputs) {

    public ActionContext {
        inputs = inputs == null ? Map.of() : inputs;
    }

    /** The current value of toolbar input {@code key}, or {@code ""} if absent. */
    public String input(String key) {
        return inputs.getOrDefault(key, "");
    }
}
