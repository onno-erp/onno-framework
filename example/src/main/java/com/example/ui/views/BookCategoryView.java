package com.example.ui.views;

import com.example.domain.catalogs.BookCategory;
import su.onno.ui.EntityConfigBuilder;
import su.onno.ui.EntityView;
import su.onno.ui.ListSpec;

import org.springframework.stereotype.Component;

/** The categories catalog — the simplest view: a code and a name. */
@Component
public class BookCategoryView implements EntityView {

    @Override
    public Class<?> entity() {
        return BookCategory.class;
    }

    @Override
    public void list(ListSpec list) {
        list.columns("code", "description")
                .label("description", "Name")
                .sortBy("description", false);
    }

    @Override
    public void fields(EntityConfigBuilder f) {
        f.field("description").order(0).label("Name");
    }
}
