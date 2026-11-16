package su.onno.ui;

import su.onno.annotations.Attribute;
import su.onno.annotations.Catalog;
import su.onno.metadata.CatalogDescriptor;
import su.onno.metadata.DefaultNamingStrategy;
import su.onno.metadata.MetadataRegistry;
import su.onno.metadata.MetadataScanner;
import su.onno.model.CatalogObject;
import su.onno.types.Ref;
import su.onno.ui.divkit.SurfaceDivBuilder;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Calendar and timeline list views (#428): {@code ListSpec.calendar}/{@code timeline} resolve their
 * getter references to row keys + data columns, validate that the range fields are date/time
 * columns and the lane is a single-target reference, and travel in the {@code onno-list}
 * descriptor. A misconfigured view degrades to "no such view", like the map view.
 */
class ListScheduleViewsTest {

    @Catalog(name = "ScheduleDoctors")
    static class Doctor extends CatalogObject {
    }

    @Catalog(name = "ScheduleVisits")
    static class Visit extends CatalogObject {
        @Attribute
        private LocalDateTime startsAt;
        @Attribute
        private LocalDateTime endsAt;
        @Attribute
        private LocalDate followUp;
        @Attribute(displayName = "Doctor")
        private Ref<Doctor> doctor;
        @Attribute(length = 80)
        private String patient;

        public LocalDateTime getStartsAt() { return startsAt; }
        public LocalDateTime getEndsAt() { return endsAt; }
        public LocalDate getFollowUp() { return followUp; }
        public Ref<Doctor> getDoctor() { return doctor; }
        public String getPatient() { return patient; }
    }

    private final MetadataScanner scanner = new MetadataScanner(new DefaultNamingStrategy());
    private final CatalogDescriptor visits = scanner.scan(Visit.class);

    private ResolvedListView resolve(ListSpec<Visit> spec) {
        MetadataRegistry registry = new MetadataRegistry();
        registry.registerCatalog(scanner.scan(Doctor.class));
        registry.registerCatalog(visits);
        ResolvedMetadataService metadata =
                new ResolvedMetadataService(registry, new FieldHintResolver(List.of()));
        return new UiViewResolver(metadata, List.of()).catalogList(visits, spec);
    }

    @Test
    void calendarResolvesGetterReferencesToRowKeysAndColumns() {
        ListSpec<Visit> spec = new ListSpec<>();
        spec.calendar(Visit::getStartsAt, Visit::getEndsAt)
                .title(Visit::getPatient).colorBy(Visit::getDoctor)
                .defaultMode(ListSpec.CalendarMode.WEEK).defaultView();

        ResolvedListView.CalendarView calendar = resolve(spec).calendarView();

        assertThat(calendar).isNotNull();
        assertThat(calendar.start()).isEqualTo(new ResolvedListView.ViewField("startsAt", "starts_at"));
        assertThat(calendar.end()).isEqualTo(new ResolvedListView.ViewField("endsAt", "ends_at"));
        assertThat(calendar.title().key()).isEqualTo("patient");
        assertThat(calendar.color().key()).isEqualTo("doctor");
        assertThat(calendar.mode()).isEqualTo("week");
        assertThat(calendar.defaultView()).isTrue();
    }

    @Test
    void calendarWithOnlyAStartFieldHasNoEnd() {
        ListSpec<Visit> spec = new ListSpec<>();
        spec.calendar(Visit::getFollowUp);

        ResolvedListView.CalendarView calendar = resolve(spec).calendarView();

        assertThat(calendar.start().key()).isEqualTo("followUp");
        assertThat(calendar.end().isSet()).isFalse();
        assertThat(calendar.mode()).isEqualTo("month");
        assertThat(calendar.defaultView()).isFalse();
    }

    @Test
    void calendarOnANonTemporalOrUnknownStartIsDropped() {
        ListSpec<Visit> text = new ListSpec<>();
        text.calendar(Visit::getPatient);
        ListSpec<Visit> unknown = new ListSpec<>();
        unknown.calendar("nope", null);

        assertThat(resolve(text).calendarView()).isNull();
        assertThat(resolve(unknown).calendarView()).isNull();
        assertThat(resolve(new ListSpec<>()).calendarView()).isNull();
    }

    @Test
    void timelineResolvesItsLaneToTheReferenceTarget() {
        ListSpec<Visit> spec = new ListSpec<>();
        spec.timeline(Visit::getStartsAt, Visit::getEndsAt, Visit::getDoctor)
                .title(Visit::getPatient).slotMinutes(10).hours(8, 21).defaultView();

        ResolvedListView.TimelineView timeline = resolve(spec).timelineView();

        assertThat(timeline).isNotNull();
        assertThat(timeline.start().column()).isEqualTo("starts_at");
        assertThat(timeline.end().column()).isEqualTo("ends_at");
        assertThat(timeline.lane().key()).isEqualTo("doctor");
        assertThat(timeline.laneKind()).isEqualTo("catalogs");
        assertThat(timeline.laneTarget()).isEqualTo("ScheduleDoctors");
        assertThat(timeline.laneLabel()).isEqualTo("Doctor");
        assertThat(timeline.slotMinutes()).isEqualTo(10);
        assertThat(timeline.startHour()).isEqualTo(8);
        assertThat(timeline.endHour()).isEqualTo(21);
        assertThat(timeline.defaultView()).isTrue();
    }

    @Test
    void timelineWithoutALaneOrWithANonReferenceLaneShowsOneLane() {
        ListSpec<Visit> none = new ListSpec<>();
        none.timeline(Visit::getStartsAt, Visit::getEndsAt);
        ListSpec<Visit> text = new ListSpec<>();
        text.timeline(Visit::getStartsAt, Visit::getEndsAt, Visit::getPatient);

        assertThat(resolve(none).timelineView().lane().isSet()).isFalse();
        ResolvedListView.TimelineView degraded = resolve(text).timelineView();
        assertThat(degraded).isNotNull();
        assertThat(degraded.lane().isSet()).isFalse();
        assertThat(degraded.laneTarget()).isEmpty();
    }

    @Test
    void timelineNeedsBothEdgesToBeTemporal() {
        ListSpec<Visit> spec = new ListSpec<>();
        spec.timeline("startsAt", "patient", null);

        assertThat(resolve(spec).timelineView()).isNull();
    }

    @Test
    @SuppressWarnings("unchecked")
    void bothViewsTravelInTheListDescriptor() {
        ListSpec<Visit> spec = new ListSpec<>();
        spec.calendar(Visit::getStartsAt, Visit::getEndsAt).title(Visit::getPatient);
        spec.timeline(Visit::getStartsAt, Visit::getEndsAt, Visit::getDoctor).slotMinutes(15);

        Map<String, Object> descriptor = SurfaceDivBuilder.listDescriptor(
                resolve(spec), "catalogs", "schedule_visits", null, true, List.of(), List.of());

        Map<String, Object> calendar = (Map<String, Object>) descriptor.get("calendar");
        assertThat(calendar).containsEntry("startField", "startsAt").containsEntry("startColumn", "starts_at")
                .containsEntry("endField", "endsAt").containsEntry("titleField", "patient")
                .containsEntry("colorField", "").containsEntry("mode", "month")
                .containsEntry("defaultView", false);
        Map<String, Object> timeline = (Map<String, Object>) descriptor.get("timeline");
        assertThat(timeline).containsEntry("laneField", "doctor").containsEntry("laneColumn", "doctor")
                .containsEntry("laneKind", "catalogs").containsEntry("laneTarget", "ScheduleDoctors")
                .containsEntry("slotMinutes", 15).containsEntry("startHour", 0).containsEntry("endHour", 24);
    }

    @Test
    void aListWithoutEitherViewEmitsNeitherKey() {
        Map<String, Object> descriptor = SurfaceDivBuilder.listDescriptor(
                resolve(new ListSpec<>()), "catalogs", "schedule_visits", null, true, List.of(), List.of());

        assertThat(descriptor).doesNotContainKeys("calendar", "timeline");
    }
}
