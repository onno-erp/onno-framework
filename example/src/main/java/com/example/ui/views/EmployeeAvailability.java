package com.example.ui.views;

import com.example.domain.documents.ScheduleEvent;
import com.example.repositories.ScheduleEventRepository;
import su.onno.ui.RefOption;
import su.onno.ui.RefOptionContext;
import su.onno.ui.RefOptionDecoration;
import su.onno.ui.RefOptionDecorator;
import su.onno.ui.RefOptionTone;

import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Contextual participant availability for the scheduling example. One repository read decorates
 * the whole capped option page; the current document is excluded while editing.
 */
@Component
public class EmployeeAvailability implements RefOptionDecorator {

    private final ScheduleEventRepository schedules;

    public EmployeeAvailability(ScheduleEventRepository schedules) {
        this.schedules = schedules;
    }

    @Override
    public Map<UUID, RefOptionDecoration> decorate(
            RefOptionContext context, List<RefOption> options) {
        LocalDateTime startsAt = temporal(context.formValues().get("startsAt"));
        LocalDateTime endsAt = temporal(context.formValues().get("endsAt"));
        if (startsAt == null || endsAt == null || !endsAt.isAfter(startsAt)) {
            Map<UUID, RefOptionDecoration> waiting = new LinkedHashMap<>();
            for (RefOption option : options) {
                waiting.put(option.id(), RefOptionDecoration.badge(
                        "Choose a time", RefOptionTone.WARNING));
            }
            return waiting;
        }

        Map<UUID, String> conflicts = new LinkedHashMap<>();
        for (ScheduleEvent event : schedules.findAllActive()) {
            if (context.documentId() != null && context.documentId().equals(event.getId())) {
                continue;
            }
            if (!overlaps(startsAt, endsAt, event.getStartsAt(), event.getEndsAt())) {
                continue;
            }
            event.getParticipants().stream()
                    .filter(row -> row.getEmployee() != null)
                    .forEach(row -> conflicts.putIfAbsent(
                            row.getEmployee().id(),
                            "Conflicts with " + event.getNumber() + " · " + event.getSubject()));
        }

        Map<UUID, RefOptionDecoration> result = new LinkedHashMap<>();
        boolean showUnavailable = !Boolean.FALSE.equals(context.formValues().get("showUnavailable"));
        for (RefOption option : options) {
            String conflict = conflicts.get(option.id());
            if (conflict != null && !showUnavailable) {
                result.put(option.id(), RefOptionDecoration.filteredOut());
            } else {
                result.put(option.id(), conflict == null
                        ? RefOptionDecoration.badge("Available", RefOptionTone.SUCCESS)
                        : RefOptionDecoration.disabled(
                                "Unavailable", RefOptionTone.DANGER, conflict));
            }
        }
        return result;
    }

    private static boolean overlaps(LocalDateTime start, LocalDateTime end,
                                    LocalDateTime otherStart, LocalDateTime otherEnd) {
        return otherStart != null && otherEnd != null
                && start.isBefore(otherEnd) && end.isAfter(otherStart);
    }

    private static LocalDateTime temporal(Object value) {
        if (value instanceof LocalDateTime dateTime) {
            return dateTime;
        }
        if (value == null || value.toString().isBlank()) {
            return null;
        }
        return LocalDateTime.parse(value.toString());
    }
}
