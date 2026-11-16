package com.example.ui.views;

import com.example.domain.catalogs.Employee;
import com.example.domain.documents.ScheduleEvent;
import com.example.domain.documents.ScheduleParticipant;
import su.onno.ui.EntityConfigBuilder;
import su.onno.ui.EntityView;
import su.onno.ui.ListSpec;

import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * Team-event record view demonstrating contextual picks and live validation, plus the calendar and
 * timeline list views. The entity is edited from the calendar on
 * {@link com.example.ui.pages.TeamPage} or from its own list under People → Team events.
 */
@Component
public class ScheduleEventView implements EntityView<ScheduleEvent> {

    @Override
    public Class<ScheduleEvent> entity() {
        return ScheduleEvent.class;
    }

    @Override
    public void list(ListSpec<ScheduleEvent> list) {
        list.columns(ScheduleEvent::getNumber, ScheduleEvent::getSubject,
                        ScheduleEvent::getStartsAt, ScheduleEvent::getEndsAt)
                .label(ScheduleEvent::getNumber, "Number")
                .sortBy(ScheduleEvent::getStartsAt, false);
        // The same records as a month/week/day calendar and as a day scheduler with one lane per
        // organizer. Both read the list feed (search and filters apply); a MANAGER can drag an
        // event to reschedule it or into another organizer's lane — the write runs the
        // "valid-interval" rule like any other save.
        list.calendar(ScheduleEvent::getStartsAt, ScheduleEvent::getEndsAt)
                .title(ScheduleEvent::getSubject)
                .defaultMode(ListSpec.CalendarMode.WEEK);
        list.timeline(ScheduleEvent::getStartsAt, ScheduleEvent::getEndsAt, ScheduleEvent::getOrganizer)
                .title(ScheduleEvent::getSubject)
                .slotMinutes(30)
                .hours(7, 22);
    }

    @Override
    public void fields(EntityConfigBuilder<ScheduleEvent> fields) {
        fields.field(ScheduleEvent::getNumber).label("Number")
                .field(ScheduleEvent::getDate).hideInForm()
                .field(ScheduleEvent::getSubject).order(0).label("Subject")
                .field(ScheduleEvent::getStartsAt).order(1).width("half").label("Starts at")
                .field(ScheduleEvent::getEndsAt).order(2).width("half").label("Ends at")
                .field(ScheduleEvent::getOrganizer).order(3).label("Organizer")
                .field(ScheduleEvent::isShowUnavailable).order(4).widget("switch")
                    .label("Show unavailable employees");
        fields.rowRefField(ScheduleEvent::getParticipants, ScheduleParticipant::getEmployee)
                    .refSecondary(Employee::getEmail)
                    .label("Employee")
                    .refOptions(EmployeeAvailability.class)
                    .uniqueWithinSection();
        fields.rowField(ScheduleEvent::getParticipants, ScheduleParticipant::getResponsibility)
                .label("Responsibility");
        fields.validation("schedule-conflicts", ScheduleConflictPreview.class)
                .dependsOn(ScheduleEvent::getStartsAt, ScheduleEvent::getEndsAt)
                .andDependsOn(ScheduleEvent::getParticipants, ScheduleParticipant::getEmployee)
                .debounce(Duration.ofMillis(200));
    }
}
