package su.onno.spring.codegen;

import su.onno.annotations.AccumulationRegister;
import su.onno.annotations.Attribute;
import su.onno.annotations.Dimension;
import su.onno.annotations.Document;
import su.onno.annotations.Resource;
import su.onno.metadata.DefaultNamingStrategy;
import su.onno.metadata.MetadataRegistry;
import su.onno.metadata.MetadataScanner;
import su.onno.model.AccumulationRecord;
import su.onno.model.AccumulationType;
import su.onno.model.DocumentObject;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class TypeScriptModelGeneratorTest {

    private final String ts = TypeScriptModelGenerator.generate(
            TypeScriptModelGenerator.buildRegistry(List.of("su.onno.spring.fixtures")));

    @Test
    void enumerationsBecomeUuidConstantsWithLabels() {
        assertThat(ts).contains("export const TestServiceCategory = {")
                .contains("export type TestServiceCategory = (typeof TestServiceCategory)[keyof typeof TestServiceCategory];")
                .contains("export const TestServiceCategoryLabels: Record<TestServiceCategory, string>")
                .containsPattern("  [A-Z_]+: \"[0-9a-f-]{36}\",");
    }

    @Test
    void catalogsAreTypedRowsWithEnumAndScalarFields() {
        assertThat(ts).contains("export type TestService = CatalogRecord & {")
                .contains("  name: string | null;")
                .contains("  category: TestServiceCategory | null;")
                .contains("  categoryDisplay?: string;");
    }

    @Test
    void documentsCarryTheirTabularSectionsAndRefCompanions() {
        assertThat(ts).contains("export type TestStarterInvoice = DocumentRecord & {")
                .contains("= TabularRow & {")
                .containsPattern("\\?: TestStarterLine\\[\\];");
    }

    @Test
    void theSdkIsAugmentedWithEveryEntityByLogicalName() {
        assertThat(ts).contains("declare module \"@onno/ui\" {")
                .contains("\"TestServices\": { kind: \"catalogs\"; route: \"/catalogs/test_services\"; record: TestService;")
                .contains("field: \"code\" | \"description\" | \"name\" | \"category\"");
    }

    /** Mirrors the example's ScheduleEvent: a document with temporal attributes besides its date. */
    @Document(name = "GenScheduleEvents")
    static class GenScheduleEvent extends DocumentObject {
        @Attribute
        private LocalDateTime startsAt;
        @Attribute
        private LocalDate dueOn;
        @Attribute(length = 50)
        private String title;
    }

    @AccumulationRegister(name = "GenStock", type = AccumulationType.BALANCE)
    static class GenStock extends AccumulationRecord {
        @Dimension
        private UUID warehouse;
        @Resource
        private BigDecimal quantity;
    }

    @Test
    void everyEntityDeclaresItsDateFields() {
        assertThat(ts).contains("\"TestServices\": { kind: \"catalogs\";")
                .containsPattern("\"TestServices\": \\{[^\\n]*; dateField: never \\};")
                .containsPattern("\"StarterInvoices\": \\{[^\\n]*; dateField: \"date\" \\};");

        MetadataScanner scanner = new MetadataScanner(new DefaultNamingStrategy());
        MetadataRegistry registry = new MetadataRegistry();
        registry.registerDocument(scanner.scanDocument(GenScheduleEvent.class));
        registry.registerAccumulation(scanner.scanRegister(GenStock.class));
        String generated = TypeScriptModelGenerator.generate(registry);
        assertThat(generated)
                .containsPattern("\"GenScheduleEvents\": \\{ kind: \"documents\";[^\\n]*"
                        + "; dateField: \"date\" \\| \"startsAt\" \\| \"dueOn\" \\};")
                .containsPattern("\"GenStock\": \\{ kind: \"registers\";[^\\n]*; dateField: \"period\" \\};");
    }

    @su.onno.annotations.InformationRegister(name = "GenRates", title = "Rates",
            periodicity = su.onno.model.Periodicity.DAY)
    static class GenRate extends su.onno.model.InformationRecord {
        @Dimension
        private String currency;
        @Resource
        private BigDecimal rate;
        @Attribute(secret = true)
        private String apiKey;
    }

    @su.onno.annotations.Constant(name = "Gen Shop Name")
    static class GenShopName {
        private String value;
    }

    @su.onno.annotations.Constant(name = "Gen Auto Archive")
    static class GenAutoArchive {
        private Boolean value;
    }

    @Test
    void informationRegistersAndConstantsAreTyped() {
        MetadataScanner scanner = new MetadataScanner(new DefaultNamingStrategy());
        MetadataRegistry registry = new MetadataRegistry();
        registry.registerInformationRegister(scanner.scanInformationRegister(GenRate.class));
        registry.registerConstant(scanner.scanConstant(GenShopName.class));
        registry.registerConstant(scanner.scanConstant(GenAutoArchive.class));
        String generated = TypeScriptModelGenerator.generate(registry);
        assertThat(generated)
                .contains("export type GenRate = InformationRow & {")
                .contains("  currency: string | null;")
                .contains("  rate: number | null;")
                .doesNotContain("apiKey")
                .contains("  interface OnnoInformationRegisters {\n"
                        + "    \"GenRates\": { record: GenRate; field: \"currency\" | \"rate\"; periodic: true };\n")
                .contains("  interface OnnoConstants {\n"
                        + "    \"Gen Auto Archive\": { value: boolean };\n"
                        + "    \"Gen Shop Name\": { value: string };\n");
    }

    @Document(name = "GenGuarded")
    @su.onno.annotations.AccessControl(readRoles = {"SALES", "MANAGER"}, writeRoles = "MANAGER",
            postRoles = "ACCOUNTANT")
    static class GenGuarded extends DocumentObject {
        @Attribute
        @su.onno.annotations.AccessControl(readRoles = "AUDITOR")
        private BigDecimal margin;
    }

    @Test
    void rolesAreTheOnesTheModelNamesStaticallyPlusAdmin() {
        MetadataScanner scanner = new MetadataScanner(new DefaultNamingStrategy());
        MetadataRegistry registry = new MetadataRegistry();
        registry.registerDocument(scanner.scanDocument(GenGuarded.class));
        String generated = TypeScriptModelGenerator.generate(registry);
        assertThat(generated)
                .contains("export type Role = \"ACCOUNTANT\" | \"ADMIN\" | \"AUDITOR\" | \"MANAGER\" | \"SALES\";")
                .contains("  interface OnnoRoles {\n    \"ACCOUNTANT\": true;\n    \"ADMIN\": true;\n");
        assertThat(TypeScriptModelGenerator.generate(new MetadataRegistry())).contains("export type Role = \"ADMIN\";");
    }

    @Test
    void outputIsDeterministic() {
        assertThat(TypeScriptModelGenerator.generate(
                TypeScriptModelGenerator.buildRegistry(List.of("su.onno.spring.fixtures")))).isEqualTo(ts);
    }

    @Test
    void javaTypesMapToJsonTypes() {
        assertThat(TypeScriptModelGenerator.tsType(java.math.BigDecimal.class)).isEqualTo("number");
        assertThat(TypeScriptModelGenerator.tsType(java.time.LocalDateTime.class)).isEqualTo("string");
        assertThat(TypeScriptModelGenerator.tsType(boolean.class)).isEqualTo("boolean");
        assertThat(TypeScriptModelGenerator.tsType(java.util.UUID.class)).isEqualTo("string");
        assertThat(TypeScriptModelGenerator.route("Stock Receipts")).isEqualTo("stock_receipts");
    }
}
