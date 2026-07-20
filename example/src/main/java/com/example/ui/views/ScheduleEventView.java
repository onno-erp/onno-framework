package com.example.ui.views;

import com.example.domain.documents.ScheduleEvent;
import su.onno.ui.EntityConfigBuilder;
import su.onno.ui.EntityView;
import su.onno.ui.ListSpec;

import org.springframework.stereotype.Component;

import java.time.Duration;

/** Bookstore staff scheduling surface demonstrating contextual picks and live validation. */
@Component
public class ScheduleEventView implements EntityView {

    @Override
    public Class<?> entity() {
        return ScheduleEvent.class;
    }

    @Override
    public void list(ListSpec list) {
        list.columns("number", "subject", "startsAt", "endsAt")
                .label("number", "Number")
                .sortBy("startsAt", false);
    }

    @Override
    public void fields(EntityConfigBuilder fields) {
        fields.field("number").label("Number")
                .field("date").hideInForm()
                .field("subject").order(0).label("Subject")
                .field("startsAt").order(1).width("half").label("Starts at")
                .field("endsAt").order(2).width("half").label("Ends at")
                .field("showUnavailable").order(3).widget("switch")
                    .label("Show unavailable employees")
                .field("participants.employee")
                    .label("Employee")
                    .refSecondary("email")
                    .refOptions(EmployeeAvailability.class)
                    .uniqueWithinSection()
                .field("participants.responsibility").label("Responsibility");
        fields.validation("schedule-conflicts", ScheduleConflictPreview.class)
                .dependsOn("startsAt", "endsAt", "participants.employee")
                .debounce(Duration.ofMillis(200));
    }
}
