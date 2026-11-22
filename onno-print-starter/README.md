# onno-print-starter

Print forms for onno catalogs and documents: a Word (`.docx`) or Excel (`.xlsx`) template plus a
small typed Java class that fills it. Each form appears in the record surface's ⋯ menu and downloads
the filled file. It is the onno counterpart of a 1C print form ("печатная форма").

```kotlin
implementation("su.onno:onno-print-starter:$onnoVersion")
```

The starter is on whenever it is on the classpath. Set `onno.print.enabled=false` to turn it off.
It needs `onno-web-starter`, which provides the record surface, access checks and download scheme.

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
            .roles("MANAGER")                  // optional; a declared role (checked at startup), compared normalized
            .locale(Locale.GERMANY)            // Word number/date patterns (default: JVM locale)
            .pdf();                            // optional: download as PDF by default (needs a converter)
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

Only `.docx` and `.xlsx` are supported, not the legacy `.doc`/`.xls`. Built-in templates come from
any Spring resource location (`classpath:`, `file:`); admins can replace them at runtime (below).

## Endpoints and UI

```text
GET    /api/print/{kind}/{name}/{id}                   forms the caller may use → [{key,label,format,formats,url}]
GET    /api/print/{kind}/{name}/{id}/{form}[?format=]  the filled file, Content-Disposition: attachment
POST   /api/print/batch/{kind}/{name}/{form}[?format=] {"ids":[…]} → zip of the filled files
GET    /api/print/templates                            every form + template state (manage roles)
GET    /api/print/templates/{kind}/{name}/{form}[?builtin=true]   the effective / built-in template
PUT    /api/print/templates/{kind}/{name}/{form}       multipart "file": replace the template
DELETE /api/print/templates/{kind}/{name}/{form}       back to the built-in template
```

`format` is `pdf` or the template's own format (`docx`/`xlsx`); without it a form downloads in its
default output. An unsupported format, or `pdf` with no converter configured, is a `400`.

`kind` is `catalogs` or `documents`, and `name` is the entity's route name (for example `Orders`).
Both endpoints require read access to the entity and to the record under any `RecordAccessPolicy`:
a record outside the caller's scope is a `404`. A form with `roles(...)` returns `403` to other
callers. Printing is a read, so it works in `onno.web.read-only` mode too; template upload and reset
are rejected with `403` in that mode.

The endpoints authorize the *printed record*. What `fill(...)` reads beyond it is application code:
`Ref` values bound with `text(...)` print the referenced record's display name (as the UI shows it),
and typed repositories are unscoped — if a form loads other records for a non-admin caller, keep it
to data that reader of the record is meant to see.

On the record surface, each permitted form adds an entry with the key `print-{key}` to the ⋯ menu.
The entry links to `onno://download/api/print/...`. The server sends only its key, title and URL; the
app's React `commands` customization places and icons it like any command, for example
`commands: { "print-invoice": { placement: "primary", icon: "printer" } }` or `"hidden"` to drop it.
The entry comes from a `RecordActionContributor` bean (an `onno-web-starter` SPI), so other modules
can add read-only record actions the same way.

With a PDF converter configured, each menu entry gets a sibling: `Invoice · PDF` (key
`print-{key}-pdf`) for a Word/Excel form, or `Invoice · DOCX` (`print-{key}-source`) for a `pdf()`
form.

## Batch printing

Select rows in a catalog/document list (⌘/Ctrl/Shift-click, ⌘A) and the selection toolbar offers each
permitted form (and its PDF/source sibling). It POSTs the ids and saves one zip with a file per
record; duplicate names get `(2)`, `(3)`… Records outside the caller's scope are skipped. At most
`onno.print.batch.max-records` (default 200) per request. The buttons come from a
`SelectionDownloadContributor` bean (an `onno-web-starter` SPI) and are shown to every reader, not
only writers.

## Uploaded templates

Admins can edit templates without a redeploy. The starter's UI package `@onno/print`
(`src/main/ui`) exports a `PrintTemplates` component; add `"@onno/print"` to the app's
`src/main/ui/package.json` and render it on an admin page:

```tsx
import { PrintTemplates } from "@onno/print";

<PrintTemplates title="Print templates" hint="Edit the Word and Excel files behind the Print menu." />
```

It lists every form with **Download** (the effective template, or the built-in one), **Upload** (an
edited copy) and **Reset** (back to the built-in file). An upload must be the same format as the
built-in template, a readable `.docx`/`.xlsx`, and at most `onno.print.templates.max-size`
(default 10 MB). It replaces the template for every user and every node; the file is stored in the
framework-managed `onno_print_templates` table, keyed by entity (`documents/Orders`) and form key —
renaming the entity orphans its uploads. Only holders of `print.templates.manage`
(`PrintPermissions.MANAGE_TEMPLATES`, granted in the app's `RoleGrants`) may manage templates; `onno.print.templates.enabled=false` makes the built-in templates
final. The fill code is unchanged by an upload, so an edited template can rearrange or restyle the
`{{placeholders}}` but not invent new ones.

## PDF output

Configure one converter:

```yaml
onno:
  print:
    pdf:
      gotenberg:
        url: http://localhost:3000     # docker run -p 3000:3000 gotenberg/gotenberg:8
      # or a local LibreOffice:
      # libreoffice:
      #   command: soffice             # or /Applications/LibreOffice.app/Contents/MacOS/soffice
```

Then any form can be downloaded with `?format=pdf`, and `spec.pdf()` makes PDF a form's default.
A form declaring `pdf()` without a converter fails startup. `GotenbergPdfConverter` posts the filled
file to Gotenberg's LibreOffice route; `LibreOfficePdfConverter` runs
`soffice --headless --convert-to pdf` in a fresh temp directory and profile per conversion. Register
your own `PdfConverter` bean to use something else; it replaces both.

## Programmatic use

```java
PrintForm<?> form = printService.form(Order.class, "invoice").orElseThrow();
PrintResult file = printService.render(form, order);   // content(), contentType(), fileName()
PrintResult pdf  = printService.render(form, order, PrintOutput.PDF);
PrintResult zip  = printService.renderZip(form, List.of(a, b), PrintOutput.SOURCE);
```

`TemplateModel`, `DocxTemplateEngine` and `XlsxTemplateEngine` can also be used directly to fill a
template from code that is not a print form, such as an email attachment.

## Replaces the old print starter

An earlier `onno-print-starter` (`@PrintTemplate` + Thymeleaf HTML/PDF) was removed in #291
because it had no endpoint and no UI. This module reuses the artifact name with a new API: delete
`@PrintTemplate` annotations and their `.html` templates, and declare `PrintForm` beans instead.
