package com.example.ui.views;

import com.example.domain.documents.ScheduleEvent;
import su.onno.ui.EntityConfigBuilder;
import su.onno.ui.EntityView;
import su.onno.ui.ListSpec;

import org.springframework.stereotype.Component;

import java.time.Duration;

/** Scheduling surface used to demonstrate issue #272 end to end. */
@Component
public class ScheduleEventView implements EntityView {

    @Override
    public Class<?> entity() {
        return ScheduleEvent.class;
    }

    @Override
    public void list(ListSpec list) {
        list.columns("number", "subject", "startsAt", "endsAt")
                .label("number", "Номер")
                .sortBy("startsAt", false);
    }

    @Override
    public void fields(EntityConfigBuilder fields) {
        fields.field("number").label("Номер")
                .field("date").hideInForm()
                .field("subject").order(0).label("Название")
                .field("startsAt").order(1).width("half").label("Начало")
                .field("endsAt").order(2).width("half").label("Окончание")
                .field("showUnavailable").order(3).widget("switch")
                    .label("Показывать занятых сотрудников")
                .field("participants.employee")
                    .label("Сотрудник")
                    .refSecondary("email")
                    .refOptions(EmployeeAvailability.class)
                    .uniqueWithinSection()
                .field("participants.responsibility").label("Роль на событии");
        fields.validation("schedule-conflicts", ScheduleConflictPreview.class)
                .dependsOn("startsAt", "endsAt", "participants.employee")
                .debounce(Duration.ofMillis(200));
    }
}
