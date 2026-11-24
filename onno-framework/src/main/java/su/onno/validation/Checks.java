package su.onno.validation;

/**
 * Live checks of one catalog or document: advisory feedback computed on the server while a record
 * is being edited, before it is saved. Register as a Spring bean; several beans for one entity
 * merge. A client re-runs a check when one of its {@link CheckBuilder#dependsOn dependencies}
 * changes ({@code POST /api/checks/{kind}/{name}/{key}} — a dry run, nothing persists).
 *
 * <p>Checks are advisory. Rules that must hold on save belong in {@code HasBusinessRules} /
 * {@code BeforeWriteHandler}, which stay authoritative.</p>
 *
 * <pre>
 * &#64;Component
 * class ScheduleEventChecks implements Checks&lt;ScheduleEvent&gt; {
 *     public Class&lt;ScheduleEvent&gt; entity() { return ScheduleEvent.class; }
 *
 *     public void define(CheckSpec&lt;ScheduleEvent&gt; v) {
 *         v.check("schedule-conflicts", this::conflicts)
 *          .dependsOn(ScheduleEvent::getStartsAt, ScheduleEvent::getEndsAt)
 *          .andDependsOn(ScheduleEvent::getParticipants, ScheduleParticipant::getEmployee)
 *          .debounce(Duration.ofMillis(200));
 *     }
 * }
 * </pre>
 *
 * @param <E> the catalog or document class
 */
public interface Checks<E> {

    /** The catalog or document class. */
    Class<E> entity();

    /** Declare the entity's checks. */
    void define(CheckSpec<E> checks);
}
