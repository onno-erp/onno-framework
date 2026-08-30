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
        configureShell(layout);
        buildNavigation(layout, false);
    }

    /**
     * Configures application-wide shell and identity metadata. Named profiles inherit this default
     * contribution and only provide their own role-specific navigation.
     */
    private static void configureShell(LayoutSpec layout) {
        // Branding configured in Java: app name, brand colors retinting the DivKit chrome accent in
        // light and dark modes, plus a logo/favicon served from src/main/resources/static/ui.
        layout.shell()
                .nav(NavStyle.SIDEBAR)
                .brand("Onno Books")
                // The compact onno mark keeps the demo shell recognizable without crowding its name.
                // Two files keep the mark and label legible in both shell themes.
                .logo("/ui/branding/logo.svg", "/ui/branding/logo-dark.svg")
                .mark("/ui/branding/favicon.svg")
                .favicon("/ui/branding/favicon.svg")
                .light(c -> c.primary("#4F46E5").primarySoft("#EEF2FF"))
                .dark(c -> c.primary("#6366F1").primarySoft("#1E1B4B"));

        // Link a signed-in login to its Employee row by email, so the person can be greeted and shown
        // as a comment author. The lookup reads the row directly, bypassing @AccessControl, so it
        // resolves for MANAGERs too even though they have no Employees nav entry.
        layout.identity(Employee.class, "email");
    }

    /**
     * Builds the profile navigation. {@code includeEmployees} adds the {@link Employee} catalog to
     * the "People" section for administrators. Keeping both profiles on one builder prevents their
     * shared route structure from drifting while leaving shell ownership on the default layout.
     */
    static void buildNavigation(LayoutSpec layout, boolean includeEmployees) {

        layout.section("Sales")
                .order(0)
                .icon("shopping-cart")
                .document(Order.class)
                .catalog(Customer.class)
                .page("/tasks", "My tasks", "list-checks");

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
                .icon("users")
                // Scheduling is calendar-shaped work, so expose the authored Team page rather than
                // a raw ScheduleEvent document list. The entity remains directly routable for
                // calendar event forms and the "New team event" action.
                .page("/team", "Team", "users");
        if (includeEmployees) {
            people.catalog(Employee.class);
        }

        layout.section("Reports")
                .order(5)
                .icon("chart-column")
                .register(BookSales.class)
                // A sidebar link to an authored Page at a custom route — a second dashboard living
                // beside the register report. See com.example.ui.pages.SalesOpsPage.
                .page("/ops", "Sales Ops", "activity");

        // Settings is just a page: there is no built-in Settings entry. This app authors one
        // (com.example.ui.pages.SettingsPage at /settings, admin-profile) and links it here for
        // admins only — the same page-link mechanism as Sales Ops above.
        if (includeEmployees) {
            layout.section("System")
                    .order(6)
                    .icon("settings")
                    .page("/settings", "Settings", "settings");
        }

    }
}
