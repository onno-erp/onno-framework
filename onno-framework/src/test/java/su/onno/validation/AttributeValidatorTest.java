package su.onno.validation;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import su.onno.metadata.AttributeDescriptor;

/**
 * The two body-map validation modes of the generic write path: {@code validate} (create — every
 * declared attribute is checked, absent required fields fail) versus {@code validatePartial}
 * (update — only submitted keys are checked, because an absent key means "leave the stored value
 * unchanged", while a key explicitly present as null/blank would clear the column and must fail).
 */
class AttributeValidatorTest {

    private final AttributeValidator validator = new AttributeValidator();

    private static AttributeDescriptor required(String field) {
        return new AttributeDescriptor(field, field, field, String.class, 0, true, false, null,
                0, 0, true, true, true, 0, null, null, null,
                AttributeDescriptor.Constraints.NONE, false);
    }

    private static AttributeDescriptor optional(String field) {
        return new AttributeDescriptor(field, field, field, String.class, 0, false, false, null,
                0, 0, true, true, true, 0, null, null, null,
                AttributeDescriptor.Constraints.NONE, false);
    }

    private final List<AttributeDescriptor> attrs = List.of(required("customer"), optional("note"));

    @Test
    void fullValidationFlagsAbsentRequiredField() {
        ValidationErrors errors = new ValidationErrors();
        validator.validate(Map.of("note", "hi"), attrs, errors);

        assertThat(errors.fieldErrors()).containsKey("customer");
    }

    @Test
    void partialValidationSkipsAbsentRequiredField() {
        ValidationErrors errors = new ValidationErrors();
        validator.validatePartial(Map.of("note", "hi"), attrs, errors);

        assertThat(errors.isEmpty()).isTrue();
    }

    @Test
    void partialValidationStillRejectsExplicitlyClearedRequiredField() {
        Map<String, Object> body = new HashMap<>();
        body.put("customer", null); // present key with null value = "clear it" — not allowed
        ValidationErrors errors = new ValidationErrors();
        validator.validatePartial(body, attrs, errors);

        assertThat(errors.fieldErrors()).containsKey("customer");
    }

    @Test
    void partialValidationChecksConstraintsOnSubmittedFields() {
        AttributeDescriptor bounded = new AttributeDescriptor("note", "Note", "note", String.class,
                5, false, false, null, 0, 0, true, true, true, 0, null, null, null,
                AttributeDescriptor.Constraints.NONE, false);
        ValidationErrors errors = new ValidationErrors();
        validator.validatePartial(Map.of("note", "way too long"), List.of(bounded), errors);

        assertThat(errors.fieldErrors()).containsKey("note");
    }
}
