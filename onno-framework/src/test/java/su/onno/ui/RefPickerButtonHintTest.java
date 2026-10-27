package su.onno.ui;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The ref picker's open/clear button hints (#409) are carried by {@link FieldHint} as nullable
 * values, so "not set" stays distinguishable from an explicit choice and the starter can apply the
 * defaults.
 */
class RefPickerButtonHintTest {

    @Test
    void explicitChoicesAreRecordedAndUnsetStaysNull() {
        EntityConfigBuilder<Object> fields = new EntityConfigBuilder<>();
        fields.field("customer").openButton(false).clearButton(true);
        fields.field("warehouse").clearButton(false);
        fields.field("lines.book").openButton(true);
        fields.field("note").label("Note");

        var hints = fields.buildFieldHints();
        assertThat(hints.get("customer").openButton()).isFalse();
        assertThat(hints.get("customer").clearButton()).isTrue();
        assertThat(hints.get("warehouse").openButton()).isNull();
        assertThat(hints.get("warehouse").clearButton()).isFalse();
        assertThat(hints.get("lines.book").openButton()).isTrue();
        assertThat(hints.get("note").openButton()).isNull();
        assertThat(hints.get("note").clearButton()).isNull();
    }

    @Test
    void thePreviousConstructorShapeLeavesTheButtonHintsUnset() {
        FieldHint hint = new FieldHint(null, null, null, null, null, null, null, null, null, null, "Label",
                null, null, null, true);
        assertThat(hint.label()).isEqualTo("Label");
        assertThat(hint.uniqueWithinSection()).isTrue();
        assertThat(hint.openButton()).isNull();
        assertThat(hint.clearButton()).isNull();
        assertThat(FieldHint.empty().openButton()).isNull();
        assertThat(FieldHint.empty().clearButton()).isNull();
    }
}
