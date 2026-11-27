package su.onno.validation;

import jakarta.validation.Constraint;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import jakarta.validation.Payload;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Null;
import jakarta.validation.constraints.Past;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import org.junit.jupiter.api.Test;
import su.onno.annotations.Attribute;
import su.onno.annotations.Catalog;
import su.onno.annotations.TabularSection;
import su.onno.metadata.AttributeDescriptor;
import su.onno.metadata.AttributeDescriptor.Constraints.Rule;
import su.onno.metadata.CatalogDescriptor;
import su.onno.metadata.DefaultNamingStrategy;
import su.onno.metadata.MetadataScanner;
import su.onno.model.CatalogObject;
import su.onno.model.TabularSectionRow;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Jakarta Bean Validation on onno entities: violations land in {@link ValidationErrors} under the
 * field (rows as {@code section[i].field}, class-level as form errors), the metadata mirrors the
 * representable constraints, {@link AttributeValidator} does not repeat them, and an
 * {@code @Attribute} flag that contradicts a Jakarta annotation fails the scan.
 */
class BeanValidationTest {

    private final MetadataScanner scanner = new MetadataScanner(new DefaultNamingStrategy());

    @Test
    void reportsEveryViolationUnderItsField() {
        Supplier supplier = invalidSupplier();

        ValidationErrors errors = new ValidationErrors();
        BeanValidation.validate(supplier, errors);

        assertThat(errors.fieldErrors()).containsOnlyKeys(
                "name", "taxCode", "email", "rating", "founded", "employees", "lines[1].amount");
        assertThat(errors.fieldErrors().get("employees")).containsExactly("must be even");
        assertThat(errors.formErrors()).containsExactly("rating requires an email");
    }

    @Test
    void cascadedRowsAreNotReportedTwice() {
        CascadingSupplier supplier = new CascadingSupplier();
        SupplierLine bad = new SupplierLine();
        bad.amount = BigDecimal.ZERO;
        supplier.lines.add(bad);

        ValidationErrors errors = new ValidationErrors();
        BeanValidation.validate(supplier, errors);

        assertThat(errors.fieldErrors()).containsOnlyKeys("rows[0].amount");
        assertThat(errors.fieldErrors().get("rows[0].amount")).hasSize(1);
    }

    @Test
    void metadataMirrorsTheRepresentableConstraints() {
        CatalogDescriptor desc = scanner.scan(Supplier.class);

        AttributeDescriptor name = attr(desc, "name");
        assertThat(name.required()).isTrue();
        assertThat(name.constraints().beanValidated(Rule.REQUIRED)).isTrue();

        AttributeDescriptor taxCode = attr(desc, "taxCode");
        assertThat(taxCode.length()).isEqualTo(20); // storage stays @Attribute(length)
        assertThat(taxCode.constraints().minLength()).isEqualTo(5);
        assertThat(taxCode.constraints().maxLength()).isEqualTo(12);

        assertThat(attr(desc, "email").constraints().email()).isTrue();
        assertThat(attr(desc, "rating").constraints().min()).isEqualTo(1);
        // An exclusive bound is left to the validator: the client is never told a bound it would misstate.
        AttributeDescriptor amount = desc.tabularSections().get(0).attributes().get(0);
        assertThat(amount.constraints().hasMin()).isFalse();
    }

    @Test
    void attributeValidatorLeavesJakartaRulesToTheProvider() {
        CatalogDescriptor desc = scanner.scan(Supplier.class);
        Map<String, Object> body = new HashMap<>();
        body.put("name", " ");
        body.put("taxCode", "1234567890123"); // 13 > @Size(max = 12)
        body.put("legacyCode", "x"); // deprecated @Attribute(minLength = 3): still enforced here

        ValidationErrors errors = new ValidationErrors();
        new AttributeValidator().validate(body, desc.attributes(), errors);

        assertThat(errors.fieldErrors()).containsOnlyKeys("legacyCode");
    }

    @Test
    void contradictoryAttributeFlagFailsTheScan() {
        assertThatThrownBy(() -> scanner.scan(ConflictingMin.class))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("ConflictingMin.qty")
                .hasMessageContaining("@Attribute(min = 5.0)");
        assertThatThrownBy(() -> scanner.scan(SizeAboveColumn.class))
                .hasMessageContaining("@Size(max = 300) exceeds the column length 255")
                .hasMessageContaining("@Attribute(length = 300)");
        assertThatThrownBy(() -> scanner.scan(RequiredButNull.class))
                .hasMessageContaining("@Attribute(required = true) contradicts @Null");
    }

    @Test
    void agreeingDeclarationsAreAccepted() {
        CatalogDescriptor desc = scanner.scan(AgreeingMin.class);

        assertThat(attr(desc, "qty").constraints().min()).isEqualTo(5);
        assertThat(attr(desc, "qty").constraints().beanValidated(Rule.MIN)).isTrue();
    }

    @Test
    void declaresConstraintsFindsAnnotatedFields() {
        assertThat(BeanValidation.declaresConstraints(Supplier.class)).isTrue();
        assertThat(BeanValidation.declaresConstraints(Plain.class)).isFalse();
    }

    private static Supplier invalidSupplier() {
        Supplier supplier = new Supplier();
        supplier.name = "";
        supplier.taxCode = "123";
        supplier.email = "not-an-email";
        supplier.rating = 0;
        supplier.founded = LocalDate.now().plusYears(1);
        supplier.employees = 3;
        SupplierLine good = new SupplierLine();
        good.amount = BigDecimal.ONE;
        SupplierLine bad = new SupplierLine();
        bad.amount = BigDecimal.valueOf(-1);
        supplier.lines.add(good);
        supplier.lines.add(bad);
        return supplier;
    }

    private static AttributeDescriptor attr(CatalogDescriptor desc, String field) {
        return desc.attributes().stream().filter(a -> a.fieldName().equals(field)).findFirst().orElseThrow();
    }

    // ----- fixtures -----

    @Target(ElementType.FIELD)
    @Retention(RetentionPolicy.RUNTIME)
    @Constraint(validatedBy = EvenValidator.class)
    @interface Even {
        String message() default "must be even";
        Class<?>[] groups() default {};
        Class<? extends Payload>[] payload() default {};
    }

    public static class EvenValidator implements ConstraintValidator<Even, Integer> {
        @Override
        public boolean isValid(Integer value, ConstraintValidatorContext context) {
            return value == null || value % 2 == 0;
        }
    }

    @Target(ElementType.TYPE)
    @Retention(RetentionPolicy.RUNTIME)
    @Constraint(validatedBy = RatedHasEmailValidator.class)
    @interface RatedHasEmail {
        String message() default "rating requires an email";
        Class<?>[] groups() default {};
        Class<? extends Payload>[] payload() default {};
    }

    public static class RatedHasEmailValidator implements ConstraintValidator<RatedHasEmail, Supplier> {
        @Override
        public boolean isValid(Supplier value, ConstraintValidatorContext context) {
            return value.rating == null || (value.email != null && value.email.contains("@"));
        }
    }

    @Catalog(name = "Bv Suppliers")
    @RatedHasEmail
    public static class Supplier extends CatalogObject {
        @Attribute @NotBlank String name;
        @Attribute(length = 20) @Size(min = 5, max = 12) String taxCode;
        @Attribute @Email String email;
        @Attribute @Min(1) Integer rating;
        @Attribute @Past LocalDate founded;
        @Attribute @Even Integer employees;
        @SuppressWarnings("deprecation")
        @Attribute(minLength = 3) String legacyCode;
        @TabularSection List<SupplierLine> lines = new ArrayList<>();
    }

    public static class SupplierLine extends TabularSectionRow {
        @Attribute @Positive BigDecimal amount;
    }

    @Catalog(name = "Bv Cascading")
    public static class CascadingSupplier extends CatalogObject {
        @TabularSection(name = "rows") @Valid List<SupplierLine> lines = new ArrayList<>();
    }

    @Catalog(name = "Bv Conflicting Min")
    @SuppressWarnings("deprecation")
    public static class ConflictingMin extends CatalogObject {
        @Attribute(min = 5) @Min(1) Integer qty;
    }

    @Catalog(name = "Bv Agreeing Min")
    @SuppressWarnings("deprecation")
    public static class AgreeingMin extends CatalogObject {
        @Attribute(min = 5) @Min(5) Integer qty;
    }

    @Catalog(name = "Bv Size Above Column")
    public static class SizeAboveColumn extends CatalogObject {
        @Attribute @Size(max = 300) String note;
    }

    @Catalog(name = "Bv Required Null")
    @SuppressWarnings("deprecation")
    public static class RequiredButNull extends CatalogObject {
        @Attribute(required = true) @Null String ghost;
    }

    @Catalog(name = "Bv Plain")
    public static class Plain extends CatalogObject {
        @Attribute String name;
    }
}
