package su.onno.command;

import su.onno.annotations.Catalog;
import su.onno.annotations.Document;
import su.onno.types.Ref;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * What a {@link Command} handler returns: whether the caller should reload the data it shows,
 * optional {@link CommandFeedback} saying what happened, and an optional route to open afterwards
 * ({@code navigateTo}). Meaning only — the client decides how to show it.
 *
 * <pre>
 * return CommandResult.ok();                                   // nothing to say
 * return CommandResult.reload();                               // data changed, nothing to say
 * return CommandResult.success("Order cancelled").andRefresh();
 * return CommandResult.info("124 records imported").title("Import completed").detail("3 rows skipped");
 * return CommandResult.open(Ref.of(Invoice.class, invoiceId));     // open the record it created
 * return CommandResult.navigate("/reports").withFeedback(...);      // or any app route
 * </pre>
 *
 * <p>A purposeful refusal is not a result: throw {@link CommandRejectedException}.</p>
 */
public record CommandResult(boolean refresh, CommandFeedback feedback, String navigateTo) {

    /**
     * The only {@code onno://} targets a result may navigate to: home ({@code onno://}) or a
     * catalog, document or register surface. Command URLs ({@code logout}, {@code delete/…},
     * {@code action/…}, {@code redirect/…}, {@code open/…}, {@code download/…}) are rejected, so a
     * route built from stored or user-entered data can never post, delete, sign out or leave the
     * application.
     */
    private static final java.util.regex.Pattern RECORD_URL = java.util.regex.Pattern.compile(
            "onno://(catalogs|documents|registers|information-registers)/[^/?#\\\\]+(/[^/?#\\\\]+)?");

    public CommandResult {
        if (navigateTo != null && !isNavigationTarget(navigateTo)) {
            throw new IllegalArgumentException("navigateTo must be an application path starting with / or an "
                    + "onno://{catalogs|documents|registers|information-registers}/… record URL: " + navigateTo);
        }
    }

    /** A result without navigation. */
    public CommandResult(boolean refresh, CommandFeedback feedback) {
        this(refresh, feedback, null);
    }

    /** Whether {@code route} is a pure navigation target (an app path or a record/surface URL). */
    public static boolean isNavigationTarget(String route) {
        if (route.equals("onno://")) return true; // home
        if (route.startsWith("onno://")) return RECORD_URL.matcher(route).matches();
        return route.startsWith("/") && !route.startsWith("//") && !route.contains("\\");
    }

    /** Open a catalog or document record (for example the one the command created). */
    public static CommandResult open(Ref<?> ref) {
        Objects.requireNonNull(ref, "ref is required");
        Catalog catalog = ref.type().getAnnotation(Catalog.class);
        Document document = ref.type().getAnnotation(Document.class);
        if ((catalog == null) == (document == null)) {
            throw new IllegalArgumentException("Reference target must declare exactly one of @Catalog or @Document: "
                    + ref.type().getName());
        }
        String kind = catalog != null ? "catalogs" : "documents";
        String name = catalog != null ? catalog.name() : document.name();
        // Match client record links; storage table overrides and display titles are not route names.
        String routeName = name.replaceAll("([a-z])([A-Z])", "$1_$2")
                .replaceAll("\\s+", "_").toLowerCase(Locale.ROOT);
        return navigate("/" + kind + "/" + routeName + "/" + ref.id());
    }

    /** Navigate to a router-relative path or an {@code onno://} surface URL after the command. */
    public static CommandResult navigate(String route) {
        return new CommandResult(false, null, Objects.requireNonNull(route, "route is required"));
    }

    /** The same result, also navigating to {@code route} afterwards. */
    public CommandResult andNavigate(String route) {
        return new CommandResult(refresh, feedback, Objects.requireNonNull(route, "route is required"));
    }

    /** Did nothing observable — just acknowledge. */
    public static CommandResult ok() {
        return new CommandResult(false, null);
    }

    /** Reload the current surface without feedback. */
    public static CommandResult reload() {
        return new CommandResult(true, null);
    }

    /** It worked: {@code message} says what was done. */
    public static CommandResult success(String message) {
        return of(CommandSeverity.SUCCESS, message);
    }

    /** Neutral information about the run. */
    public static CommandResult info(String message) {
        return of(CommandSeverity.INFO, message);
    }

    /** It ran, but something needs the user's attention. */
    public static CommandResult warning(String message) {
        return of(CommandSeverity.WARNING, message);
    }

    /** It ran and failed in an expected way (nothing for an input form to fix; see {@link CommandRejectedException}). */
    public static CommandResult error(String message) {
        return of(CommandSeverity.ERROR, message);
    }

    private static CommandResult of(CommandSeverity severity, String message) {
        return new CommandResult(false, CommandFeedback.of(severity, null, message, List.of()));
    }

    /** The same result, also asking the caller to reload. */
    public CommandResult andRefresh() {
        return new CommandResult(true, feedback, navigateTo);
    }

    /** A heading over the message. */
    public CommandResult title(String title) {
        CommandFeedback f = requireFeedback();
        return new CommandResult(refresh, CommandFeedback.of(f.severity(), title, f.message(), f.details()), navigateTo);
    }

    /** One readable detail line (blank lines are ignored). */
    public CommandResult detail(String detail) {
        return details(detail == null ? List.of() : List.of(detail));
    }

    /** Readable detail lines (blank lines are ignored). */
    public CommandResult details(List<String> details) {
        CommandFeedback f = requireFeedback();
        List<String> all = new ArrayList<>(f.details());
        if (details != null) {
            details.stream().filter(Objects::nonNull).filter(d -> !d.isBlank()).forEach(all::add);
        }
        return new CommandResult(refresh, CommandFeedback.of(f.severity(), f.title(), f.message(), all), navigateTo);
    }

    private CommandFeedback requireFeedback() {
        if (feedback == null) {
            throw new IllegalStateException("ok()/reload() carry no feedback; start from success/info/warning/error");
        }
        return feedback;
    }
}
