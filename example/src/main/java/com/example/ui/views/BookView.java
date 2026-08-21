package com.example.ui.views;

import com.example.domain.catalogs.Book;
import com.example.domain.catalogs.Supplier;
import su.onno.ui.EntityConfigBuilder;
import su.onno.ui.EntityView;
import su.onno.ui.ListSpec;

import org.springframework.stereotype.Component;

/**
 * The books catalog. Relabels the inherited {@code description} column to "Title", formats the price
 * as money, and renders the cover as an image upload (streamed to {@code POST /api/media}).
 */
@Component
public class BookView implements EntityView<Book> {

    @Override
    public Class<Book> entity() {
        return Book.class;
    }

    @Override
    public void list(ListSpec<Book> list) {
        list.columns(Book::getDescription, Book::getAuthor, Book::getCategory,
                        Book::getPrice, Book::getSupplier)
                .label(Book::getDescription, "Title")
                .sortBy(Book::getDescription, false)
                // Shelf views: group by category or supplier, with the average price per group.
                .groupable(Book::getCategory, Book::getSupplier)
                .aggregate(Book::getPrice, ListSpec.Agg.AVG, "Avg price");
        // Typeahead facets for the high-cardinality columns: author matches anywhere in the
        // name, ISBN anchors at the start (scan the prefix off a barcode).
        list.filter(Book::getAuthor).label("Author").contains();
        list.filter(Book::getIsbn).label("ISBN").startsWith();
        // A Table ⇄ Shelf toggle: the "bookTiles" renderer (src/main/widgets/BookTiles.tsx) draws
        // the same searched/filtered/sorted rows as cover tiles.
        list.custom("bookTiles").label("Shelf").defaultView();
    }

    @Override
    public void fields(EntityConfigBuilder<Book> f) {
        f.field(Book::getDescription).order(0).label("Title")
            .field(Book::getAuthor).order(1)
            .field(Book::getIsbn).order(2)
            .field(Book::getCategory).order(3);
        f.refField(Book::getSupplier).refSecondary(Supplier::getEmail).order(4)
            .field(Book::getPrice).order(5).format("currency:USD")
            .field(Book::getCoverUrl).order(6).label("Cover").widget("image")
                .hint("Upload a cover image.");
    }
}
