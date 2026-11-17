package su.onno.print;

/**
 * The second download a form offers besides its default output: PDF for a template-format form when
 * a converter is configured, the template format for a PDF form.
 *
 * @param format the {@code ?format=} value
 * @param suffix the action-key suffix ({@code pdf} or {@code source})
 */
record PrintVariant(String format, String suffix) {

    /** The variant of {@code form}, or null when it has none. */
    static PrintVariant of(PrintService print, PrintForm<?> form) {
        if (print.defaultOutput(form) == PrintOutput.PDF) {
            return new PrintVariant(print.format(form), "source");
        }
        return print.pdfAvailable() ? new PrintVariant("pdf", "pdf") : null;
    }
}
