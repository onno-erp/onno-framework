package com.example.ui.views;

import com.example.domain.catalogs.Employee;
import su.onno.ui.EntityConfigBuilder;
import su.onno.ui.EntityView;
import su.onno.ui.ListSpec;

import org.springframework.stereotype.Component;

/** The employees catalog (ADMIN-only writes; see the catalog's @AccessControl). */
@Component
public class EmployeeView implements EntityView {

    @Override
    public Class<?> entity() {
        return Employee.class;
    }

    @Override
    public void list(ListSpec list) {
        list.columns("avatarUrl", "description", "email", "position")
                .label("description", "Name")
                .label("avatarUrl", "")
                .sortBy("description", false);
    }

    @Override
    public void fields(EntityConfigBuilder f) {
        f.field("description").order(0).label("Name")
            .field("email").order(1)
            .field("position").order(2)
            // The avatar widget marks this as the staff photo: the framework reads it for the
            // signed-in person's shell account block and for comment-author avatars.
            .field("avatarUrl").order(3).label("Photo").widget("avatar")
                .hint("Link to a staff photo.");
    }
}
