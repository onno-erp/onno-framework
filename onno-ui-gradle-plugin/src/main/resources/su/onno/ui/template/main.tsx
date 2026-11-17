/**
 * The app's UI. Every screen starts as the framework's default, wired here explicitly so you can
 * see where to change it: edit a page file, or swap the component for your own. Entity names,
 * fields and enum values are typed from the Java model (model.gen.ts, regenerated on every build).
 *
 *   pages/       the login page, home and your own list, record and ordinary pages
 *   components/  your own components
 */
import { defineApp } from "@onno/ui";
import { HomePage } from "./pages/HomePage";
import { LoginPage } from "./pages/LoginPage";
// import { ChartColumn, ShoppingCart } from "@onno/ui/icons";

defineApp({
  // The /login screen (password, SSO buttons, …). See pages/LoginPage.tsx.
  login: LoginPage,
  pages: [
    { path: "/", component: HomePage },
    // Import your TSX pages, then register entity screens by logical name:
    // { list: "Orders", component: OrdersPage },
    // { record: "Orders", component: OrderPage },
    // { path: "/reports", component: ReportsPage, label: "Reports", icon: ChartColumn, roles: ["MANAGER"] },
  ],
  // The sidebar groups every readable entity by its `context` and lists labelled pages.
  // Curate it instead with sections of entity names and page paths:
  // navigation: [{ title: "Sales", icon: ShoppingCart, items: ["Orders", "/reports"] }],
  // or take over its layout with `navigationComponent` (composing Navigation, NavigationSection,
  // NavigationLink and DefaultNavigation from @onno/ui).
});
