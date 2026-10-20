package su.onno.print;

import su.onno.access.AccessSubject;
import su.onno.annotations.EnumLabel;
import su.onno.model.CatalogObject;
import su.onno.model.DocumentObject;
import su.onno.types.PolyRef;
import su.onno.types.Ref;
import su.onno.types.RefResolver;

import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * The registered {@link PrintForm}s and their rendering. Startup fails on a duplicate key for the
 * same type, a form for a class that is not a catalog or document, or a missing/unsupported template.
 */
public class PrintService {

    static final String DOCX = "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
    static final String XLSX = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";

    private final Map<Class<?>, Map<String, PrintForm<?>>> forms = new HashMap<>();
    private final Map<PrintForm<?>, Resource> templates = new HashMap<>();
    private final RefResolver refs;
    private final TemplateEngine docx = new DocxTemplateEngine();
    private final TemplateEngine xlsx = new XlsxTemplateEngine();

    /**
     * @param forms     the print form beans
     * @param printable catalog and document classes a form may target
     * @param refs      resolves {@code Ref} values to their display name
     * @param resources resolves template locations
     */
    public PrintService(List<PrintForm<?>> forms, Set<Class<?>> printable, RefResolver refs,
                        ResourceLoader resources) {
        this.refs = refs;
        for (PrintForm<?> form : forms) {
            if (!printable.contains(form.type())) {
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
            templates.put(form, template(form, resources));
        }
    }

    /** The forms registered for {@code type}, ordered by label. */
    public List<PrintForm<?>> formsFor(Class<?> type) {
        return forms.getOrDefault(type, Map.of()).values().stream()
                .sorted(Comparator.comparing(PrintForm::label))
                .toList();
    }

    /** The form {@code key} registered for {@code type}. */
    public Optional<PrintForm<?>> form(Class<?> type, String key) {
        return Optional.ofNullable(forms.getOrDefault(type, Map.of()).get(key));
    }

    /** Whether {@code subject} passes the form's role restriction (record read access is checked separately). */
    public boolean permits(PrintForm<?> form, AccessSubject subject) {
        Set<String> roles = form.spec().roles();
        if (roles.isEmpty() || subject.superuser()) return true;
        return roles.stream().map(AccessSubject::normalizeRole).anyMatch(subject.roles()::contains);
    }

    /** The template's format: {@code docx} or {@code xlsx}. */
    public String format(PrintForm<?> form) {
        return extension(templates.get(form).getFilename());
    }

    /** Lucide icon name of the form's print action. */
    public String icon(PrintForm<?> form) {
        return form.spec().icon();
    }

    /** Fills the form's template from {@code record}, an instance of {@link PrintForm#type()}. */
    public PrintResult render(PrintForm<?> form, Object record) {
        Resource template = templates.get(form);
        if (template == null) throw new IllegalArgumentException("Unregistered print form " + form.key());
        TemplateModel model = new TemplateModel(this::text, form.spec().locale());
        form.fillInto(record, model);
        String format = format(form);
        byte[] bytes;
        try (InputStream in = template.getInputStream()) {
            bytes = in.readAllBytes();
        } catch (IOException e) {
            throw new UncheckedIOException("Could not read print template " + template.getDescription(), e);
        }
        TemplateEngine engine = "docx".equals(format) ? docx : xlsx;
        return new PrintResult(engine.render(bytes, model), "docx".equals(format) ? DOCX : XLSX,
                form.fileNameOf(record) + "." + format);
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

    private static String extension(String filename) {
        if (filename == null || filename.lastIndexOf('.') < 0) return "";
        return filename.substring(filename.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT);
    }
}
