package su.onno.ui.calendar;

import java.security.Principal;
import java.util.List;
import java.util.Set;

/**
 * A Spring bean that feeds entries into a named calendar. Several sources may share one calendar
 * name; a {@code .type("calendar").calendar("studio")} widget merges every source of calendar
 * {@code studio} the viewer may read, draws one legend entry per source, and offers drag-to-reschedule
 * on sources that allow it.
 *
 * <p>Use a source when a calendar spans several entities, derives items (durations, colours, links)
 * from business rules, or needs filters; a single catalog or document with plain date fields can use
 * the entity-backed widget instead ({@code .catalog(X.class).dateField(...)}).</p>
 *
 * <pre>{@code
 * @Component
 * class MeetingsSource implements CalendarSource {
 *     public String calendar() { return "studio"; }
 *     public String key() { return "meetings"; }
 *     public String label() { return "Meetings"; }
 *     public String color() { return "#60A5FA"; }
 *     public List<CalendarItem> items(CalendarQuery q) { ... }
 *     public boolean canReschedule(Principal p) { return true; }
 *     public void reschedule(String id, CalendarMove move, Principal p) { ... }
 * }
 * }</pre>
 */
public interface CalendarSource {

    /** Calendar this source contributes to, matched against the widget's {@code calendar} name. */
    String calendar();

    /** Stable key, unique within the calendar; also the legend toggle and item-id prefix. */
    String key();

    /** Legend label. */
    String label();

    /** Legend colour and the default colour of items that set none. */
    String color();

    /** Legend order; lower first. */
    default int order() { return 0; }

    /** Whether the viewer may see this source at all. Hidden sources are left out of the legend too. */
    default boolean canRead(Principal principal) { return true; }

    /**
     * Items overlapping the query window. Called only after {@link #canRead} passed. Facet filtering
     * is optional here; the framework re-applies {@link CalendarQuery#matches} to every item.
     */
    List<CalendarItem> items(CalendarQuery query);

    /** Whether the viewer may drag this source's items. Items can opt out individually. */
    default boolean canReschedule(Principal principal) { return false; }

    /**
     * Persist a drag or resize. {@code id} is the source-local id the item was built with. Throw
     * {@link IllegalArgumentException} for a move the business rules refuse; its message is shown to
     * the user and the item snaps back.
     */
    default void reschedule(String id, CalendarMove move, Principal principal) {
        throw new UnsupportedOperationException("This calendar source cannot be rescheduled");
    }

    /**
     * Entity names (as in UI change events, e.g. {@code Weddings}) whose changes should refresh the
     * widget live.
     */
    default Set<String> watches() { return Set.of(); }
}
