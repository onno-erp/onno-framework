package com.example;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * "Rentals ERP" — the reference application for onno-framework. It models a small holiday-rental
 * business by declaring typed Java metadata; the framework generates the schema, REST API, DivKit
 * UI, MCP tools, and migration history from it. Nothing here hand-writes tables, DTOs, or CRUD
 * controllers.
 *
 * <p>The domain, read in dependency order, lives under {@code com.example}:</p>
 * <ul>
 *   <li>{@code domain.catalogs} — master data ({@code @Catalog}): Properties, Clients, Employees,
 *       Bank Accounts, Countries, plus the Clinic/Doctor relationship demo.</li>
 *   <li>{@code domain.enumerations} — code-controlled fixed lists ({@code @Enumeration}).</li>
 *   <li>{@code domain.documents} — business events ({@code @Document}): Bookings, Bills, Payments;
 *       {@code Guest} is a tabular-section row inside Booking.</li>
 *   <li>{@code domain.registers} — the balances and turnovers that posting writes into
 *       ({@code @AccumulationRegister}): Occupancy, Revenue, Receivables, Bank Balance.</li>
 *   <li>{@code domain.constants} — single global settings ({@code @Constant}).</li>
 *   <li>{@code jobs} — a scheduled background procedure gated on a constant.</li>
 *   <li>{@code seed} — first-launch demo data ({@link com.example.seed.RentalSeeder}).</li>
 *   <li>{@code ui} — the UI authored as beans: {@code Layout} shells, {@code Page} dashboards,
 *       per-entity {@code EntityView}s. An entity is only visible if it has an EntityView.</li>
 * </ul>
 *
 * <p>{@code @SpringBootApplication} sets the component-scan root; the framework scans this package
 * for the annotated metadata above. Run it (see the project README / running-the-example notes),
 * then sign in at <a href="http://localhost:8080">localhost:8080</a> with one of the demo users in
 * {@code application.yaml} (e.g. {@code admin}/{@code admin}).</p>
 */
@SpringBootApplication
public class ExampleApp {

    public static void main(String[] args) {
        SpringApplication.run(ExampleApp.class, args);
    }
}
