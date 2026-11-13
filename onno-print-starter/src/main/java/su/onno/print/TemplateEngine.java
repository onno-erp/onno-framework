package su.onno.print;

/** Fills one template format ({@code .docx} or {@code .xlsx}) with a {@link TemplateModel}. */
public interface TemplateEngine {

    /** The filled document produced from the template bytes. */
    byte[] render(byte[] template, TemplateModel model);
}
