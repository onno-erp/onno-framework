package com.example.ui.layouts;

import com.example.domain.catalogs.Book;
import com.example.domain.catalogs.BookCategory;
import com.example.domain.catalogs.Customer;
import com.example.domain.catalogs.Employee;
import com.example.domain.catalogs.Supplier;
import com.example.domain.documents.Order;
import com.example.domain.documents.StockReceipt;
import com.example.domain.registers.BookSales;
import com.example.domain.registers.BookStock;
import su.onno.ui.Layout;
import su.onno.ui.LayoutSpec;
import su.onno.ui.NavStyle;

import org.springframework.stereotype.Component;

/**
 * The back-office shell for everyone — the <b>default (manager) UI profile</b>. UI structure is
 * authored here as a bean (sidebar sections, branding, the login→Employee identity link), never as
 * annotations on the domain classes.
 *
 * <p>Nav is curated: an entity shows in the sidebar only because a section lists it here. This
 * profile carries no {@code roles()} restriction, so it's the baseline every signed-in user resolves
 * to — a MANAGER lands here, and the "People" section deliberately omits {@link Employee} (managing
 * staff is ADMIN-only; see {@link Employee}'s {@code @AccessControl} and {@link AdminLayout}). An
 * ADMIN, on the higher-priority admin profile, sees Employees and the dashboard instead. MANAGER
 * still has read access to {@link Employee} so the order "Assigned to" picker works — it just has no
 * nav entry here.</p>
 */
@Component
public class MainLayout implements Layout {

    @Override
    public void configure(LayoutSpec layout) {
        // No roles() here: the default profile is the baseline every user can resolve to. The admin
        // profile (AdminLayout) is additive and higher-priority, so an ADMIN lands there instead.
        build(layout, false);
    }

    /**
     * Builds the shared shell, branding, sections and identity link. {@code includeEmployees} adds the
     * {@link Employee} catalog to the "People" section — true only for the admin profile, since staff
     * management is ADMIN-only. Keeping both profiles on one builder stops their navs from drifting.
     */
    static void build(LayoutSpec layout, boolean includeEmployees) {
        // Branding configured in Java: app name, brand colors retinting the DivKit chrome accent in
        // light and dark modes, plus a logo/favicon served from src/main/resources/static/ui.
        layout.shell()
                .nav(NavStyle.SIDEBAR)
                .brand("Onno Books")
                .logo("/branding/logo.svg")
                .favicon("/branding/favicon.svg")
                .light(c -> c.primary("#4F46E5").primarySoft("#EEF2FF"))
                .dark(c -> c.primary("#6366F1").primarySoft("#1E1B4B"));

        layout.section("Sales")
                .order(0)
                .icon("shopping-cart")
                .document(Order.class)
                .catalog(Customer.class);

        layout.section("Catalog")
                .order(1)
                .icon("book")
                .catalog(Book.class)
                .catalog(BookCategory.class);

        layout.section("Inventory")
                .order(2)
                .icon("package")
                .document(StockReceipt.class)
                .register(BookStock.class);

        layout.section("Suppliers")
                .order(3)
                .icon("truck")
                .catalog(Supplier.class);

        var people = layout.section("People")
                .order(4)
                .icon("users");
        if (includeEmployees) {
            people.catalog(Employee.class);
        }

        layout.section("Reports")
                .order(5)
                .icon("chart-column")
                .register(BookSales.class);

        // Link a signed-in login to its Employee row by email, so the person can be greeted and shown
        // as a comment author. The lookup reads the row directly, bypassing @AccessControl, so it
        // resolves for MANAGERs too even though they have no Employees nav entry.
        layout.identity(Employee.class, "email");
    }
}
