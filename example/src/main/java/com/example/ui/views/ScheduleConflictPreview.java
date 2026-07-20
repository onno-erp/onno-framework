package com.example.ui.views;

import com.example.domain.documents.ScheduleEvent;
import com.example.repositories.ScheduleEventRepository;
import org.springframework.stereotype.Component;
import su.onno.ui.FormFeedback;
import su.onno.ui.FormValidationContext;
import su.onno.ui.FormValidator;

import java.time.LocalDateTime;
import java.util.List;

/** Live bookstore staffing-conflict preview; save-time business rules remain authoritative. */
@Component
public class ScheduleConflictPreview implements FormValidator {
    private final ScheduleEventRepository schedules;

    public ScheduleConflictPreview(ScheduleEventRepository schedules) {
        this.schedules = schedules;
    }

    @Override
    public List<FormFeedback> validate(FormValidationContext context) {
        LocalDateTime start = temporal(context.values().get("startsAt"));
        LocalDateTime end = temporal(context.values().get("endsAt"));
        if (start == null || end == null || !end.isAfter(start)) {
            return List.of(FormFeedback.error("endsAt", "End time must be after start time"));
        }
        long overlaps = schedules.findAllActive().stream()
                .filter(event -> context.id() == null || !context.id().equals(event.getId()))
                .filter(event -> overlaps(start, end, event))
                .count();
        if (overlaps == 0) {
            return List.of(FormFeedback.info("", "No schedule conflicts found"));
        }
        String message = "Time overlaps with " + overlaps
                + (overlaps == 1 ? " scheduled event" : " scheduled events");
        return List.of(
                FormFeedback.warning("startsAt", message),
                FormFeedback.info("participants.employee",
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
