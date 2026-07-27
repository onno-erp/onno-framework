package su.onno.types;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PolyRefTest {

    @Test
    void externalForm_roundTripsConcreteTypeAndId() {
        UUID id = UUID.randomUUID();

        PolyRef reference = PolyRef.of(String.class, id);

        assertThat(reference.externalForm()).isEqualTo(String.class.getName() + "|" + id);
        assertThat(PolyRef.parse(reference.externalForm())).isEqualTo(reference);
        assertThat(reference.toString()).isEqualTo(reference.externalForm());
    }

    @Test
    void parse_rejectsMalformedAndUnknownTypes() {
        assertThatThrownBy(() -> PolyRef.parse("not-a-reference"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Invalid polymorphic reference");
        assertThatThrownBy(() -> PolyRef.parse("example.Missing|" + UUID.randomUUID()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unknown polymorphic reference type");
    }
}
