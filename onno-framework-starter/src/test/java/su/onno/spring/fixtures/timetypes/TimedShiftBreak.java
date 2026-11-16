package su.onno.spring.fixtures.timetypes;

import su.onno.annotations.Attribute;
import su.onno.model.TabularSectionRow;

import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.time.LocalTime;

@Getter
@Setter
public class TimedShiftBreak extends TabularSectionRow {

    @Attribute
    private LocalTime at;

    @Attribute
    private Instant takenAt;
}
