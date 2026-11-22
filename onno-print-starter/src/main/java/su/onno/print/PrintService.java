package su.onno.print;

import su.onno.access.AccessSubject;
import su.onno.annotations.EnumLabel;
import su.onno.model.CatalogObject;
import su.onno.model.DocumentObject;
import su.onno.types.PolyRef;
import su.onno.types.Ref;
import su.onno.types.RefResolver;

import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.time.Instant;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * The registered {@link PrintForm}s and their rendering. Startup fails on a duplicate key for the
 * same type, a form for a class that is not a catalog or document, a missing/unsupported built-in
 * template, or a PDF form without a {@link PdfConverter}.
 *
 * <p>A form's effective template is the admin-uploaded one in {@link PrintTemplateStore} when
 * present, else its built-in template.</p>
 */
public class PrintService {

    static final String DOCX = "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
    static final String XLSX = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
    static final String PDF = "application/pdf";
    static final String ZIP = "application/zip";

    /** A template file: the effective one for a form, or its built-in default. */
    public record TemplateFile(String fileName, byte[] content, boolean uploaded, String uploadedBy,
                               Instant uploadedAt) {
    }

    private final Map<Class<?>, Map<String, PrintForm<?>>> forms = new HashMap<>();
    private final Map<PrintForm<?>, Resource> templates = new HashMap<>();
    private final Map<Class<?>, String> scopes;
    private final RefResolver refs;
    private final PrintTemplateStore store;
    private final PdfConverter pdf;
    private final long maxTemplateBytes;
    private final TemplateEngine docx = new DocxTemplateEngine();
    private final TemplateEngine xlsx = new XlsxTemplateEngine();

    /** Built-in templates only, no PDF. */
    public PrintService(List<PrintForm<?>> forms, Map<Class<?>, String> scopes, RefResolver refs,
                        ResourceLoader resources) {
        this(forms, scopes, refs, resources, null, null, 10L * 1024 * 1024);
    }

    /**
     * @param forms            the print form beans
     * @param scopes           catalog/document classes a form may target, mapped to their
     *                         {@code catalogs/{name}} / {@code documents/{name}} scope
     * @param refs             resolves {@code Ref} values to their display name
     * @param resources        resolves built-in template locations
     * @param store            uploaded template overrides, or null to disable them
     * @param pdf              PDF converter, or null when PDF output is unavailable
     * @param maxTemplateBytes largest accepted uploaded template
     */
    public PrintService(List<PrintForm<?>> forms, Map<Class<?>, String> scopes, RefResolver refs,
                        ResourceLoader resources, PrintTemplateStore store, PdfConverter pdf,
                        long maxTemplateBytes) {
        this.scopes = Map.copyOf(scopes);
        this.refs = refs;
        this.store = store;
        this.pdf = pdf;
        this.maxTemplateBytes = maxTemplateBytes;
        for (PrintForm<?> form : forms) {
            if (!scopes.containsKey(form.type())) {
                throw new IllegalStateException("Print form '" + form.key() + "' targets " + form.type().getName()
                        + ", which is not a registered @Catalog or @Document");
            }
            PrintForm<?> previous = this.forms.computeIfAbsent(form.type(), t -> new LinkedHashMap<>())
                    .putIfAbsent(form.key(), form);
            if (previous != null) {
                throw new IllegalStateException("Duplicate print form '" + form.key() + "' for "
                        + form.type().getName() + ": " + previous.getClass().getName() + " and "
                        + form.getClass().getName());
            }
            if (form.spec().output() == PrintOutput.PDF && pdf == null) {
                throw new IllegalStateException("Print form '" + form.key() + "' declares pdf() but no PdfConverter "
                        + "is configured (set onno.print.pdf.gotenberg.url or onno.print.pdf.libreoffice.command)");
            }
            templates.put(form, template(form, resources));
        }
    }

    /** The forms registered for {@code type}, ordered by label. */
    public List<PrintForm<?>> formsFor(Class<?> type) {
        return forms.getOrDefault(type, Map.of()).values().stream()
                .sorted(Comparator.comparing(PrintForm::label))
                .toList();
    }

    /**
     * Startup check: every form's {@code roles(...)} names a role the application declares (or maps
     * with {@code fromIdp}), so a misspelled role fails startup instead of hiding the form from
     * everyone.
     */
    public void requireKnownRoles(su.onno.access.RoleGrants grants) {
        for (PrintForm<?> form : allForms()) {
            grants.requireRecognized("print form " + scope(form) + "/" + form.key() + " roles(...)", form.spec().roles());
        }
    }

    /** Every registered form, ordered by scope then label. */
    public List<PrintForm<?>> allForms() {
        return forms.values().stream().flatMap(m -> m.values().stream())
                .sorted(Comparator.comparing((PrintForm<?> f) -> scope(f)).thenComparing(PrintForm::label))
                .toList();
    }

    /** The form {@code key} registered for {@code type}. */
    public Optional<PrintForm<?>> form(Class<?> type, String key) {
        return Optional.ofNullable(forms.getOrDefault(type, Map.of()).get(key));
    }

    /** Whether {@code subject} passes the form's role restriction (record read access is checked separately). */
    public boolean permits(PrintForm<?> form, AccessSubject subject) {
        Set<String> roles = form.spec().roles();
        if (roles.isEmpty() || subject.trusted()) return true;
        return roles.stream().map(AccessSubject::normalizeRole).anyMatch(subject.roles()::contains);
    }

    /** The template's format: {@code docx} or {@code xlsx}. */
    public String format(PrintForm<?> form) {
        return extension(templates.get(form).getFilename());
    }

    /** The file format {@code output} produces for the form: {@code pdf} or its template format. */
    public String format(PrintForm<?> form, PrintOutput output) {
        return output == PrintOutput.PDF ? "pdf" : format(form);
    }

    /** What the form downloads as by default. */
    public PrintOutput defaultOutput(PrintForm<?> form) {
        return form.spec().output();
    }

    /** Whether a {@link PdfConverter} is configured. */
    public boolean pdfAvailable() {
        return pdf != null;
    }

    /** Whether uploaded template overrides are enabled. */
    public boolean templateUploadsEnabled() {
        return store != null;
    }

    /** {@code catalogs/{name}} or {@code documents/{name}} of the form's entity. */
    public String scope(PrintForm<?> form) {
        return scopes.get(form.type());
    }


    /** Fills the form's effective template from {@code record} in its default output. */
    public PrintResult render(PrintForm<?> form, Object record) {
        return render(form, record, defaultOutput(form));
    }

    /** Fills the form's effective template from {@code record}, an instance of {@link PrintForm#type()}. */
    public PrintResult render(PrintForm<?> form, Object record, PrintOutput output) {
        return render(form, record, output, effectiveTemplate(form).content());
    }

    /**
     * Prints every record into one zip archive ({@code {key}.zip}); duplicate file names get a
     * {@code (2)}, {@code (3)}… suffix.
     */
    public PrintResult renderZip(PrintForm<?> form, List<?> records, PrintOutput output) {
        byte[] template = effectiveTemplate(form).content();
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        Set<String> used = new HashSet<>();
        try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
            for (Object record : records) {
                PrintResult file = render(form, record, output, template);
                zip.putNextEntry(new ZipEntry(unique(file.fileName(), used)));
                zip.write(file.content());
                zip.closeEntry();
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Could not write print archive", e);
        }
        return new PrintResult(bytes.toByteArray(), ZIP, form.key() + ".zip");
    }

    /** The template a render uses: the uploaded one when present, else the built-in one. */
    public TemplateFile effectiveTemplate(PrintForm<?> form) {
        requireRegistered(form);
        if (store != null) {
            Optional<PrintTemplateStore.StoredTemplate> stored = store.find(scope(form), form.key());
            if (stored.isPresent()) {
                PrintTemplateStore.StoredTemplate t = stored.get();
                return new TemplateFile(t.fileName(), t.content(), true, t.uploadedBy(), t.uploadedAt());
            }
        }
        return builtinTemplate(form);
    }

    /** The form's built-in template, ignoring any upload. */
    public TemplateFile builtinTemplate(PrintForm<?> form) {
        Resource template = requireRegistered(form);
        try (InputStream in = template.getInputStream()) {
            return new TemplateFile(template.getFilename(), in.readAllBytes(), false, null, null);
        } catch (IOException e) {
            throw new UncheckedIOException("Could not read print template " + template.getDescription(), e);
        }
    }

    /** Uploaded-template metadata by {@code scope/formKey} (content omitted). */
    public Map<String, PrintTemplateStore.StoredTemplate> uploadedTemplates() {
        Map<String, PrintTemplateStore.StoredTemplate> uploaded = new HashMap<>();
        if (store != null) store.list().forEach(t -> uploaded.put(t.scope() + "/" + t.formKey(), t));
        return uploaded;
    }

    /**
     * Replaces the form's template. The file must be a readable {@code .docx}/{@code .xlsx} of the
     * same format as the built-in template and no larger than the configured limit.
     *
     * @throws IllegalArgumentException when the file is rejected
     */
    public void uploadTemplate(PrintForm<?> form, String fileName, byte[] content, String uploadedBy) {
        requireRegistered(form);
        if (store == null) throw new IllegalStateException("Uploaded print templates are disabled");
        String expected = format(form);
        if (!expected.equals(extension(fileName))) {
            throw new IllegalArgumentException("Template for '" + form.key() + "' must be a ." + expected + " file");
        }
        if (content == null || content.length == 0) throw new IllegalArgumentException("Template file is empty");
        if (content.length > maxTemplateBytes) {
            throw new IllegalArgumentException("Template file exceeds " + maxTemplateBytes + " bytes");
        }
        try {
            if (expected.equals("docx")) {
                new XWPFDocument(new ByteArrayInputStream(content)).close();
            } else {
                new XSSFWorkbook(new ByteArrayInputStream(content)).close();
            }
        } catch (IOException | RuntimeException e) {
            throw new IllegalArgumentException("Not a readable ." + expected + " file", e);
        }
        store.save(scope(form), form.key(), baseName(fileName), content, uploadedBy);
    }

    /** Drops the uploaded template so the built-in one applies again; true when one existed. */
    public boolean resetTemplate(PrintForm<?> form) {
        requireRegistered(form);
        return store != null && store.delete(scope(form), form.key());
    }

    /** Display text of a bound value, matching how the UI shows it. */
    String text(Object value) {
        return switch (value) {
            case null -> "";
            case Ref<?> ref -> refs.resolve(ref).map(PrintService::recordLabel).orElse("");
            case PolyRef ref -> refs.resolve(ref).map(PrintService::recordLabel).orElse("");
            case Enum<?> constant -> enumLabel(constant);
            case CatalogObject record -> recordLabel(record);
            case DocumentObject record -> recordLabel(record);
            default -> value.toString();
        };
    }

    private PrintResult render(PrintForm<?> form, Object record, PrintOutput output, byte[] template) {
        if (output == PrintOutput.PDF && pdf == null) {
            throw new IllegalStateException("PDF output requires a PdfConverter");
        }
        TemplateModel model = new TemplateModel(this::text, form.spec().locale());
        form.fillInto(record, model);
        String format = format(form);
        byte[] filled = ("docx".equals(format) ? docx : xlsx).render(template, model);
        String name = form.fileNameOf(record);
        if (output == PrintOutput.PDF) {
            return new PrintResult(pdf.toPdf(filled, format), PDF, name + ".pdf");
        }
        return new PrintResult(filled, "docx".equals(format) ? DOCX : XLSX, name + "." + format);
    }

    private Resource requireRegistered(PrintForm<?> form) {
        Resource template = templates.get(form);
        if (template == null) throw new IllegalArgumentException("Unregistered print form " + form.key());
        return template;
    }

    private static String unique(String fileName, Set<String> used) {
        if (used.add(fileName)) return fileName;
        int dot = fileName.lastIndexOf('.');
        String stem = dot < 0 ? fileName : fileName.substring(0, dot);
        String ext = dot < 0 ? "" : fileName.substring(dot);
        for (int n = 2; ; n++) {
            String candidate = stem + " (" + n + ")" + ext;
            if (used.add(candidate)) return candidate;
        }
    }

    private static String recordLabel(Object record) {
        return switch (record) {
            case CatalogObject c -> c.getDescription() != null && !c.getDescription().isBlank()
                    ? c.getDescription() : String.valueOf(c.getCode());
            case DocumentObject d -> String.valueOf(d.getNumber());
            default -> record.toString();
        };
    }

    private static String enumLabel(Enum<?> constant) {
        try {
            EnumLabel label = constant.getDeclaringClass().getField(constant.name()).getAnnotation(EnumLabel.class);
            return label == null ? constant.name() : label.value();
        } catch (NoSuchFieldException e) {
            return constant.name();
        }
    }

    private static Resource template(PrintForm<?> form, ResourceLoader resources) {
        String configured = form.spec().template();
        List<String> candidates = configured != null
                ? List.of(configured)
                : List.of("classpath:/print/" + form.key() + ".docx", "classpath:/print/" + form.key() + ".xlsx");
        for (String location : candidates) {
            Resource resource = resources.getResource(location);
            if (resource.exists()) {
                String ext = extension(resource.getFilename());
                if (!ext.equals("docx") && !ext.equals("xlsx")) {
                    throw new IllegalStateException("Print form '" + form.key() + "' template must be .docx or .xlsx: "
                            + location);
                }
                return resource;
            }
        }
        throw new IllegalStateException("Print form '" + form.key() + "' template not found: "
                + String.join(" or ", candidates));
    }

    private static String baseName(String fileName) {
        String name = fileName.replace('\\', '/');
        name = name.substring(name.lastIndexOf('/') + 1);
        return name.length() > 255 ? name.substring(name.length() - 255) : name;
    }

    private static String extension(String filename) {
        if (filename == null || filename.lastIndexOf('.') < 0) return "";
        return filename.substring(filename.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT);
    }
}
