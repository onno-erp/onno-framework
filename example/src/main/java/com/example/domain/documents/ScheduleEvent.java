package com.example.domain.documents;

import su.onno.annotations.AccessControl;
import su.onno.annotations.Attribute;
import su.onno.annotations.Document;
import su.onno.annotations.TabularSection;
import su.onno.lifecycle.OnFillingHandler;
import su.onno.model.DocumentObject;
import su.onno.rules.BusinessRule;
import su.onno.rules.Validated;

import lombok.Getter;
import lombok.Setter;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;

/**
 * A bookstore staff event (inventory count, team meeting, floor coverage) whose participant picker
 * demonstrates contextual availability and sibling-row uniqueness. The UI prevents duplicates
 * early; {@link #rules()} still enforces the invariant on every write, including imports and direct
 * API clients.
 */
@Document(name = "Schedule Events", title = "Staff schedule event", numberPrefix = "EV-", context = "People")
@AccessControl(readRoles = {"MANAGER", "ADMIN"}, writeRoles = {"MANAGER", "ADMIN"})
@Getter
@Setter
public class ScheduleEvent extends DocumentObject implements OnFillingHandler, Validated {

    @Attribute(displayName = "Subject", required = true, length = 200)
    private String subject = "Team meeting";

    @Attribute(displayName = "Starts at", required = true)
    private LocalDateTime startsAt = LocalDate.now().plusDays(1).atTime(10, 0);

    @Attribute(displayName = "Ends at", required = true)
    private LocalDateTime endsAt = LocalDate.now().plusDays(1).atTime(12, 0);

    @Attribute(displayName = "Show unavailable employees")
    private boolean showUnavailable = true;

    @TabularSection(name = "participants")
    private List<ScheduleParticipant> participants = new ArrayList<>();

    @Override
    public void onFilling() {
        if (getDate() == null) {
            setDate(startsAt == null ? LocalDateTime.now() : startsAt);
        }
    }

    @Override
    public List<BusinessRule> rules() {
        return List.of(
                new BusinessRule("valid-interval", "End time must be after start time",
                        () -> startsAt != null && endsAt != null && endsAt.isAfter(startsAt)),
                new BusinessRule("unique-participants", "An employee cannot be added twice",
                        this::participantsAreUnique));
    }

    private boolean participantsAreUnique() {
        if (participants == null) {
            return true;
        }
        var ids = new HashSet<java.util.UUID>();
        return participants.stream()
                .filter(row -> row.getEmployee() != null)
                .allMatch(row -> ids.add(row.getEmployee().id()));
    }
}
