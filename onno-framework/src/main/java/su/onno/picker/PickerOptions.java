package su.onno.picker;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Contextual state for the options of one reference field's picker, computed on the server from
 * the live (unsaved) form: a badge ("Available"), a disabled option with a reason, or an option
 * hidden from this search. Register as a Spring bean; it binds itself to the field it decorates —
 * the owner entity and the field path ({@code "customer"}, or {@code "section.field"} for a
 * tabular-section column). One bean per field; a second bean for the same field fails startup.
 *
 * <p>The picker search (and the picker dialog) call {@link #decorate} once per result page with
 * the parent form, the current tabular row and the capped candidates. Returning no entry for a
 * candidate leaves it undecorated.</p>
 *
 * <pre>
 * &#64;Component
 * class EmployeeAvailability implements PickerOptions&lt;ScheduleEvent&gt; {
 *     public Class&lt;ScheduleEvent&gt; entity() { return ScheduleEvent.class; }
 *     public String field() { return "participants.employee"; }
 *     public Map&lt;UUID, PickerOption&gt; decorate(PickerContext ctx, List&lt;PickerCandidate&gt; options) { … }
 * }
 * </pre>
 *
 * @param <E> the catalog or document whose form holds the reference field
 */
public interface PickerOptions<E> {

    /** The catalog or document whose form holds the reference field. */
    Class<E> entity();

    /** The reference field: a top-level field name or {@code section.field}. */
    String field();

    /** The state of each candidate worth decorating, keyed by candidate id. */
    Map<UUID, PickerOption> decorate(PickerContext context, List<PickerCandidate> options);
}
