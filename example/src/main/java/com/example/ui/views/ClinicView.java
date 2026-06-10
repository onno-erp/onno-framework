package com.example.ui.views;

import com.example.domain.catalogs.ClinicDoctor;
import com.example.domain.catalogs.Clinic;
import com.onec.ui.EntityConfigBuilder;
import com.onec.ui.EntityView;

import org.springframework.stereotype.Component;

/**
 * The Clinic editor. Beyond its own fields it hosts an inline <em>related-list</em> panel of the
 * clinic's doctors, backed by the {@link ClinicDoctor} join catalog: {@code via("clinic")} scopes
 * rows to this clinic and {@code display("doctor")} is the doctor picked/shown per row. The
 * symmetric panel on {@code DoctorView} reads the same join rows from the other direction.
 */
@Component
public class ClinicView implements EntityView {

    @Override
    public Class<?> entity() {
        return Clinic.class;
    }

    @Override
    public void fields(EntityConfigBuilder f) {
        f.field("address").order(0)
                .field("city").order(1)
                .field("phone").order(2);

        f.relatedList("doctors", ClinicDoctor.class)
                .via("clinic")          // Ref<Clinic> that scopes join rows to this record
                .display("doctor")      // Ref<Doctor> shown per row (resolved to its description)
                .columns("doctor", "role")
                .label("Doctors");
    }
}
