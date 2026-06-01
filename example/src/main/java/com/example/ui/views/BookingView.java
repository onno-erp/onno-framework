package com.example.ui.views;

import com.example.domain.documents.Booking;
import com.onec.ui.EntityConfigBuilder;
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

    @Override
    public void fields(EntityConfigBuilder f) {
        f.field("property").order(0)
                .field("status").order(1)
                .field("channel").order(2)
                .field("checkIn").order(3)
                .field("checkOut").order(4)
                .field("adults").order(5)
                .field("children").order(6)
                .field("nights").order(7).hideInForm()
                .field("nightRate").order(8)
                .field("cleaningFee").order(9)
                .field("totalGross").order(10).hideInForm()
                .field("summary").order(11).hideInForm()
                .field("primaryClient").order(12)
                .field("assignedTo").order(13)
                .field("notes").order(20);
    }
}
