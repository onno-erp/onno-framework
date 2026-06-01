package com.example.views;

import com.example.domain.documents.Booking;
import com.onec.ui.EntityView;
import com.onec.ui.ListSpec;

import org.springframework.stereotype.Component;

/**
 * Default booking list — the full back-office view. A profile-specific view
 * (see {@link CleaningBookingView}) can replace this for a persona.
 */
@Component
public class BookingView implements EntityView {

    @Override
    public Class<?> entity() {
        return Booking.class;
    }

    @Override
    public void list(ListSpec list) {
        list.columns("number", "date", "property", "checkIn", "checkOut", "primaryClient", "status", "totalGross")
                .label("primaryClient", "Guest")
                .label("totalGross", "Total");
    }
}
