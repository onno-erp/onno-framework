package com.example.ui.views;

import com.example.domain.catalogs.ClinicDoctor;
import com.example.domain.catalogs.Doctor;
import su.onno.ui.EntityConfigBuilder;
import su.onno.ui.EntityView;

import org.springframework.stereotype.Component;

/**
 * The Doctor editor — the mirror image of {@code ClinicView}. Its related-list panel shows the
 * clinics this doctor works at, reading the very same {@link ClinicDoctor} join rows but scoped
 * the other way: {@code via("doctor")} ties rows to this doctor, {@code display("clinic")} is the
 * clinic per row. One join catalog, two inline rosters, no duplicated relationship.
 */
@Component
public class DoctorView implements EntityView {

    @Override
    public Class<?> entity() {
        return Doctor.class;
    }

    @Override
    public void fields(EntityConfigBuilder f) {
        f.field("specialty").order(0)
                .field("email").order(1);

        f.relatedList("clinics", ClinicDoctor.class)
                .via("doctor")
                .display("clinic")
                .columns("clinic", "role")
                .label("Clinics");
    }
}
