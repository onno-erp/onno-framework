package com.example.domain.documents;

import jakarta.validation.constraints.NotNull;
import com.example.domain.catalogs.Employee;
import su.onno.annotations.Attribute;
import su.onno.model.TabularSectionRow;
import su.onno.types.Ref;

import lombok.Getter;
import lombok.Setter;

/** One staff member assigned to a scheduled event. */
@Getter
@Setter
public class ScheduleParticipant extends TabularSectionRow {

    @Attribute(displayName = "Employee")
    @NotNull
    private Ref<Employee> employee;

    @Attribute(displayName = "Responsibility", length = 120)
    private String responsibility;
}
