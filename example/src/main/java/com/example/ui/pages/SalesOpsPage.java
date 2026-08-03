package com.example.ui.pages;

import com.example.domain.documents.Order;
import su.onno.ui.Page;
import su.onno.ui.PageBuilder;

import org.springframework.stereotype.Component;

/**
 * A second, standalone board at a custom route ({@code /ops}) — proof that a dashboard is not a
 * privileged singleton but just a {@link Page} at whatever route you choose. It is reachable because
 * {@link com.example.ui.layouts.MainLayout} links it in the nav ({@code section(...).page("/ops", ...)});
 * the framework serves any route that has a registered {@code Page} bean.
 *
 * <p>Declares no {@code profile()}, so it exists for every persona — a MANAGER and an ADMIN both open
 * it from the sidebar, each seeing the same working Orders list with its KPIs and status breakdown
 * collected in a right rail. The browser tab still reads "Sales Ops" from the nav label.</p>
 */
@Component
public class SalesOpsPage implements Page {

    @Override
    public String route() {
        return "/ops";
    }

    @Override
    public void compose(PageBuilder b) {
        b.title("Sales ops");
        b.subtitle("Whatever layout the page wants — columns, splits, nesting");

        // One working surface: the full list stays dominant while every summary sits together in
        // the right rail. The framework stacks the rail below the list on mobile.
        b.row(body -> {
            body.col("2/3", main -> main.list(Order.class));
            body.col("1/3", side -> {
                side.widget("Open orders").type("count").document(Order.class)
                    .config("metric", "count").config("filter", "open = true")
                    .hint("Orders not yet completed or cancelled.");
                side.widget("Posted revenue").type("count").document(Order.class)
                    .config("metric", "sum").metricField(Order::getTotal)
                    .config("filter", "posted = true").config("currency", "USD");
                side.widget("Total orders").type("count").document(Order.class)
                    .config("metric", "count");
                side.widget("Orders by status").type("chart").document(Order.class)
                    .config("kind", "pie").config("groupBy", "statusDisplay").config("metric", "count")
                    .hint("Where orders sit in the lifecycle.");
            });
        });
    }
}
