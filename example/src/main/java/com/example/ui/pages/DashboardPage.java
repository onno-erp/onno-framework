package com.example.ui.pages;

import com.example.domain.documents.Order;
import su.onno.ui.Page;
import su.onno.ui.PageBuilder;

import org.springframework.stereotype.Component;

/**
 * The home dashboard ({@code /}) — an at-a-glance board for the shop. Pinned to the admin profile,
 * so it's ADMIN-only: onno has no per-page RBAC, so the gate is the profile (a MANAGER resolves to
 * the default profile, whose {@code /} is the Orders list, and this page does not exist there — see
 * {@link com.example.ui.layouts.AdminLayout}).
 *
 * <p>Everything is server-aggregated from the {@link Order} document — count tiles that honor a
 * {@code filter} (an AND-chain of {@code col op value}), charts that group by a column, and a recent
 * list. Tiles aggregate a single column, which is why the order carries the derived {@code open}
 * boolean: "open orders" is then a plain {@code open = true} count.</p>
 */
@Component
public class DashboardPage implements Page {

    @Override
    public String route() {
        return "/";
    }

    @Override
    public String profile() {
        return "admin";
    }

    @Override
    public void compose(PageBuilder b) {
        b.title("Dashboard");
        b.subtitle("Onno Books — orders and stock at a glance");

        // ---- KPI row ----------------------------------------------------------------------------
        b.widget("Open orders").type("count").width("1/3").order(0).document(Order.class)
                .config("metric", "count")
                .config("filter", "open = true")
                .hint("Orders not yet completed or cancelled.");

        b.widget("Total orders").type("count").width("1/3").order(1).document(Order.class)
                .config("metric", "count");

        b.widget("Revenue (posted)").type("count").width("1/3").order(2).document(Order.class)
                .config("metric", "sum").config("metricField", "total")
                // System columns carry the `_` prefix in widget configs (cf. `_date` below): the
                // posted flag is `_posted`, not `posted`.
                .config("filter", "_posted = true")
                .hint("Sum of order totals across posted orders.");

        // ---- Charts -----------------------------------------------------------------------------
        b.widget("Orders by status").type("chart").width("1/2").order(10).document(Order.class)
                .config("kind", "pie").config("groupBy", "status_display")
                .config("metric", "count")
                .hint("Where orders sit in the lifecycle.");

        // Posted-order revenue over time, with a time-range selector and a Day/Week/Month granularity
        // toggle (config("controls", …)).
        b.widget("Revenue by day").type("chart").width("1/2").order(11).document(Order.class)
                .config("kind", "area")
                .config("groupBy", "_date").config("groupByDate", "day")
                .config("metric", "sum").config("metricField", "total")
                .config("filter", "_posted = true")
                .config("controls", "range,granularity")
                .config("defaultRange", "90d")
                .hint("Posted-order revenue over time.");

        // Dual-axis: revenue (area, left axis, $) and order count (bars, right axis) on one chart —
        // two very different magnitudes read cleanly because each measure has its own Y axis.
        // config("measure2", …) adds the secondary measure.
        b.widget("Orders & revenue").type("chart").width("full").order(12).document(Order.class)
                .config("kind", "area").config("label", "Revenue")
                .config("groupBy", "_date").config("groupByDate", "week")
                .config("metric", "sum").config("metricField", "total")
                .config("measure2", "count").config("kind2", "bar").config("label2", "Orders")
                .config("filter", "_posted = true")
                .config("controls", "range,granularity")
                .config("defaultRange", "90d")
                .hint("Weekly posted revenue (left axis) against order count (right axis).");

        // ---- Recent orders ----------------------------------------------------------------------
        b.widget("Recent orders").type("list").width("full").order(20).document(Order.class).maxItems(10)
                .config("titleTemplate", "{_number} · {customer_display}")
                .config("secondaryField", "status_display");
    }
}
