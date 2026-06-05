package com.example.domain.documents;

import com.example.domain.catalogs.Client;
import com.example.domain.catalogs.Employee;
import com.example.domain.catalogs.Property;
import com.example.domain.enumerations.BookingChannel;
import com.example.domain.enumerations.BookingStatus;
import com.example.domain.registers.OccupancyRegister;
import com.onec.annotations.AccessControl;
import com.onec.annotations.Attribute;
import com.onec.annotations.Document;
import com.onec.annotations.TabularSection;
import com.onec.lifecycle.BeforeWriteHandler;
import com.onec.lifecycle.Postable;
import com.onec.mail.template.MailTemplate;
import com.onec.model.DocumentObject;
import com.onec.posting.PostingContext;
import com.onec.rules.BusinessRule;
import com.onec.rules.Validated;
import com.onec.types.Ref;

import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

@Document(name = "Bookings", numberPrefix = "B-", numberLength = 14, context = "Rentals")
@AccessControl(readRoles = {"RENTALS", "CLEANER"}, writeRoles = {"RENTALS"})
@MailTemplate(name = "booking-confirmed",
        subject = "Your booking is confirmed",
        html = true)
@Getter
@Setter
public class Booking extends DocumentObject implements BeforeWriteHandler, Postable, Validated {

    @Attribute(required = true)
    private Ref<Property> property;

    @Attribute
    private BookingStatus status;

    @Attribute
    private BookingChannel channel;

    @Attribute(displayName = "Check-in", required = true)
    private LocalDate checkIn;

    @Attribute(displayName = "Check-out", required = true)
    private LocalDate checkOut;

    @Attribute(displayName = "Adults")
    private Integer adults;

    @Attribute(displayName = "Children")
    private Integer children;

    @Attribute(displayName = "Nights")
    private Integer nights;

    @Attribute(displayName = "Avg. price / night", precision = 12, scale = 2)
    private BigDecimal nightRate;

    @Attribute(displayName = "Cleaning fee", precision = 12, scale = 2)
    private BigDecimal cleaningFee;

    @Attribute(displayName = "Total (gross)", precision = 14, scale = 2)
    private BigDecimal totalGross;

    @Attribute(length = 200)
    private String summary;

    @Attribute(length = 1000)
    private String notes;

    @Attribute
    private Ref<Client> primaryClient;

    @Attribute(displayName = "Assigned to")
    private Ref<Employee> assignedTo;

    @TabularSection(name = "guests")
    private List<Guest> guests = new ArrayList<>();

    @Override
    public List<BusinessRule> rules() {
        return List.of(
                new BusinessRule("property-required", "Property is required", () -> property != null),
                // A checked-in booking is reported to SES.HOSPEDAJES as a parte de viajeros, which the
                // service rejects without a traveler. Block posting it before it can be submitted; the
                // per-field SES requirements (document, address, …) are enforced at submission time by
                // the hospedajes starter, which cannot resolve the client refs from here.
                new BusinessRule("checked-in-needs-traveler",
                        "A checked-in booking must have at least one guest (or a primary client)",
                        () -> status != BookingStatus.CHECKED_IN || hasIdentifiableTraveler()));
    }

    private boolean hasIdentifiableTraveler() {
        if (primaryClient != null) {
            return true;
        }
        return guests.stream().anyMatch(g -> g.getClient() != null);
    }

    @Override
    public void beforeWrite() {
        if (checkIn != null && checkOut != null && checkOut.isAfter(checkIn)) {
            this.nights = (int) ChronoUnit.DAYS.between(checkIn, checkOut);
        } else {
            this.nights = 0;
        }
        BigDecimal rate = nightRate != null ? nightRate : BigDecimal.ZERO;
        BigDecimal cleaning = cleaningFee != null ? cleaningFee : BigDecimal.ZERO;
        this.totalGross = rate.multiply(BigDecimal.valueOf(nights)).add(cleaning)
                .setScale(2, RoundingMode.HALF_UP);

        // status left as-is; null means "no explicit lifecycle state yet"

        // summary used by calendar/list widgets
        this.summary = (property != null ? "" : "") + (checkIn != null ? checkIn.toString() : "")
                + (nights != null && nights > 0 ? " (" + nights + "n)" : "");
    }

    @Override
    public void handlePosting(PostingContext context) {
        if (status == BookingStatus.CANCELED) {
            return;
        }
        var occupancy = context.movements(OccupancyRegister.class);
        occupancy.addReceipt(r -> {
            r.setProperty(property);
            r.setNights(nights == null ? BigDecimal.ZERO : BigDecimal.valueOf(nights));
            r.setAdults(adults == null ? BigDecimal.ZERO : BigDecimal.valueOf(adults));
            r.setChildren(children == null ? BigDecimal.ZERO : BigDecimal.valueOf(children));
        });
    }
}
