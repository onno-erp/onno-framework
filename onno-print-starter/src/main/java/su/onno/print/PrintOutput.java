package su.onno.print;

/** What a print form downloads as. */
public enum PrintOutput {
    /** The filled template in its own format ({@code .docx} or {@code .xlsx}). */
    SOURCE,
    /** The filled template converted to PDF by the configured {@link PdfConverter}. */
    PDF
}
