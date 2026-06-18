# onno-print-starter

Spring Boot starter that renders printable forms for onno documents and catalogs. A class declares one
or more print templates with the `@PrintTemplate` annotation; the `PrintService` renders a given
instance of that class to **HTML** or **PDF** bytes.

Templates are written as Thymeleaf HTML. PDF output is produced by running the rendered HTML through
Flying Saucer / OpenPDF (`ITextRenderer`), so PDF templates must be valid XHTML.

## Enabling

The starter is **on by default** — auto-configuration kicks in whenever Thymeleaf is on the classpath
(`onno.print.enabled` defaults to `true`). To turn it off:

```yaml
onno:
  print:
    enabled: false
```

When active it exposes two beans (each `@ConditionalOnMissingBean`, so you can override either):
`PrintTemplateRegistry` and `PrintService`.

### Configuration keys

| Key | Default | Purpose |
|-----|---------|---------|
| `onno.print.enabled` | `true` | Master switch. |
| `onno.print.base-packages` | *application's auto-configuration packages* | Packages scanned for `@PrintTemplate` classes. Defaults to your app's base packages. |
| `onno.print.encoding` | `UTF-8` | Charset used to read templates and encode HTML output. |

## Declaring a print form

Annotate the document/catalog class with `@PrintTemplate` (repeatable — declare several to offer
e.g. summary / detailed / legal variants):

```java
@PrintTemplate(name = "bill", label = "Factura", format = PrintFormat.PDF, order = 0)
@PrintTemplate(name = "guardia-civil", label = "Parte GC", order = 1)
public class Invoice {
    public String getNumber() { ... }
    public List<Line> getLines() { ... }
}
```

Annotation attributes:

| Attribute | Default | Notes |
|-----------|---------|-------|
| `name` | *(required)* | Stable identifier, used in URLs / UI actions and to look up the template. |
| `label` | `""` | Human-readable label for the print button (falls back to `name`). |
| `template` | `classpath:/print/{name}.html` | Template path, resolved by Spring's `ResourceLoader`. |
| `format` | `PrintFormat.PDF` | `PDF` or `HTML`. |
| `order` | `0` | Sort order when listing templates for a target. |

At startup `PrintScanner` scans `base-packages`, and every `@PrintTemplate` is registered in the
`PrintTemplateRegistry`, keyed by `(target class, name)`.

### Writing the template

Templates are standard Thymeleaf HTML. There is **no bundled template** — you supply your own at the
resolved path (default `src/main/resources/print/{name}.html`). The rendered entity is exposed under
two variables, `doc` and `self`; any extras map you pass is exposed both as a map under `extra` and
with each key flattened to a top-level variable:

```html
<!DOCTYPE html>
<html xmlns:th="http://www.thymeleaf.org">
<body>
  <h1 th:text="${doc.number}">INV-000</h1>
  <table>
    <tr th:each="line : ${doc.lines}">
      <td th:text="${line.description}"></td>
      <td th:text="${line.amount}"></td>
    </tr>
  </table>
  <footer th:text="${extra.printedBy}"></footer>
</body>
</html>
```

For `PDF` templates the markup must be well-formed XHTML (Flying Saucer is strict).

## Rendering

Inject `PrintService` and render an instance by template name:

```java
@Autowired PrintService print;

PrintResult pdf = print.render(invoice, "bill");
// pdf.contentType() == "application/pdf"
// pdf.filename()    == "bill.pdf"
// pdf.content()     -> byte[]

// With extra model variables (exposed as ${extra.printedBy} and ${printedBy}):
PrintResult withExtras = print.render(invoice, "bill", Map.of("printedBy", "Front desk"));
```

`PrintResult` is a record of `byte[] content`, `String contentType`, and `String filename`. The
content type and the filename extension follow the template's declared `format` (`text/html` / `.html`
or `application/pdf` / `.pdf`).

The template is matched on `target.getClass()` and `name`. Passing an unknown name (or a target whose
class has no such template) throws `IllegalArgumentException`; a missing template resource or a PDF
that fails to render throws `IllegalStateException`.

## Serving the result

This starter has **no REST endpoint** — it only produces bytes. Expose them from your own controller,
e.g.:

```java
@GetMapping("/invoices/{id}/print/{template}")
ResponseEntity<byte[]> print(@PathVariable Long id, @PathVariable String template) {
    PrintResult r = print.render(invoiceService.get(id), template);
    return ResponseEntity.ok()
            .header(HttpHeaders.CONTENT_TYPE, r.contentType())
            .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + r.filename() + "\"")
            .body(r.content());
}
```

To drive a UI, list the templates available for a class via the registry:
`printTemplateRegistry.forTarget(Invoice.class)` returns the `PrintTemplateDescriptor`s sorted by
`order` (each has `name()`, `resolvedLabel()`, `format()`).

## Gotchas

- **Lookup is exact-class, not by hierarchy.** The registry keys on the concrete `target.getClass()`.
  A proxy or subclass instance won't match a template declared on its superclass — render the plain
  entity type.
- **PDF needs valid XHTML.** Flying Saucer parses strictly; unclosed tags or stray HTML5 markup will
  fail PDF rendering.
- **Template caching is off.** The Thymeleaf resolver runs with `cacheable=false`, so each render
  re-reads and re-parses the template.
- **Scan scope.** If your `@PrintTemplate` classes live outside the application's base packages, set
  `onno.print.base-packages` explicitly or they won't be registered.
