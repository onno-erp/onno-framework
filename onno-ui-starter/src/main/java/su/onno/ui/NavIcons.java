package su.onno.ui;

/**
 * Picks a distinct glyph for a navigation entry from its entity name, so the nav
 * (sidebar, bottom bar, mobile menu) doesn't repeat one section icon down every row.
 * A heuristic default only — a layout that sets an explicit icon should win, and the
 * section icon is the fallback when nothing matches.
 *
 * <p>Returned names are lucide kebab-case icon names, rendered by the client's
 * {@code onno-icon} bridge; any lucide name is valid and an unknown one degrades to a
 * fallback glyph rather than rendering blank.</p>
 */
final class NavIcons {

    private NavIcons() {}

    /** A per-item icon derived from {@code name}, falling back to {@code sectionIcon}. */
    static String forItem(String name, String type, String sectionIcon) {
        String n = name == null ? "" : name.toLowerCase();

        // Compound names hit their most specific word first: "Book sales" is a sales
        // report (chart), "Book stock" is inventory (package), "Stock receipt" is a
        // receipt document — only a plain "Book" is the literal thing on a shelf.
        if (has(n, "dashboard", "overview", "home")) return "house";
        if (has(n, "propert", "apartment", "room", "unit", "listing")) return "building";
        if (has(n, "rental", "lease", "tenanc")) return "key";
        if (has(n, "bed", "occupan")) return "bed";
        if (has(n, "booking", "reservation", "stay", "schedule", "event", "shift")) return "calendar";
        if (has(n, "client", "customer", "guest", "tenant", "contact")) return "users";
        if (has(n, "employee", "staff", "user", "people", "person", "team")) return "user";
        if (has(n, "supplier", "vendor", "shipment", "delivery")) return "truck";
        if (has(n, "bill", "invoice", "receipt")) return "receipt";
        if (has(n, "payment", "transaction", "payout")) return "wallet";
        if (has(n, "bank", "account", "balance", "cash")) return "banknote";
        if (has(n, "receivable", "payable", "ledger", "contract", "agreement", "document", "file")) return "file-text";
        if (has(n, "revenue", "sales", "report", "income", "stat", "analytic", "kpi", "metric")) return "chart-column";
        if (has(n, "countr", "region", "location", "address", "place")) return "map-pin";
        if (has(n, "product", "item", "good", "stock", "inventor", "warehouse")) return "package";
        if (has(n, "book", "librar", "publication")) return "book-open";
        if (has(n, "setting", "config", "preference", "admin")) return "settings";
        if (has(n, "task", "todo", "ticket", "issue", "job", "order")) return "clipboard-list";
        if (has(n, "categor", "tag", "label", "type", "status")) return "tag";

        // Registers are reporting surfaces; a chart reads better than a blank section icon.
        if ("register".equals(type)) return "chart-column";

        // An authored section icon is the next-best default; otherwise a neutral, type-aware
        // glyph (never a bare placeholder circle): documents look like records, catalogs like
        // a tagged reference list.
        if (sectionIcon != null && !sectionIcon.isBlank()) return sectionIcon;
        return switch (type == null ? "" : type) {
            case "document" -> "file-text";
            case "register" -> "chart-column";
            default -> "box";
        };
    }

    private static boolean has(String haystack, String... needles) {
        for (String needle : needles) {
            if (haystack.contains(needle)) return true;
        }
        return false;
    }
}
