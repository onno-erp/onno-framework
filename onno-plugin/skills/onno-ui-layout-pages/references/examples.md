# Layout And Page Examples

## Table Of Contents

- Main Layout
- Admin Layout
- Dashboard Page
- Custom Route Page
- Default Route Override
- Settings And Page Actions
- Reachability Verification

## Main Layout

```java
@Component
public class MainLayout implements Layout {
    @Override
    public void configure(LayoutSpec spec) {
        spec.shell()
                .nav(NavStyle.SIDEBAR)
                .brand("Acme ERP")
                .logo("/branding/logo.svg", "/branding/logo-dark.svg")
                .mark("/branding/mark.svg", "/branding/mark-dark.svg")
                .markFrame(false)
                .favicon("/branding/favicon.svg")
                .light(c -> c.primary("#2563EB").primarySoft("#DBEAFE"))
                .dark(c -> c.primary("#60A5FA").primarySoft("#172554"));

        spec.section("Sales").order(0).icon("shopping-cart")
                .document(SalesOrder.class)
                .catalog(Customer.class)
                .page("/sales", "Sales dashboard", "activity");

        spec.section("Inventory").order(1).icon("package")
                .document(GoodsReceipt.class)
                .register(StockRegister.class);

        spec.section("Catalog").order(2).icon("boxes")
                .catalog(Product.class)
                .catalog(ProductGroup.class);

        spec.identity(Employee.class, "email");
    }
}
```

If `Product` has an `EntityView` but is not listed in a section, `/catalogs/products` can still work,
but the sidebar will not show it.

## Admin Layout

```java
@Component
public class AdminLayout implements Layout {
    @Override
    public String profile() {
        return "admin";
    }

    @Override
    public void configure(LayoutSpec spec) {
        spec.roles("ADMIN").priority(100);
        spec.section("People").order(10).icon("users")
                .catalog(Employee.class);
        spec.section("System").order(20).icon("settings")
                .page("/settings", "Settings", "settings");
    }
}
```

Use profile layouts for curated role-specific experiences. Entity `@AccessControl` still gates data.
Named profiles replace rather than inherit the default navigation; share/repeat every desired
section. Keep shared shell branding and identity on the default layout.

## Dashboard Page

```java
@Component
public class SalesDashboard implements Page {
    @Override
    public String route() {
        return "/sales";
    }

    @Override
    public String profile() {
        return "sales";
    }

    @Override
    public void compose(PageBuilder b) {
        b.title("Sales");
        b.subtitle("Orders and revenue");

        b.widget("Time range").type("timeRange").width("full").order(-10)
                .config("presets", "24h,7d,30d,90d,all")
                .config("default", "30d");

        b.widget("Open orders").type("count").width("1/3").order(0)
                .document(SalesOrder.class)
                .config("metric", "count")
                .config("filter", "status != 'CANCELLED'");

        b.widget("Revenue").type("count").width("1/3").order(1)
                .document(SalesOrder.class)
                .config("metric", "sum")
                .config("metricField", "total")
                .config("filter", "posted = true")
                .hint("Sum of posted order totals.");

        b.widget("Orders by status").type("chart").width("1/3").order(2)
                .document(SalesOrder.class)
                .config("kind", "pie")
                .config("groupBy", "statusDisplay")
                .config("metric", "count");

        b.list(SalesOrder.class, v -> v
                .filter("status != 'CANCELLED'")
                .groupBy("status")
                .sort("date", true));
    }
}
```

## Custom Route Page

```java
@Component
public class OperationsPage implements Page {
    @Override
    public String route() {
        return "/ops";
    }

    @Override
    public void compose(PageBuilder b) {
        b.bare();
        b.row(r -> r
                .col("2/3", c -> c.list(SalesOrder.class))
                .col("1/3", c -> c
                        .widget("Stock alerts").type("stockAlerts").register(StockRegister.class)));
    }
}
```

Link it in a layout with `.page("/ops", "Operations", "activity")`.

## Default Route Override

```java
@Component
public class ProductsSurface implements Page {
    @Override
    public String route() {
        return "/catalogs/products";
    }

    @Override
    public void compose(PageBuilder b) {
        b.title("Products");
        b.aside(a -> a.widget("Low stock").type("stockAlerts")
                .register(StockRegister.class).maxItems(10));
        b.list(Product.class);
    }
}
```

A `Page` at a default entity route replaces the framework's default list/report surface.

## Settings And Page Actions

```java
@Component
public class SalesSettingsPage implements Page {
    @Override public String route() { return "/settings"; }
    @Override public String profile() { return "sales"; }

    @Override
    public void compose(PageBuilder b) {
        b.title("Sales settings");
        b.list(SalesPolicy.class);
        b.actions("Maintenance", actions -> actions.action("refresh-prices")
                .label("Refresh prices").roles("SALES_MANAGER")
                .handler(ctx -> refreshPrices()));
    }
}
```

Page actions have no entity authorization gate; declare `.roles(...)`. The built-in
`type("setting").config("constant", logicalName)` widget calls the ADMIN-only global settings API,
so use role-gated catalogs/actions for non-admin persona settings.

## Reachability Verification

- As each role, inspect `GET /api/divkit/shell?viewport=desktop` and confirm the selected profile,
  home route, and complete expected navigation.
- A profile-specific page returns `200` only for its matching active profile; a universal page has
  no `profile()` and is available to all profiles.
- Nav links do not validate page existence and do not authorize it. Verify each linked route and
  page action server-side; wrong action roles must return `403`.
- An entity with a view but no section is directly reachable but unlisted. A section entry without
  a matching active-profile view is filtered from nav and its DivKit route returns `404`.
- Fetch branding assets and assert their content type, because a missing asset can fall through to
  SPA HTML with status 200.
