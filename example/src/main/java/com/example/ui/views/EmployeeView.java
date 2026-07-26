package com.example.ui.views;

import com.example.domain.catalogs.Employee;
import su.onno.ui.EntityConfigBuilder;
import su.onno.ui.EntityView;
import su.onno.ui.ListSpec;

import org.springframework.stereotype.Component;

/** The employees catalog (ADMIN-only writes; see the catalog's @AccessControl). */
@Component
public class EmployeeView implements EntityView<Employee> {

    @Override
    public Class<Employee> entity() {
        return Employee.class;
    }

    @Override
    public void list(ListSpec<Employee> list) {
        list.columns(Employee::getAvatarUrl, Employee::getDescription,
                        Employee::getEmail, Employee::getPosition)
                .label(Employee::getDescription, "Name")
                .label(Employee::getAvatarUrl, "")
                .sortBy(Employee::getDescription, false);
        // Role facet: an enum field with no authored options offers every declared value,
        // labelled by its @EnumLabel.
        list.filter(Employee::getPosition).label("Role").multiOptions();
    }

    @Override
    public void fields(EntityConfigBuilder<Employee> f) {
        f.field(Employee::getDescription).order(0).label("Name")
            .field(Employee::getEmail).order(1)
            .field(Employee::getPosition).order(2)
            // The avatar widget marks this as the staff photo: the framework reads it for the
            // signed-in person's shell account block and for comment-author avatars.
            .field(Employee::getAvatarUrl).order(3).label("Photo").widget("avatar")
                .hint("Link to a staff photo.");
    }
}
