package su.onno.ui.calendar;

import java.security.Principal;
import java.util.List;

/**
 * A filter offered above a source-backed calendar, such as "Planner" or "Client". Items expose their
 * value for the facet through {@link CalendarItem.Builder#facet}; the widget shows a picker with
 * {@link #options} and, when {@link #me} resolves, an "Only me" choice. The selection is remembered
 * per viewer in the browser.
 */
public interface CalendarFacet {

    /** Calendar this filter belongs to. */
    String calendar();

    /** Key matched against {@link CalendarItem#facets()}. */
    String key();

    /** Picker label. */
    String label();

    /** Picker order; lower first. */
    default int order() { return 0; }

    /** Choices for this viewer. An empty list hides the picker. */
    List<Option> options(Principal principal);

    /** The viewer's own value, enabling "Only me"; null when the viewer has none. */
    default String me(Principal principal) { return null; }

    record Option(String id, String label) {}
}
