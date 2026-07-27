package su.onno.spring;

import su.onno.repository.EnumerationPersistence;

import org.junit.jupiter.api.Test;
import org.springframework.core.convert.TypeDescriptor;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EnumUuidConvertersTest {

    enum Status {
        NEW,
        DONE
    }

    @Test
    void stringReaderAcceptsUuidTextAndRejectsLegacyConstantNames() {
        EnumUuidConverters.StringToEnum converter =
                new EnumUuidConverters.StringToEnum(Set.of(Status.class));
        TypeDescriptor source = TypeDescriptor.valueOf(String.class);
        TypeDescriptor target = TypeDescriptor.valueOf(Status.class);

        assertThat(converter.convert(
                EnumerationPersistence.resolveId(Status.class, Status.DONE).toString(),
                source,
                target)).isEqualTo(Status.DONE);
        assertThatThrownBy(() -> converter.convert("DONE", source, target))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
