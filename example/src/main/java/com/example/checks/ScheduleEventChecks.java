package com.example.checks;

import com.example.domain.documents.ScheduleEvent;
import com.example.domain.documents.ScheduleParticipant;
import com.example.repositories.ScheduleEventRepository;
import su.onno.validation.CheckContext;
import su.onno.validation.CheckSpec;
import su.onno.validation.Checks;
import su.onno.validation.Feedback;

import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Live (as-you-type) checks for a team event: the schedule-conflict preview re-runs whenever the
 * time window or a participant changes and warns about overlapping events. Advisory only — the
 * save-time business rules on {@link ScheduleEvent} stay authoritative.
 */
@Component
public class ScheduleEventChecks implements Checks<ScheduleEvent> {

    private final ScheduleEventRepository schedules;

    public ScheduleEventChecks(ScheduleEventRepository schedules) {
        this.schedules = schedules;
    }

    @Override
    public Class<ScheduleEvent> entity() {
        return ScheduleEvent.class;
    }

    @Override
    public void define(CheckSpec<ScheduleEvent> v) {
        v.check("schedule-conflicts", this::conflicts)
                .dependsOn(ScheduleEvent::getStartsAt, ScheduleEvent::getEndsAt)
                .andDependsOn(ScheduleEvent::getParticipants, ScheduleParticipant::getEmployee)
                .debounce(Duration.ofMillis(200));
    }

    private List<Feedback> conflicts(CheckContext context) {
        LocalDateTime start = temporal(context.values().get("startsAt"));
        LocalDateTime end = temporal(context.values().get("endsAt"));
        if (start == null || end == null || !end.isAfter(start)) {
            return List.of(Feedback.error("endsAt", "End time must be after start time"));
        }
        long overlaps = schedules.findAllActive().stream()
                .filter(event -> context.id() == null || !context.id().equals(event.getId()))
                .filter(event -> overlaps(start, end, event))
                .count();
        if (overlaps == 0) {
            return List.of(Feedback.info("", "No schedule conflicts found"));
        }
        String message = "Time overlaps with " + overlaps
                + (overlaps == 1 ? " scheduled event" : " scheduled events");
        return List.of(
                Feedback.warning("startsAt", message),
                Feedback.info("participants.employee",
                        "Check availability statuses before adding participants"));
    }

    private static boolean overlaps(LocalDateTime start, LocalDateTime end, ScheduleEvent event) {
        return event.getStartsAt() != null && event.getEndsAt() != null
                && start.isBefore(event.getEndsAt()) && end.isAfter(event.getStartsAt());
    }

    private static LocalDateTime temporal(Object value) {
        if (value instanceof LocalDateTime dateTime) return dateTime;
        if (value == null || value.toString().isBlank()) return null;
        return LocalDateTime.parse(value.toString());
    }
}
