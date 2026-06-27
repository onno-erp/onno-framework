package com.example;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * "Onno Books" — the reference application for onno-framework. It models a small book retailer by
 * declaring typed Java metadata; the framework generates the schema, REST API, DivKit UI, and
 * migration history from it. Nothing here hand-writes tables, DTOs, or CRUD controllers.
 *
 * <p>It is deliberately <b>small and end-to-end</b>: one coherent domain (selling books and keeping
 * them in stock), the core starters only (framework + UI + auth), and every feature wired so it
 * actually works rather than half-scaffolded. Read in dependency order, under {@code com.example}:</p>
 * <ul>
 *   <li>{@code domain.catalogs} — master data ({@code @Catalog}): Books, Categories, Customers,
 *       Suppliers, Employees.</li>
 *   <li>{@code domain.enumerations} — code-controlled fixed lists ({@code @Enumeration}):
 *       {@code OrderStatus} (rendered as colored status pills), {@code Position}.</li>
 *   <li>{@code domain.documents} — business events ({@code @Document}): a customer {@code Order}
 *       (lines are {@code OrderLine} rows) and a {@code StockReceipt} (lines are
 *       {@code StockReceiptLine} rows).</li>
 *   <li>{@code domain.registers} — what posting writes into ({@code @AccumulationRegister}):
 *       {@code BookStock} (BALANCE, can't go negative) and {@code BookSales} (TURNOVER).</li>
 *   <li>{@code domain.constants} — a single global setting ({@code @Constant}): the store name.</li>
 *   <li>{@code seed} — first-launch demo data ({@link com.example.seed.BookstoreSeeder}).</li>
 *   <li>{@code ui} — the UI authored as beans: {@code Layout} shells (a manager profile and an
 *       admin profile), a {@code Page} dashboard, per-entity {@code EntityView}s.</li>
 * </ul>
 *
 * <p>Run it, then sign in at <a href="http://localhost:8080">localhost:8080</a> with a demo user from
 * {@code application.yaml} — {@code admin}/{@code admin} (dashboard + staff) or
 * {@code manager}/{@code manager} (runs the shop).</p>
 */
@SpringBootApplication
public class BookstoreApp {

    public static void main(String[] args) {
        SpringApplication.run(BookstoreApp.class, args);
    }
}
