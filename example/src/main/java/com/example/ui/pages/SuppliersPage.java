package com.example.ui.pages;

import com.example.domain.catalogs.Supplier;
import su.onno.ui.Page;
import su.onno.ui.PageBuilder;

import org.springframework.stereotype.Component;

/**
 * Overrides the default Suppliers list surface. The Suppliers nav entry routes to
 * {@code /catalogs/suppliers}; because a {@code Page} is registered at exactly that route, it renders
 * instead of the framework's auto-generated list — the entity list "is a page" you can compose.
 *
 * <p>The default (no page) is a plain catalog list. Here we keep that list — {@code b.list(Supplier.class)}
 * embeds the same interactive surface (New, search, sort, click-to-open) — but add a count tile above
 * it. Delete this bean and the route falls back to the identical bare list, so authoring a page is a
 * pure opt-in: you get the good default until you decide to shape it.</p>
 */
@Component
public class SuppliersPage implements Page {

    @Override
    public String route() {
        return "/catalogs/suppliers";
    }

    @Override
    public void compose(PageBuilder b) {
        b.title("Suppliers");
        b.subtitle("Who we buy stock from");

        b.widget("Total suppliers").type("count").width("1/3").order(0).catalog(Supplier.class)
                .config("metric", "count");

        b.list(Supplier.class);
    }
}
