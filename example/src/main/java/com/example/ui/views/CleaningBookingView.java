package com.example.ui.views;

import com.onec.ui.ListSpec;

import org.springframework.stereotype.Component;

/**
 * A radically different booking list for the cleaning persona: a short,
 * task-focused table (which property, when to clean, who's assigned) instead of
 * the full back-office columns. Targets the "cleaning" profile, so cleaners get
 * this while everyone else gets {@link BookingView}. Reuses BookingView's entity
 * binding via plain Java inheritance.
 */
@Component
public class CleaningBookingView extends BookingView {

    @Override
    public String profile() {
        return "cleaning";
    }

    @Override
    public void list(ListSpec list) {
        list.title("Cleanings")
                .columns("property", "checkOut", "status", "assignedTo")
                .label("checkOut", "Check-out")
                .label("assignedTo", "Assigned to");
    }
}
