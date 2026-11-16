package su.onno.spring.fixtures.timetypes;

import su.onno.annotations.Attribute;
import su.onno.annotations.Catalog;
import su.onno.annotations.TabularSection;
import su.onno.model.CatalogObject;

import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;

/** A catalog with {@code LocalTime} / {@code Instant} attributes, header and tabular rows (#548). */
@Catalog(name = "TimedShifts", codeLength = 9, autoNumber = false)
@Getter
@Setter
public class TimedShift extends CatalogObject {

    @Attribute
    private LocalTime startsAt;

    @Attribute
    private Instant openedAt;

    @TabularSection(name = "breaks")
    private List<TimedShiftBreak> breaks = new ArrayList<>();
}
