package su.onno.print;

import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

/** Title, template, output and access settings of a {@link PrintForm}, set in {@link PrintForm#configure(PrintFormSpec)}. */
public final class PrintFormSpec {

    private String label;
    private String template;
    private final Set<String> roles = new LinkedHashSet<>();
    private Locale locale = Locale.getDefault();
    private PrintOutput output = PrintOutput.SOURCE;

    PrintFormSpec() {
    }

    /** Menu label of the print action; defaults to the form key. */
    public PrintFormSpec label(String label) {
        this.label = label;
        return this;
    }

    /**
     * Template location resolved by Spring's {@code ResourceLoader}, ending in {@code .docx} or
     * {@code .xlsx}. Defaults to {@code classpath:/print/{key}.docx}, then {@code .xlsx}.
     */
    public PrintFormSpec template(String location) {
        this.template = Objects.requireNonNull(location, "location");
        return this;
    }

    /**
     * Restricts the form to callers holding one of {@code roles} (ADMIN always passes), on top of
     * read access to the record. Without roles, every reader of the record may print it.
     */
    public PrintFormSpec roles(String... roles) {
        this.roles.addAll(Set.of(roles));
        return this;
    }

    /** Locale for Word number/date patterns; defaults to the JVM default locale. */
    public PrintFormSpec locale(Locale locale) {
        this.locale = Objects.requireNonNull(locale, "locale");
        return this;
    }

    /**
     * Downloads the form as PDF by default (the source format stays available via
     * {@code ?format=}). Requires a {@link PdfConverter}; startup fails without one.
     */
    public PrintFormSpec pdf() {
        this.output = PrintOutput.PDF;
        return this;
    }

    String label() {
        return label;
    }

    String template() {
        return template;
    }

    Set<String> roles() {
        return Set.copyOf(roles);
    }

    Locale locale() {
        return locale;
    }

    PrintOutput output() {
        return output;
    }
}
