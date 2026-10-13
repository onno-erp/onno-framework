package su.onno.ui.calendar;

import java.time.LocalDateTime;

/**
 * Where a dragged or resized calendar item landed. {@code end} is exclusive and may be null when the
 * widget dropped an item without a known end. {@code allDay} reports the slot it was dropped into, so
 * a source can tell a month-grid day drop from a timed week-view drop.
 */
public record CalendarMove(LocalDateTime start, LocalDateTime end, boolean allDay) {
}
