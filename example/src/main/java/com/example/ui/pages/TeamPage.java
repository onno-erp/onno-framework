package com.example.ui.pages;

import com.example.domain.catalogs.Employee;
import com.example.domain.documents.ScheduleEvent;
import su.onno.ui.Page;
import su.onno.ui.PageBuilder;

import org.springframework.stereotype.Component;

/**
 * Team workspace at {@code /team}. Events are calendar-shaped work, so the sidebar leads here
 * instead of exposing the raw {@link ScheduleEvent} document list. Opening a calendar event still
 * uses the generated detail/form surface, preserving participant availability and conflict checks.
 */
@Component
public class TeamPage implements Page {

    @Override
    public String route() {
        return "/team";
    }

    @Override
    public void compose(PageBuilder b) {
        b.title("Team");
        b.subtitle("Upcoming events and staff availability");

        b.actions("Team events", actions -> actions
                .action("newEvent")
                .label("New team event")
                .icon("calendar-plus")
                .roles("MANAGER", "ADMIN")
                .navigate("onno://documents/schedule_events/new"));

        b.row(body -> {
            body.col("2/3", schedule -> schedule
                    .widget("Upcoming team events")
                    .type("calendar")
                    .document(ScheduleEvent.class)
                    .dateField(ScheduleEvent::getStartsAt)
                    .titleField(ScheduleEvent::getSubject)
                    .endDateField(ScheduleEvent::getEndsAt)
                    .config("allDay", "false")
                    .config("secondaryField", "number")
                    .hint("Open an event to manage participants; drag it to reschedule."));
            body.col("1/3", staff -> staff.list(Employee.class));
        });
    }
}
