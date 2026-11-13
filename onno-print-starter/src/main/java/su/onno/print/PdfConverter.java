package su.onno.print;

/**
 * Converts a filled {@code .docx}/{@code .xlsx} file to PDF. Register one bean to enable PDF output:
 * the starter auto-configures {@link GotenbergPdfConverter} when
 * {@code onno.print.pdf.gotenberg.url} is set and {@link LibreOfficePdfConverter} when
 * {@code onno.print.pdf.libreoffice.command} is set; an application bean of this type replaces both.
 */
public interface PdfConverter {

    /**
     * @param document the filled document
     * @param format   its format, {@code docx} or {@code xlsx}
     * @return the PDF bytes
     */
    byte[] toPdf(byte[] document, String format);
}
