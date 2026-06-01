package com.example.ui.views;

import com.example.domain.catalogs.Employee;
import com.onec.ui.EntityConfigBuilder;
import com.onec.ui.EntityView;

import org.springframework.stereotype.Component;

/**
 * Declares the Employee catalog as a visible surface and its field order. Shown in
 * the default back-office nav and reused by the cleaning persona's "Team" section.
 */
@Component
public class EmployeeView implements EntityView {

    @Override
    public Class<?> entity() {
        return Employee.class;
    }

    @Override
    public void fields(EntityConfigBuilder f) {
        f.field("avatarUrl").order(-1).widget("avatar")
                .field("fullName").order(0)
                .field("role").order(1)
                .field("department").order(2)
                .field("hourlyRate").order(3)
                .field("hiredOn").order(4)
                .field("email").order(5)
                .field("mobile").order(6)
                .field("active").order(7);
    }
}
