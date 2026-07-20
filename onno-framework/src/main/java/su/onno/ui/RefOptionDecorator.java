package su.onno.ui;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Application-provided batch resolver for contextual reference-picker option state.
 *
 * <p>Implement this as an ordinary Spring bean, then attach it to a reference field with
 * {@link FieldHintBuilder#refOptions(Class)}. The resolver receives the current parent form,
 * tabular row, edit-document id, and the capped option page in one call. Returning no entry for an
 * option leaves it undecorated.</p>
 */
@FunctionalInterface
public interface RefOptionDecorator {

    Map<UUID, RefOptionDecoration> decorate(RefOptionContext context, List<RefOption> options);
}
