# Layout And Page Examples

## Table Of Contents

- Main Layout
- Admin Layout
- Dashboard Page
- Custom Route Page
- Default Route Override

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

If `Product` has an `EntityView` but is not listed in a section, `/catalogs/Products` can still work,
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
    public int priority() {
        return 100;
    }

    @Override
    public List<String> roles() {
        return List.of("ADMIN");
    }

    @Override
    public void configure(LayoutSpec spec) {
        spec.section("People").order(10).icon("users")
                .catalog(Employee.class);
        spec.section("System").order(20).icon("settings")
                .page("/settings", "Settings", "settings");
    }
}
```

Use profile layouts for curated role-specific experiences. Entity `@AccessControl` still gates data.

## Dashboard Page

```java
@Component
public class SalesDashboard implements Page {
    @Override
    public String route() {
        return "/sales";
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
                .config("filter", "_posted = true")
                .hint("Sum of posted order totals.");

        b.widget("Orders by status").type("chart").width("1/3").order(2)
                .document(SalesOrder.class)
                .config("kind", "pie")
                .config("groupBy", "status_display")
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
                        .widget("Stock alerts").type("list").register(StockRegister.class)
                        .config("filter", "quantity < 5")));
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
        return "/catalogs/Products";
    }

    @Override
    public void compose(PageBuilder b) {
        b.title("Products");
        b.aside(a -> a.widget("Low stock").type("list").register(StockRegister.class).maxItems(10));
        b.list(Product.class);
    }
}
```

A `Page` at a default entity route replaces the framework's default list/report surface.
