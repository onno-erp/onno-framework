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
 * Team-event record view demonstrating contextual picks and live validation. The entity is edited
 * from the calendar on {@link com.example.ui.pages.TeamPage}, not listed directly in navigation.
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
    }

    @Override
    public void fields(EntityConfigBuilder<ScheduleEvent> fields) {
        fields.field(ScheduleEvent::getNumber).label("Number")
                .field(ScheduleEvent::getDate).hideInForm()
                .field(ScheduleEvent::getSubject).order(0).label("Subject")
                .field(ScheduleEvent::getStartsAt).order(1).width("half").label("Starts at")
                .field(ScheduleEvent::getEndsAt).order(2).width("half").label("Ends at")
                .field(ScheduleEvent::isShowUnavailable).order(3).widget("switch")
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
