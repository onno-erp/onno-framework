package com.example.ui.views;

import com.example.domain.catalogs.BookCategory;
import su.onno.ui.EntityConfigBuilder;
import su.onno.ui.EntityView;
import su.onno.ui.ListSpec;

import org.springframework.stereotype.Component;

/** The categories catalog — the simplest view: a code and a name. */
@Component
public class BookCategoryView implements EntityView<BookCategory> {

    @Override
    public Class<BookCategory> entity() {
        return BookCategory.class;
    }

    @Override
    public void list(ListSpec<BookCategory> list) {
        list.columns(BookCategory::getCode, BookCategory::getDescription)
                .label(BookCategory::getDescription, "Name")
                .sortBy(BookCategory::getDescription, false);
    }

    @Override
    public void fields(EntityConfigBuilder<BookCategory> f) {
        f.field(BookCategory::getDescription).order(0).label("Name");
    }
}
