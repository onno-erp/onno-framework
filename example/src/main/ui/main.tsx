/**
 * Onno Books — the app's UI.
 *
 * The server exposes the business model and a default screen for every entity; this declares only
 * what differs: how each entity presents (entities/, one file per entity), the app's own pages
 * (pages/) and the sidebar.
 */
import {
  Activity,
  ListChecks,
  Users,
  Settings,
  Inbox,
  Plug,
  ShoppingCart,
  Book,
  Package,
  Truck,
  ChartColumn,
  MessagesSquare,
  Lock,
} from "@onno/ui/icons";
import { defineApp } from "@onno/ui";
import { NewOrderButton } from "./components/NewOrderButton";
import { entities } from "./entities";
import { crmInboxAvailable } from "./lib/crm";
import { LoginPage } from "./pages/LoginPage";
import { HomePage } from "./pages/HomePage";
import { SalesOpsPage } from "./pages/SalesOpsPage";
import { SalesReportPage } from "./pages/SalesReportPage";
import { SettingsPage } from "./pages/SettingsPage";
import { PeriodClosePage } from "./pages/PeriodClosePage";
import { StockReceiptPage } from "./pages/StockReceiptPage";
import { SuppliersPage } from "./pages/SuppliersPage";
import {
  ConnectionsPage,
  ContactPage,
  ConversationPage,
  SupportPage,
  supportExtensions,
} from "./pages/SupportPage";
import { TasksPage } from "./pages/TasksPage";
import { TeamPage } from "./pages/TeamPage";

defineApp({
  login: LoginPage,
  entities: { ...entities, "Book Prices": {
    fields: { price: { format: { money: "USD" } } },
    list: {
      columns: ["book", "period", "price"], sort: "-period",
      filters: [
        { field: "book", label: "Book", optionsFrom: "Books" },
        { field: "period", label: "Date", type: "dateRange" },
      ],
    },
  } },
  // Contributions to the CRM chat's outlets (quote a message, open the contact's page).
  extensions: supportExtensions,
  pages: [
    // Entity screens whose layout differs from the default (presentation lives in entities/).
    { list: "Suppliers", component: SuppliersPage },
    { record: "Stock Receipts", component: StockReceiptPage },
    { path: "/", component: HomePage },
    {
      path: "/ops",
      component: SalesOpsPage,
      label: "Sales Ops",
      icon: Activity,
    },
    {
      path: "/tasks",
      component: TasksPage,
      label: "My tasks",
      icon: ListChecks,
    },
    { path: "/team", component: TeamPage, label: "Team", icon: Users },
    {
      path: "/settings",
      component: SettingsPage,
      label: "Settings",
      icon: Settings,
      permission: "settings.edit",
    },
    { path: "/period-close", component: PeriodClosePage, label: "Period close", icon: Lock, permission: "periodClose.manage", when: (model) => model.features.periodClose === true },
    // Optional modules: the page and its link exist only when the module does.
    {
      path: "/support",
      component: SupportPage,
      label: "Inbox",
      icon: Inbox,
      when: crmInboxAvailable,
    },
    {
      path: "/support/connections",
      component: ConnectionsPage,
      label: "Connections",
      icon: Plug,
      permission: "crm.administer",
      when: crmInboxAvailable,
    },
    {
      path: "/catalogs/crm_conversations/:id",
      component: ConversationPage,
      view: false,
      when: crmInboxAvailable,
    },
    {
      path: "/support/contacts/:id",
      component: ContactPage,
      view: false,
      when: crmInboxAvailable,
    },
    // Pages that replace an entity's default screen.

    { path: "/registers/book_sales", component: SalesReportPage, view: false },
  ],

  // To replace the entire navigation layout, import AppNavigation and set:
  // navigationComponent: AppNavigation,

  // Entities the user cannot read, pages they cannot use and sections left empty are dropped.
  navigation: [
    {
      title: "Sales",
      icon: ShoppingCart,
      actions: <NewOrderButton />,
      items: [
        "Orders",
        "Customers",
        {
          title: "Work",
          items: [
            "/tasks",
            {
              title: "Reports",
              icon: ChartColumn,
              items: ["Book Sales", "/ops"],
            },
          ],
        },
      ],
    },
    { title: "Catalog", icon: Book, items: ["Books", "Categories", "Book Prices"] },
    {
      title: "Inventory",
      icon: Package,
      items: ["Stock Receipts", "Book Stock"],
    },
    { title: "Suppliers", icon: Truck, items: ["Suppliers"] },
    // Scheduling is calendar-shaped work: the Team page leads, and the team-event list beside it
    // switches between table, calendar and a per-organizer timeline.
    {
      title: "People",
      icon: Users,
      items: ["/team", "Schedule Events", { entity: "Employees", roles: ["ADMIN"] }],
    },
    { title: "Reports", icon: ChartColumn, items: ["Book Sales", "/ops"] },
    {
      title: "Support",
      icon: MessagesSquare,
      items: ["/support", "/support/connections"],
    },
    { title: "System", icon: Settings, items: ["/settings", "/period-close"] },
  ],
});
