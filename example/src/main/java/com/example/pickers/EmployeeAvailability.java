package com.example.pickers;

import com.example.domain.documents.ScheduleEvent;
import com.example.repositories.ScheduleEventRepository;
import su.onno.picker.PickerCandidate;
import su.onno.picker.PickerContext;
import su.onno.picker.PickerOption;
import su.onno.picker.PickerOptions;
import su.onno.picker.PickerTone;

import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Contextual participant availability for a team event's {@code participants.employee} picker:
 * each candidate is badged available/unavailable for the chosen window. One repository read
 * decorates the whole capped option page; the event being edited is excluded.
 */
@Component
public class EmployeeAvailability implements PickerOptions<ScheduleEvent> {

    private final ScheduleEventRepository schedules;

    public EmployeeAvailability(ScheduleEventRepository schedules) {
        this.schedules = schedules;
    }

    @Override
    public Class<ScheduleEvent> entity() {
        return ScheduleEvent.class;
    }

    @Override
    public String field() {
        return "participants.employee";
    }

    @Override
    public Map<UUID, PickerOption> decorate(PickerContext context, List<PickerCandidate> options) {
        LocalDateTime startsAt = temporal(context.formValues().get("startsAt"));
        LocalDateTime endsAt = temporal(context.formValues().get("endsAt"));
        if (startsAt == null || endsAt == null || !endsAt.isAfter(startsAt)) {
            Map<UUID, PickerOption> waiting = new LinkedHashMap<>();
            for (PickerCandidate option : options) {
                waiting.put(option.id(), PickerOption.badge(
                        "Choose a time", PickerTone.WARNING));
            }
            return waiting;
        }

        Map<UUID, String> conflicts = new LinkedHashMap<>();
        for (ScheduleEvent event : schedules.findAllActive()) {
            if (context.recordId() != null && context.recordId().equals(event.getId())) {
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

        Map<UUID, PickerOption> result = new LinkedHashMap<>();
        boolean showUnavailable = !Boolean.FALSE.equals(context.formValues().get("showUnavailable"));
        for (PickerCandidate option : options) {
            String conflict = conflicts.get(option.id());
            if (conflict != null && !showUnavailable) {
                result.put(option.id(), PickerOption.filteredOut());
            } else {
                result.put(option.id(), conflict == null
                        ? PickerOption.badge("Available", PickerTone.SUCCESS)
                        : PickerOption.disabled(
                                "Unavailable", PickerTone.DANGER, conflict));
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
