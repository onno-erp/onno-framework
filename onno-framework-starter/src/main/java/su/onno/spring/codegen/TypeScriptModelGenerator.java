package su.onno.spring.codegen;

import su.onno.metadata.AccumulationRegisterDescriptor;
import su.onno.metadata.AttributeDescriptor;
import su.onno.metadata.CatalogDescriptor;
import su.onno.metadata.ConstantDescriptor;
import su.onno.metadata.DefaultNamingStrategy;
import su.onno.metadata.DocumentDescriptor;
import su.onno.metadata.EnumerationDescriptor;
import su.onno.metadata.EnumerationValueDescriptor;
import su.onno.metadata.InformationRegisterDescriptor;
import su.onno.metadata.MetadataRegistry;
import su.onno.metadata.MetadataScanner;
import su.onno.metadata.TabularSectionDescriptor;
import su.onno.spring.AccumulationScanner;
import su.onno.spring.CatalogScanner;
import su.onno.spring.ConstantScanner;
import su.onno.spring.DocumentScanner;
import su.onno.spring.EnumerationScanner;
import su.onno.spring.InformationRegisterScanner;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Stream;

/**
 * Generates the TypeScript model of an onno app from its Java business model: one type per catalog,
 * document, tabular-section row, accumulation and information register (matching the logical JSON
 * the API returns), a value object per {@code @Enumeration} (constant → stored UUID), and
 * augmentations of {@code @onno/ui}'s {@code OnnoEntities}, {@code OnnoInformationRegisters} and
 * {@code OnnoConstants} (each {@code @Constant}'s value type) so its hooks and components are typed —
 * {@code useRecords("Orders")} returns {@code Order[]}, {@code customizeEntity("Orders", …)} only
 * accepts the Orders fields.
 *
 * <p>Run by the {@code su.onno.ui} Gradle plugin before the UI is bundled:
 * {@code TypeScriptModelGenerator <out.ts> <classesDir>[:<classesDir>…] [extraPackage…]}. The
 * packages to scan are the app's own top-level packages (derived from its compiled classes) plus
 * any extra packages given.</p>
 */
public final class TypeScriptModelGenerator {

    private TypeScriptModelGenerator() {}

    public static void main(String[] args) throws IOException {
        if (args.length < 2) {
            System.err.println("usage: TypeScriptModelGenerator <out.ts> <classesDir[:classesDir…]> [package…]");
            System.exit(2);
        }
        Path out = Path.of(args[0]);
        Set<String> packages = new LinkedHashSet<>();
        for (String dir : args[1].split(java.io.File.pathSeparator)) {
            if (!dir.isBlank()) packages.addAll(rootPackages(Path.of(dir)));
        }
        for (int i = 2; i < args.length; i++) packages.add(args[i]);
        String source = generate(buildRegistry(List.copyOf(packages)));
        Files.createDirectories(out.toAbsolutePath().getParent());
        if (!Files.exists(out) || !Files.readString(out).equals(source)) {
            Files.writeString(out, source, StandardCharsets.UTF_8);
        }
        System.out.println("[onno] generated " + out + " from packages " + packages);
    }

    /** Two-segment packages that hold compiled classes (com/example/… → com.example). */
    static Set<String> rootPackages(Path classesDir) throws IOException {
        Set<String> result = new TreeSet<>();
        if (!Files.isDirectory(classesDir)) return result;
        try (Stream<Path> files = Files.walk(classesDir)) {
            files.filter(p -> p.toString().endsWith(".class")).forEach(p -> {
                Path rel = classesDir.relativize(p).getParent();
                if (rel == null) return;
                int n = Math.min(2, rel.getNameCount());
                StringBuilder pkg = new StringBuilder();
                for (int i = 0; i < n; i++) {
                    if (i > 0) pkg.append('.');
                    pkg.append(rel.getName(i));
                }
                result.add(pkg.toString());
            });
        }
        return result;
    }

    static MetadataRegistry buildRegistry(List<String> packages) {
        MetadataRegistry registry = new MetadataRegistry();
        MetadataScanner scanner = new MetadataScanner(new DefaultNamingStrategy());
        for (Class<?> c : new CatalogScanner().scan(packages)) registry.registerCatalog(scanner.scan(c));
        for (Class<?> c : new DocumentScanner().scan(packages)) registry.registerDocument(scanner.scanDocument(c));
        for (Class<?> c : new AccumulationScanner().scan(packages)) registry.registerAccumulation(scanner.scanRegister(c));
        for (Class<?> c : new EnumerationScanner().scan(packages)) registry.registerEnumeration(scanner.scanEnumeration(c));
        for (Class<?> c : new InformationRegisterScanner().scan(packages)) {
            registry.registerInformationRegister(scanner.scanInformationRegister(c));
        }
        for (Class<?> c : new ConstantScanner().scan(packages)) registry.registerConstant(scanner.scanConstant(c));
        return registry;
    }

    /** The TypeScript module for a registry. Deterministic: entities and fields in a stable order. */
    public static String generate(MetadataRegistry registry) {
        StringBuilder ts = new StringBuilder();
        ts.append("""
                // Generated by onno from the Java business model — do not edit; regenerated on every build.
                // Types match the logical JSON the API returns; see @onno/ui for the hooks that use them.
                /* eslint-disable */
                import type { CatalogRecord, DocumentRecord, InformationRow, RefInfo, RegisterRow, TabularRow } from "@onno/ui";

                """);

        List<EnumerationDescriptor> enums = registry.allEnumerations().stream()
                .sorted(Comparator.comparing(e -> e.javaClass().getSimpleName())).toList();
        if (!enums.isEmpty()) ts.append("// ---- enumerations -------------------------------------------------------\n\n");
        for (EnumerationDescriptor e : enums) {
            String type = e.javaClass().getSimpleName();
            ts.append("/** ").append(doc(e.displayTitle())).append(" — the stored value is the constant's UUID. */\n");
            ts.append("export const ").append(type).append(" = {\n");
            for (EnumerationValueDescriptor v : e.values()) {
                ts.append("  ").append(v.name()).append(": \"").append(v.id()).append("\",\n");
            }
            ts.append("} as const;\n");
            ts.append("export type ").append(type).append(" = (typeof ").append(type).append(")[keyof typeof ")
                    .append(type).append("];\n");
            ts.append("/** Display labels (`@EnumLabel`) by stored value. */\n");
            ts.append("export const ").append(type).append("Labels: Record<").append(type).append(", string> = {\n");
            for (EnumerationValueDescriptor v : e.values()) {
                ts.append("  [").append(type).append('.').append(v.name()).append("]: ")
                        .append(json(v.label())).append(",\n");
            }
            ts.append("};\n\n");
        }

        List<String> entityEntries = new ArrayList<>();
        List<CatalogDescriptor> catalogs = registry.allCatalogs().stream()
                .sorted(Comparator.comparing(CatalogDescriptor::logicalName)).toList();
        if (!catalogs.isEmpty()) ts.append("// ---- catalogs -----------------------------------------------------------\n\n");
        for (CatalogDescriptor c : catalogs) {
            String type = c.javaClass().getSimpleName();
            Set<String> fields = new LinkedHashSet<>(List.of("code", "description"));
            writeSections(ts, registry, c.tabularSections(), fields);
            ts.append("/** ").append(doc(c.displayTitle())).append(" — `/catalogs/").append(route(c.logicalName())).append("` */\n");
            ts.append("export type ").append(type).append(" = CatalogRecord & {\n");
            writeAttributes(ts, registry, c.attributes(), fields, "");
            for (TabularSectionDescriptor s : c.tabularSections()) {
                ts.append("  ").append(s.name()).append("?: ").append(s.rowClass().getSimpleName()).append("[];\n");
            }
            ts.append("};\n\n");
            entityEntries.add(entityEntry(c.logicalName(), "catalogs", type, fields, temporal(List.of(), c.attributes())));
        }

        List<DocumentDescriptor> documents = registry.allDocuments().stream()
                .sorted(Comparator.comparing(DocumentDescriptor::logicalName)).toList();
        if (!documents.isEmpty()) ts.append("// ---- documents ----------------------------------------------------------\n\n");
        for (DocumentDescriptor d : documents) {
            String type = d.javaClass().getSimpleName();
            Set<String> fields = new LinkedHashSet<>(List.of("number", "date", "posted"));
            writeSections(ts, registry, d.tabularSections(), fields);
            ts.append("/** ").append(doc(d.displayTitle())).append(" — `/documents/").append(route(d.logicalName())).append("` */\n");
            ts.append("export type ").append(type).append(" = DocumentRecord & {\n");
            writeAttributes(ts, registry, d.attributes(), fields, "");
            for (TabularSectionDescriptor s : d.tabularSections()) {
                ts.append("  ").append(s.name()).append("?: ").append(s.rowClass().getSimpleName()).append("[];\n");
            }
            ts.append("};\n\n");
            entityEntries.add(entityEntry(d.logicalName(), "documents", type, fields, temporal(List.of("date"), d.attributes())));
        }

        List<AccumulationRegisterDescriptor> registers = registry.allRegisters().stream()
                .sorted(Comparator.comparing(AccumulationRegisterDescriptor::logicalName)).toList();
        if (!registers.isEmpty()) ts.append("// ---- registers ----------------------------------------------------------\n\n");
        for (AccumulationRegisterDescriptor r : registers) {
            String type = r.javaClass().getSimpleName();
            Set<String> fields = new LinkedHashSet<>();
            List<AttributeDescriptor> columns = new ArrayList<>(r.dimensions());
            columns.addAll(r.resources());
            ts.append("/** ").append(doc(r.displayTitle())).append(" — `/registers/").append(route(r.logicalName()))
                    .append("` (dimensions and resources of one movement or balance row) */\n");
            ts.append("export type ").append(type).append(" = RegisterRow & {\n");
            writeAttributes(ts, registry, columns, fields, "");
            ts.append("};\n\n");
            entityEntries.add(entityEntry(r.logicalName(), "registers", type, fields, temporal(List.of("period"), columns)));
        }

        List<String> informationEntries = new ArrayList<>();
        List<InformationRegisterDescriptor> informationRegisters = registry.allInformationRegisters().stream()
                .sorted(Comparator.comparing(InformationRegisterDescriptor::logicalName)).toList();
        if (!informationRegisters.isEmpty()) {
            ts.append("// ---- information registers ----------------------------------------------\n\n");
        }
        for (InformationRegisterDescriptor r : informationRegisters) {
            String type = r.javaClass().getSimpleName();
            Set<String> fields = new LinkedHashSet<>();
            List<AttributeDescriptor> columns = new ArrayList<>(r.dimensions());
            columns.addAll(r.resources());
            columns.addAll(r.attributes().stream().filter(a -> !a.secret()).toList());
            boolean periodic = r.periodicity() != su.onno.model.Periodicity.NONE;
            ts.append("/** ").append(doc(r.displayTitle())).append(" — information register")
                    .append(periodic ? " (" + r.periodicity().name().toLowerCase(Locale.ROOT) + ")" : "").append(" */\n");
            ts.append("export type ").append(type).append(" = InformationRow & {\n");
            writeAttributes(ts, registry, columns, fields, "");
            ts.append("};\n\n");
            informationEntries.add("    " + json(r.logicalName()) + ": { record: " + type + "; field: " + union(fields)
                    + "; periodic: " + periodic + " };\n");
        }

        List<String> constantEntries = new ArrayList<>();
        for (ConstantDescriptor c : registry.allConstants().stream()
                .sorted(Comparator.comparing(ConstantDescriptor::logicalName)).toList()) {
            EnumerationDescriptor enumeration = enumOf(registry, c.valueType());
            String value = enumeration != null ? enumeration.javaClass().getSimpleName()
                    : su.onno.types.Ref.class.isAssignableFrom(c.valueType()) ? "string" : tsType(c.valueType());
            constantEntries.add("    " + json(c.logicalName()) + ": { value: " + value + " };\n");
        }

        Set<String> roles = roles(registry);
        ts.append("// ---- roles ----------------------------------------------------------------\n\n");
        ts.append("/** Every role the model names in `@AccessControl`, plus the built-in ADMIN. */\n");
        ts.append("export type Role = ").append(union(roles)).append(";\n\n");

        ts.append("// ---- typed SDK ------------------------------------------------------------\n\n");
        ts.append("declare module \"@onno/ui\" {\n  interface OnnoEntities {\n");
        for (String e : entityEntries) ts.append(e);
        ts.append("  }\n  interface OnnoInformationRegisters {\n");
        for (String e : informationEntries) ts.append(e);
        ts.append("  }\n  interface OnnoConstants {\n");
        for (String e : constantEntries) ts.append(e);
        ts.append("  }\n  interface OnnoRoles {\n");
        for (String r : roles) ts.append("    ").append(json(r)).append(": true;\n");
        ts.append("  }\n}\n");
        return ts.toString();
    }

    /**
     * The roles the model names statically: every {@code @AccessControl} (read, write, post, unpost)
     * on an entity or register class, its superclasses and fields, plus {@code ADMIN}, the built-in
     * superuser — sorted. Roles that appear only at runtime (a command's {@code roles(...)}, the
     * configured users) are not visible here; an app adds those to {@code OnnoRoles} itself.
     */
    static Set<String> roles(MetadataRegistry registry) {
        Set<String> roles = new TreeSet<>(Set.of("ADMIN"));
        List<Class<?>> classes = new ArrayList<>();
        registry.allCatalogs().forEach(d -> classes.add(d.javaClass()));
        registry.allDocuments().forEach(d -> classes.add(d.javaClass()));
        registry.allRegisters().forEach(d -> classes.add(d.javaClass()));
        registry.allInformationRegisters().forEach(d -> classes.add(d.javaClass()));
        for (Class<?> type : classes) {
            for (Class<?> c = type; c != null && c != Object.class; c = c.getSuperclass()) {
                addRoles(roles, c.getAnnotation(su.onno.annotations.AccessControl.class));
                for (java.lang.reflect.Field f : c.getDeclaredFields()) {
                    addRoles(roles, f.getAnnotation(su.onno.annotations.AccessControl.class));
                }
            }
        }
        return roles;
    }

    private static void addRoles(Set<String> roles, su.onno.annotations.AccessControl access) {
        if (access == null) return;
        for (String[] names : List.of(access.readRoles(), access.writeRoles(), access.postRoles(), access.unpostRoles())) {
            for (String r : names) {
                if (r != null && !r.isBlank()) roles.add(r.trim());
            }
        }
    }

    private static void writeSections(StringBuilder ts, MetadataRegistry registry,
                                      List<TabularSectionDescriptor> sections, Set<String> fields) {
        for (TabularSectionDescriptor s : sections) {
            Set<String> rowFields = new LinkedHashSet<>();
            ts.append("/** A row of the `").append(s.name()).append("` tabular section. */\n");
            ts.append("export type ").append(s.rowClass().getSimpleName()).append(" = TabularRow & {\n");
            writeAttributes(ts, registry, s.attributes(), rowFields, "");
            ts.append("};\n\n");
            fields.add(s.name());
            for (String f : rowFields) fields.add(s.name() + "." + f);
        }
    }

    private static void writeAttributes(StringBuilder ts, MetadataRegistry registry,
                                        List<AttributeDescriptor> attributes, Set<String> fields, String indent) {
        for (AttributeDescriptor a : attributes) {
            String name = a.fieldName();
            fields.add(name);
            if (a.displayName() != null && !a.displayName().isBlank() && !a.displayName().equals(name)) {
                ts.append(indent).append("  /** ").append(doc(a.displayName())).append(" */\n");
            }
            EnumerationDescriptor enumeration = enumOf(registry, a.javaType());
            if (a.isRef() || !a.refTargets().isEmpty()) {
                String target = a.refTarget() == null ? "string" : json(a.refTarget());
                ts.append(indent).append("  ").append(name).append(": string | null;\n");
                ts.append(indent).append("  ").append(name).append("Display?: string;\n");
                ts.append(indent).append("  ").append(name).append("Ref?: RefInfo<").append(target).append(">;\n");
                ts.append(indent).append("  ").append(name).append("Code?: string;\n");
                ts.append(indent).append("  ").append(name).append("Avatar?: string;\n");
                ts.append(indent).append("  ").append(name).append("Color?: string;\n");
                ts.append(indent).append("  ").append(name).append("Restricted?: boolean;\n");
            } else if (enumeration != null) {
                ts.append(indent).append("  ").append(name).append(": ")
                        .append(enumeration.javaClass().getSimpleName()).append(" | null;\n");
                ts.append(indent).append("  ").append(name).append("Display?: string;\n");
                ts.append(indent).append("  ").append(name).append("Color?: string;\n");
            } else {
                ts.append(indent).append("  ").append(name).append(": ").append(tsType(a.javaType()))
                        .append(a.javaType().isPrimitive() ? "" : " | null").append(";\n");
            }
        }
    }

    private static EnumerationDescriptor enumOf(MetadataRegistry registry, Class<?> type) {
        if (type == null || !type.isEnum()) return null;
        return registry.allEnumerations().stream().filter(e -> e.javaClass() == type).findFirst().orElse(null);
    }

    static String tsType(Class<?> t) {
        if (t == null) return "unknown";
        if (t == String.class || t == java.util.UUID.class || t == char.class || t == Character.class) return "string";
        if (t == boolean.class || t == Boolean.class) return "boolean";
        if (t.isPrimitive() || Number.class.isAssignableFrom(t)) return "number";
        if (java.time.temporal.Temporal.class.isAssignableFrom(t)) return "string";
        if (t.isEnum()) {
            StringBuilder union = new StringBuilder();
            for (Object c : t.getEnumConstants()) {
                if (!union.isEmpty()) union.append(" | ");
                union.append(json(((Enum<?>) c).name()));
            }
            return union.isEmpty() ? "string" : union.toString();
        }
        return "unknown";
    }

    /** The entity's date/time fields: its system date field plus every temporal attribute. */
    private static Set<String> temporal(List<String> system, List<AttributeDescriptor> attributes) {
        Set<String> out = new LinkedHashSet<>(system);
        for (AttributeDescriptor a : attributes) {
            if (a.javaType() != null && java.time.temporal.Temporal.class.isAssignableFrom(a.javaType())) {
                out.add(a.fieldName());
            }
        }
        return out;
    }

    private static String union(Set<String> names) {
        return names.isEmpty() ? "never" : String.join(" | ", names.stream().map(TypeScriptModelGenerator::json).toList());
    }

    private static String entityEntry(String logicalName, String kind, String type, Set<String> fields, Set<String> dates) {
        return "    " + json(logicalName) + ": { kind: \"" + kind + "\"; route: \"/" + kind + "/" + route(logicalName)
                + "\"; record: " + type + "; field: " + union(fields) + "; dateField: " + union(dates) + " };\n";
    }

    /** Logical name → route segment ("Stock Receipts" → "stock_receipts"), as the server routes it. */
    static String route(String logicalName) {
        String normalized = logicalName.replace(" ", "");
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < normalized.length(); i++) {
            char c = normalized.charAt(i);
            if (Character.isUpperCase(c) && i > 0) sb.append('_');
            sb.append(Character.toLowerCase(c));
        }
        return sb.toString().toLowerCase(Locale.ROOT);
    }

    private static String json(String s) {
        StringBuilder out = new StringBuilder("\"");
        for (char c : s.toCharArray()) {
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                default -> out.append(c);
            }
        }
        return out.append('"').toString();
    }

    private static String doc(String s) {
        return s == null ? "" : s.replace("*/", "*\\/");
    }
}
