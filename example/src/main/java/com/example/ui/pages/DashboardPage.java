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
 * <p>Everything is server-aggregated from the {@link Order} document — range-aware KPI stats that
 * compare with the preceding equal-length period, charts that group by a column, and a recent list.
 * The order carries the derived {@code open} boolean so "open orders" remains a plain
 * {@code open = true} count.</p>
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

        // Shared time picker — one range every chart on the board reads from (preset or absolute
        // From/To). A "timeRange" widget needs no entity; it just drives the dashboard window.
        // Quick-picks ("presets") and the starting window ("default") are configurable: each preset
        // is a duration id (<n><unit>, where m=minute and M=month, plus "all"); omit both for the
        // built-in ladder (15m…all) defaulting to the last 30 days.
        b.widget("Time range").type("timeRange").width("full").order(-10)
                .config("presets", "24h,7d,30d,90d,1y,all")
                .config("default", "30d");

        // ---- KPI row ----------------------------------------------------------------------------
        b.widget("Open orders").type("stat").width("1/3").order(0).document(Order.class).dateField("_date")
                .config("metric", "count")
                .config("trend", "false").config("comparison", "true")
                .config("comparisonLabel", "vs previous period")
                .config("filter", "open = true")
                .hint("Orders not yet completed or cancelled.");

        b.widget("Total orders").type("stat").width("1/3").order(1).document(Order.class).dateField("_date")
                .config("metric", "count")
                .config("trend", "false").config("comparison", "true")
                .config("comparisonLabel", "vs previous period");

        b.widget("Revenue (posted)").type("stat").width("1/3").order(2).document(Order.class).dateField("_date")
                .config("metric", "sum").config("metricField", "total")
                .config("currency", "USD")
                .config("trend", "false").config("comparison", "true")
                .config("comparisonLabel", "vs previous period")
                // System columns carry the `_` prefix in widget configs (cf. `_date` below): the
                // posted flag is `_posted`, not `posted`.
                .config("filter", "_posted = true")
                .hint("Sum of order totals across posted orders.");

        // ---- Charts -----------------------------------------------------------------------------
        b.widget("Orders by status").type("chart").width("1/2").order(10).document(Order.class)
                .config("kind", "pie").config("groupBy", "status_display")
                .config("metric", "count")
                .hint("Where orders sit in the lifecycle.");

        // Posted-order revenue over time. The window comes from the shared time picker above, and the
        // bucket size (day/week/month) auto-follows it.
        b.widget("Revenue by day").type("chart").width("1/2").order(11).document(Order.class)
                .config("kind", "area")
                .config("groupBy", "_date").config("groupByDate", "day")
                .config("metric", "sum").config("metricField", "total")
                .config("filter", "_posted = true")
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
                .hint("Weekly posted revenue (left axis) against order count (right axis).");

        // ---- Recent orders ----------------------------------------------------------------------
        b.widget("Recent orders").type("list").width("full").order(20).document(Order.class).maxItems(10)
                .config("titleTemplate", "{_number} · {customer_display}")
                .config("secondaryField", "status_display");

        // ---- Custom widget: a type the framework has no built-in for ---------------------------
        // "eventLog" has no server-side renderer — its React component ships as
        // example/src/main/widgets/EventLog.tsx, compiled by the su.onno.widgets Gradle plugin and
        // loaded into the SPA at boot. The .config(...) values arrive as widget.extraConfig.
        b.widget("Recent activity").type("eventLog").width("full").order(60).document(Order.class)
                .maxItems(10)
                .config("dateField", "_date").config("titleField", "_number")
                .config("secondaryDisplay", "customer_display")
                .config("amountField", "total").config("currency", "USD")
                .hint("A dev-authored widget — its renderer is a .tsx compiled by su.onno.widgets.");

        // ---- Embedded list on a default view ----------------------------------------------------
        // The full Orders board embedded in the page, but opened on a preset view: filtered to open
        // orders, grouped by status, newest first. The base filter is a server-side constraint; the
        // viewer can still regroup, resort, and add their own filters on top.
        b.list(Order.class, v -> v
                .filter("open = true")
                .groupBy("status")
                .sort("date", true));
    }
}
