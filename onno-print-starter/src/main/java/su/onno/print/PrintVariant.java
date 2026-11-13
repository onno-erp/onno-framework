package su.onno.print;

/**
 * The second download a form offers besides its default output: PDF for a template-format form when
 * a converter is configured, the template format for a PDF form.
 *
 * @param format the {@code ?format=} value
 * @param suffix the action-key suffix ({@code pdf} or {@code source})
 * @param icon   lucide icon of the entry
 */
record PrintVariant(String format, String suffix, String icon) {

    /** The variant of {@code form}, or null when it has none. */
    static PrintVariant of(PrintService print, PrintForm<?> form) {
        if (print.defaultOutput(form) == PrintOutput.PDF) {
            String source = print.format(form);
            return new PrintVariant(source, "source", "docx".equals(source) ? "file-text" : "sheet");
        }
        return print.pdfAvailable() ? new PrintVariant("pdf", "pdf", "file-down") : null;
    }
}
