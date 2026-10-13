package su.onno.ui;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.security.Principal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;
import su.onno.ui.calendar.CalendarFacet;
import su.onno.ui.calendar.CalendarItem;
import su.onno.ui.calendar.CalendarMove;
import su.onno.ui.calendar.CalendarQuery;
import su.onno.ui.calendar.CalendarSource;

class CalendarControllerTest {
    private static final Principal ANN = () -> "ann";
    private static final Principal BOB = () -> "bob";
    private static final LocalDateTime OCT_5 = LocalDateTime.of(2026, 10, 5, 10, 0);

    /** Readable by everyone, movable only by Ann; records every move it receives. */
    static final class Meetings implements CalendarSource {
        final List<String> moves = new ArrayList<>();
        public String calendar() { return "studio"; }
        public String key() { return "meetings"; }
        public String label() { return "Meetings"; }
        public String color() { return "#60A5FA"; }
        public Set<String> watches() { return Set.of("FollowUps"); }
        public List<CalendarItem> items(CalendarQuery q) {
            return List.of(
                    CalendarItem.builder("m1", "Venue walk", OCT_5).facet("owner", "ann").task(false).build(),
                    CalendarItem.builder("m2", "Tasting", OCT_5.plusDays(1)).facet("owner", "bob").build(),
                    CalendarItem.builder("m3", "Locked", OCT_5.plusDays(2)).facet("owner", "ann").editable(false).build(),
                    CalendarItem.builder("m5", "Fixed length", OCT_5.plusDays(3)).facet("owner", "ann").resizable(false).build(),
                    CalendarItem.builder("m4", "Next year", OCT_5.plusYears(1)).build());
        }
        public boolean canReschedule(Principal p) { return "ann".equals(p.getName()); }
        public void reschedule(String id, CalendarMove move, Principal p) {
            if (move.start().getHour() < 8) throw new IllegalArgumentException("Too early");
            moves.add(id + "@" + move.start());
        }
    }

    /** Only Ann may see it; read-only. */
    static final class Private implements CalendarSource {
        public String calendar() { return "studio"; }
        public String key() { return "private"; }
        public String label() { return "Private"; }
        public String color() { return "#000000"; }
        public int order() { return -1; }
        public boolean canRead(Principal p) { return "ann".equals(p.getName()); }
        public List<CalendarItem> items(CalendarQuery q) {
            return List.of(CalendarItem.builder("p1", "Secret", OCT_5).allDay(true).build());
        }
    }

    static final class Owners implements CalendarFacet {
        public String calendar() { return "studio"; }
        public String key() { return "owner"; }
        public String label() { return "Planner"; }
        public List<Option> options(Principal p) { return List.of(new Option("ann", "Ann"), new Option("bob", "Bob")); }
        public String me(Principal p) { return p.getName(); }
    }

    private final Meetings meetings = new Meetings();
    private final CalendarController controller =
            new CalendarController(List.of(meetings, new Private()), List.of(new Owners()));

    private List<CalendarController.ItemView> items(Principal p, List<String> facets) {
        return controller.items("studio", LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 31), facets, null, p);
    }

    @Test
    void metaListsReadableSourcesInOrderWithFacetsAndWatches() {
        var meta = controller.meta("studio", ANN);
        assertThat(meta.sources()).extracting(CalendarController.SourceView::key).containsExactly("private", "meetings");
        assertThat(meta.sources()).extracting(CalendarController.SourceView::canReschedule).containsExactly(false, true);
        assertThat(meta.facets()).singleElement().satisfies(f -> assertThat(f.me()).isEqualTo("ann"));
        assertThat(meta.watches()).containsExactly("FollowUps");
        assertThat(controller.meta("studio", BOB).sources()).extracting(CalendarController.SourceView::key).containsExactly("meetings");
    }

    @Test
    void itemsAreWindowedPrefixedColouredAndPermissionScoped() {
        var annItems = items(ANN, null);
        assertThat(annItems).extracting(CalendarController.ItemView::id).containsExactly("private:p1", "meetings:m1", "meetings:m2", "meetings:m3", "meetings:m5");
        assertThat(annItems.getFirst().color()).isEqualTo("#000000");
        assertThat(annItems.getFirst().end()).isEqualTo(OCT_5.plusDays(1));
        assertThat(annItems).filteredOn(i -> i.id().equals("meetings:m3")).singleElement()
                .satisfies(i -> assertThat(i.editable()).isFalse());
        assertThat(items(BOB, null)).extracting(CalendarController.ItemView::id).doesNotContain("private:p1")
                .allSatisfy(id -> assertThat(id).startsWith("meetings:"));
        assertThat(items(BOB, null)).allSatisfy(i -> assertThat(i.editable()).isFalse());
        assertThat(annItems).filteredOn(i -> i.id().equals("meetings:m5")).singleElement()
                .satisfies(i -> { assertThat(i.editable()).isTrue(); assertThat(i.resizable()).isFalse(); });
        assertThat(annItems).filteredOn(i -> i.id().equals("meetings:m1")).singleElement()
                .satisfies(i -> assertThat(i.resizable()).isTrue());
    }

    @Test
    void facetFiltersDropNonMatchingAndValuelessItems() {
        assertThat(items(ANN, List.of("owner:bob"))).extracting(CalendarController.ItemView::title).containsExactly("Tasting");
        assertThat(CalendarController.parseFacets(List.of("owner:a:b", "bad", "client:"))).containsOnlyKeys("owner");
    }

    @Test
    void rescheduleChecksSourceAccessAndSurfacesBusinessRefusals() {
        var move = new CalendarController.MoveRequest(OCT_5.plusDays(3), OCT_5.plusDays(3).plusHours(1), false);
        controller.reschedule("studio", "meetings:m1", move, ANN);
        assertThat(meetings.moves).containsExactly("m1@" + OCT_5.plusDays(3));

        assertThatThrownBy(() -> controller.reschedule("studio", "meetings:m1", move, BOB))
                .isInstanceOf(ResponseStatusException.class).hasMessageContaining("403");
        assertThatThrownBy(() -> controller.reschedule("studio", "private:p1", move, BOB))
                .isInstanceOf(ResponseStatusException.class).hasMessageContaining("404");
        assertThatThrownBy(() -> controller.reschedule("studio", "private:p1", move, ANN))
                .isInstanceOf(ResponseStatusException.class).hasMessageContaining("403");
        assertThatThrownBy(() -> controller.reschedule("studio", "meetings:m1",
                new CalendarController.MoveRequest(OCT_5.withHour(6), null, false), ANN))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("Too early");
        assertThatThrownBy(() -> controller.reschedule("studio", "meetings:m1",
                new CalendarController.MoveRequest(OCT_5, OCT_5.minusHours(1), false), ANN))
                .isInstanceOf(ResponseStatusException.class).hasMessageContaining("400");
    }

    @Test
    void rejectsUnknownCalendarsAndOversizedWindows() {
        assertThatThrownBy(() -> controller.meta("nope", ANN)).hasMessageContaining("404");
        assertThatThrownBy(() -> controller.items("studio", LocalDate.of(2026, 1, 1), LocalDate.of(2027, 6, 30), null, null, ANN))
                .hasMessageContaining("400");
    }
}
