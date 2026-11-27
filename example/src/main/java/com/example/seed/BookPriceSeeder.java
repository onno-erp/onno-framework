package com.example.seed;

import com.example.domain.registers.BookPrice;
import su.onno.repository.InformationRegisterRepository;
import su.onno.repository.InformationRegisterRepositoryImpl;
import org.springframework.beans.factory.annotation.Qualifier;
import java.util.Map;
import com.example.repositories.BookRepository;
import su.onno.types.Ref;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;

/** Adds illustrative price history once, after the bookstore's ordinary data has been seeded. */
@Component
public class BookPriceSeeder {
    private final InformationRegisterRepository<BookPrice> prices;
    private final BookRepository books;

    @SuppressWarnings("unchecked")
    public BookPriceSeeder(@Qualifier("informationRegisterRepositoryMap")
            Map<Class<?>, InformationRegisterRepositoryImpl<?>> registers, BookRepository books) {
        this.prices = (InformationRegisterRepository<BookPrice>) registers.get(BookPrice.class);
        this.books = books;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void seed() {
        if (!prices.getRecords().isEmpty()) return;
        var today = LocalDate.now();
        for (var book : books.findAllActive()) {
            if (book.getPrice() == null) continue;
            for (int days : new int[] {30, 14, 0}) {
                var row = new BookPrice();
                row.setBook(Ref.of(com.example.domain.catalogs.Book.class, book.getId()));
                row.setPeriod(today.minusDays(days).atStartOfDay());
                row.setPrice(book.getPrice().multiply(days == 0 ? BigDecimal.ONE
                        : days == 14 ? new BigDecimal("0.95") : new BigDecimal("0.90"))
                        .setScale(2, RoundingMode.HALF_UP));
                prices.write(row);
            }
        }
    }
}
