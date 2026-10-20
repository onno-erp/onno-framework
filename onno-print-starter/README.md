# onno-print-starter

Print forms for onno catalogs and documents: a Word (`.docx`) or Excel (`.xlsx`) template plus a
small typed Java class that fills it. Each form appears in the record surface's ⋯ menu and downloads
the filled file. It is the onno counterpart of a 1C print form ("печатная форма").

```kotlin
implementation("su.onno:onno-print-starter:$onnoVersion")
```

The starter is on whenever it is on the classpath. Set `onno.print.enabled=false` to turn it off.
It needs `onno-ui-starter`, which provides the record surface, access checks and download scheme.

## Declaring a form

Register a `PrintForm<T>` bean per form. `T` must be a registered `@Catalog` or `@Document` class.

```java
@Component
public class OrderInvoicePrint extends PrintForm<Order> {

    public OrderInvoicePrint() {
        super("invoice", Order.class);          // key: [a-z0-9-], unique per type
    }

    @Override
    protected void configure(PrintFormSpec spec) {
        spec.label("Invoice (Word)")           // menu label (defaults to the key)
            .template("classpath:/print/invoice.docx") // default: classpath:/print/{key}.docx, then .xlsx
            .icon("file-text")                 // lucide icon (default "printer")
            .roles("MANAGER")                  // optional; ADMIN always passes
            .locale(Locale.GERMANY);           // Word number/date patterns (default: JVM locale)
    }

    @Override
    protected void fill(Order order, TemplateModel model) {
        model.text("number", order.getNumber())
             .date("date", order.getDate(), "dd.MM.yyyy")
             .text("customer", order.getCustomer())        // Ref -> the record's name
             .text("status", order.getStatus())            // enum -> its @EnumLabel
             .number("total", order.getTotal(), "#,##0.00")
             .table("items", order.getItems(), (row, line) -> row
                 .text("book", line.getBook())
                 .number("qty", line.getQuantity())
                 .number("amount", line.getAmount(), "#,##0.00"));
    }

    @Override
    protected String fileName(Order order) {           // optional; default "{key}-{number|code}"
        return "Invoice " + order.getNumber();
    }
}
```

Startup fails on a duplicate key for the same type, a form whose type is not a catalog or document,
or a missing template or one that is not `.docx`/`.xlsx`.

## Writing the template

Write placeholders as `{{key}}` anywhere in the file. In a table, `{{table.column}}` marks the row
that repeats once per item.

- **Word.** Placeholders work in the body, in tables, and in headers and footers. Word often splits
  typed text across several runs; a placeholder still matches, and the replacement keeps the
  formatting of the run it starts in. A table row that contains `{{items.…}}` is copied once per
  item with its formatting kept. With no items, the row is removed. A `\n` in a value becomes a line
  break.
- **Excel.** A cell that holds just one `number`/`date` placeholder gets a real number or date and
  keeps its own style and number format, so format the cell in the template. A General cell given a
  date gets an ISO date format through a cloned style; cells sharing the original style are not
  touched. Any other placeholder is replaced as text.
  - A row with `{{items.…}}` is copied once per item (styles, merged cells and relative formulas
    included), and the rows below move down.
  - A range ending on that row, such as a totals row's `=SUM(D7:D7)`, stretches over the new rows
    (`SUM(D7:D9)`). Formulas are recalculated before saving.
  - With no items, the row's table placeholders are blanked; the row stays so formulas that
    reference it keep working.
- **Patterns.** In Word, `number(key, value, "#,##0.00")` and `date(key, value, "dd.MM.yyyy")` use
  Java `DecimalFormat`/`DateTimeFormatter` patterns. Excel ignores them and uses the cell's format.
- **Unknown placeholders** are left as they are, so a typo shows up in the printed file.

Only `.docx` and `.xlsx` are supported, not the legacy `.doc`/`.xls`. Phase 1 reads templates from
any Spring resource location (`classpath:`, `file:`). Admin-uploaded templates and PDF output are
on the roadmap.

## Endpoints and UI

```text
GET /api/print/{kind}/{name}/{id}          forms the caller may use for the record → [{key,label,format,url}]
GET /api/print/{kind}/{name}/{id}/{form}   the filled file, Content-Disposition: attachment
```

`kind` is `catalogs` or `documents`, and `name` is the entity's route name (for example `Orders`).
Both endpoints require read access to the entity and to the record under any `RecordAccessPolicy`:
a record outside the caller's scope is a `404`. A form with `roles(...)` returns `403` to other
callers. Printing is a read, so it works in `onno.ui.read-only` mode too.

On the record surface, each permitted form adds an entry with the key `print-{key}` to the ⋯ menu.
The entry links to `onno://download/api/print/...`. Its placement follows the usual override, for
example `f.action("print-invoice").primary()` to show it as a button or `.hidden()` to drop it.
The menu entry comes from a `RecordActionContributor` bean (an `onno-ui-starter` SPI), so other
modules can add read-only record actions the same way.

## Programmatic use

```java
PrintForm<?> form = printService.form(Order.class, "invoice").orElseThrow();
PrintResult file = printService.render(form, order);   // content(), contentType(), fileName()
```

`TemplateModel`, `DocxTemplateEngine` and `XlsxTemplateEngine` can also be used directly to fill a
template from code that is not a print form, such as an email attachment.

## Replaces the old print starter

An earlier `onno-print-starter` (`@PrintTemplate` + Thymeleaf HTML/PDF) was removed in #291
because it had no endpoint and no UI. This module reuses the artifact name with a new API: delete
`@PrintTemplate` annotations and their `.html` templates, and declare `PrintForm` beans instead.
