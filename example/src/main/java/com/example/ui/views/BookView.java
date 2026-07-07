package com.example.ui.views;

import com.example.domain.catalogs.Book;
import su.onno.ui.EntityConfigBuilder;
import su.onno.ui.EntityView;
import su.onno.ui.ListSpec;

import org.springframework.stereotype.Component;

/**
 * The books catalog. Relabels the inherited {@code description} column to "Title", formats the price
 * as money, and renders the cover as an image upload (streamed to {@code POST /api/media}).
 */
@Component
public class BookView implements EntityView {

    @Override
    public Class<?> entity() {
        return Book.class;
    }

    @Override
    public void list(ListSpec list) {
        list.columns("description", "author", "category", "price", "supplier")
                .label("description", "Title")
                .sortBy("description", false)
                // Shelf views: group by category or supplier, with the average price per group.
                .groupable("category", "supplier")
                .aggregate("price", ListSpec.Agg.AVG, "Avg price");
        // Typeahead facets for the high-cardinality columns: author matches anywhere in the
        // name, ISBN anchors at the start (scan the prefix off a barcode).
        list.filter("author").label("Author").contains();
        list.filter("isbn").label("ISBN").startsWith();
        // A Table ⇄ Shelf toggle: the "bookTiles" renderer (src/main/widgets/BookTiles.tsx) draws
        // the same searched/filtered/sorted rows as cover tiles.
        list.custom("bookTiles").label("Shelf");
    }

    @Override
    public void fields(EntityConfigBuilder f) {
        f.field("description").order(0).label("Title")
            .field("author").order(1)
            .field("isbn").order(2)
            .field("category").order(3)
            .field("supplier").order(4).refSecondary("email")
            .field("price").order(5).format("currency:USD")
            .field("coverUrl").order(6).label("Cover").widget("image")
                .hint("Upload a cover image.");
    }
}
