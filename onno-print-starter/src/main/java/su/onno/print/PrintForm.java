package su.onno.print;

import su.onno.model.CatalogObject;
import su.onno.model.DocumentObject;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * A printable form of a catalog or document: a Word or Excel template plus the typed code that
 * fills it. Register one as a Spring bean per form; the record surface then offers it in its menu
 * and {@code GET /api/print/{kind}/{name}/{id}/{key}} downloads the filled file.
 *
 * <pre>{@code
 * @Component
 * class OrderInvoice extends PrintForm<Order> {
 *     OrderInvoice() { super("invoice", Order.class); }
 *
 *     @Override protected void configure(PrintFormSpec spec) {
 *         spec.label("Invoice").template("classpath:/print/invoice.docx");
 *     }
 *
 *     @Override protected void fill(Order order, TemplateModel model) {
 *         model.text("customer", order.getCustomer())
 *              .table("items", order.getItems(), (row, line) -> row
 *                  .text("book", line.getBook())
 *                  .number("qty", line.getQuantity()));
 *     }
 * }
 * }</pre>
 *
 * @param <T> the catalog or document class
 */
public abstract class PrintForm<T> {

    private static final Pattern KEY = Pattern.compile("[a-z0-9][a-z0-9-]*");

    private final String key;
    private final Class<T> type;
    private volatile PrintFormSpec spec;

    /**
     * @param key  stable identifier, unique per type: lowercase letters, digits and dashes (it is a
     *             URL segment and the {@code print-{key}} action key)
     * @param type the catalog or document class this form prints
     */
    protected PrintForm(String key, Class<T> type) {
        if (key == null || !KEY.matcher(key).matches()) {
            throw new IllegalArgumentException("Print form key must match [a-z0-9][a-z0-9-]*: " + key);
        }
        this.key = key;
        this.type = Objects.requireNonNull(type, "type");
    }

    /** Sets the label, template, roles and locale; the defaults need no override. */
    protected void configure(PrintFormSpec spec) {
    }

    /** Binds the record's values into the template model. */
    protected abstract void fill(T record, TemplateModel model);

    /**
     * Download file name without extension. Defaults to {@code {key}-{number}} for a document and
     * {@code {key}-{code}} for a catalog item.
     */
    protected String fileName(T record) {
        String suffix = switch (record) {
            case DocumentObject d -> d.getNumber();
            case CatalogObject c -> c.getCode();
            default -> null;
        };
        return suffix == null || suffix.isBlank() ? key : key + "-" + suffix;
    }

    public final String key() {
        return key;
    }

    public final Class<T> type() {
        return type;
    }

    /** The configured spec (computed once). */
    final PrintFormSpec spec() {
        PrintFormSpec s = spec;
        if (s == null) {
            synchronized (this) {
                if (spec == null) {
                    PrintFormSpec configured = new PrintFormSpec();
                    configure(configured);
                    spec = configured;
                }
                s = spec;
            }
        }
        return s;
    }

    /** Menu label: the configured label, else the key. */
    public final String label() {
        String label = spec().label();
        return label == null || label.isBlank() ? key : label;
    }

    final void fillInto(Object record, TemplateModel model) {
        fill(type.cast(record), model);
    }

    final String fileNameOf(Object record) {
        return fileName(type.cast(record));
    }
}
