package su.onno.ui.calendar;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * One entry a {@link CalendarSource} contributes to a calendar widget.
 *
 * <p>{@code id} only has to be unique within its source; the framework prefixes it with the
 * source key on the wire and strips the prefix again before calling
 * {@link CalendarSource#reschedule}. {@code end} is exclusive; an all-day item spans whole days from
 * {@code start}'s date to {@code end}'s date. {@code link} is an in-app route such as
 * {@code catalogs/weddings/<id>} or {@code inbox?conversation=<id>} (no leading slash).
 * {@code facets} carries the item's value for each {@link CalendarFacet} key (for example the owner's
 * id under {@code owner}) so the framework can apply the widget's filters uniformly.</p>
 *
 * <p>Build one with {@link #builder(String, String, LocalDateTime)}.</p>
 */
public record CalendarItem(
        String id,
        String title,
        String subtitle,
        LocalDateTime start,
        LocalDateTime end,
        boolean allDay,
        String color,
        String link,
        String meta,
        boolean done,
        boolean tracksCompletion,
        boolean editable,
        boolean resizable,
        Map<String, String> facets) {

    public CalendarItem {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(start, "start");
        title = title == null ? "" : title;
        if (end == null || !end.isAfter(start)) end = allDay ? start.plusDays(1) : start.plusHours(1);
        facets = facets == null ? Map.of() : Map.copyOf(facets);
    }

    /** True when the item overlaps the half-open window {@code [from, to)}. */
    public boolean overlaps(LocalDateTime from, LocalDateTime to) {
        return start.isBefore(to) && end.isAfter(from);
    }

    public static Builder builder(String id, String title, LocalDateTime start) {
        return new Builder(id, title, start);
    }

    /** Fluent construction; every setter is optional. */
    public static final class Builder {
        private final String id;
        private final String title;
        private final LocalDateTime start;
        private LocalDateTime end;
        private boolean allDay;
        private String subtitle;
        private String color;
        private String link;
        private String meta;
        private boolean done;
        private boolean tracksCompletion;
        private boolean editable = true;
        private boolean resizable = true;
        private final Map<String, String> facets = new LinkedHashMap<>();

        private Builder(String id, String title, LocalDateTime start) {
            this.id = id;
            this.title = title;
            this.start = start;
        }

        /** Exclusive end. Defaults to one hour after start, or one day for all-day items. */
        public Builder end(LocalDateTime end) { this.end = end; return this; }
        public Builder allDay(boolean allDay) { this.allDay = allDay; return this; }
        /** Second line, e.g. location or client. */
        public Builder subtitle(String subtitle) { this.subtitle = subtitle; return this; }
        /** CSS colour; falls back to the source's legend colour. */
        public Builder color(String color) { this.color = color; return this; }
        /** In-app route opened on click, without a leading slash. */
        public Builder link(String link) { this.link = link; return this; }
        /** Short trailing detail such as the assignee's name. */
        public Builder meta(String meta) { this.meta = meta; return this; }
        /**
         * Marks a task-like item: once its end has passed while not {@code done}, the widget flags it
         * overdue. Done items render struck through.
         */
        public Builder task(boolean done) { this.tracksCompletion = true; this.done = done; return this; }
        /** Whether this item may be dragged, on top of the source's {@link CalendarSource#canReschedule}. */
        public Builder editable(boolean editable) { this.editable = editable; return this; }
        /**
         * Whether a draggable item may also be stretched or shortened. Turn off for items whose length
         * is fixed by the domain (a single-day date); {@link CalendarSource#reschedule} then only ever
         * sees moves.
         */
        public Builder resizable(boolean resizable) { this.resizable = resizable; return this; }
        /** The item's value for a {@link CalendarFacet}; null values are skipped. */
        public Builder facet(String key, Object value) {
            if (value != null) facets.put(key, value.toString());
            return this;
        }

        public CalendarItem build() {
            return new CalendarItem(id, title, subtitle, start, end, allDay, color, link, meta, done,
                    tracksCompletion, editable, resizable, facets);
        }
    }
}
