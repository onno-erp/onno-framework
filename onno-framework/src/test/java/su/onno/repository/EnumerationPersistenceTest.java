package su.onno.repository;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** {@link EnumerationPersistence#fromNameOrId}: the one rule for reading a client's enum value. */
class EnumerationPersistenceTest {

    enum Status { NEW, APPROVED }

    enum Other { NEW }

    @Test
    void acceptsTheConstantName() {
        assertThat(EnumerationPersistence.fromNameOrId(Status.class, "APPROVED")).contains(Status.APPROVED);
        assertThat(EnumerationPersistence.fromNameOrId(Status.class, " NEW ")).contains(Status.NEW);
    }

    @Test
    void acceptsTheStoredIdAsUuidOrString() {
        UUID approved = EnumerationPersistence.resolveId(Status.class, Status.APPROVED);
        assertThat(EnumerationPersistence.fromNameOrId(Status.class, approved)).contains(Status.APPROVED);
        assertThat(EnumerationPersistence.fromNameOrId(Status.class, approved.toString())).contains(Status.APPROVED);
        assertThat(EnumerationPersistence.fromNameOrId(Status.class, approved.toString().toUpperCase()))
                .contains(Status.APPROVED);
    }

    @Test
    void acceptsItsOwnConstantOnly() {
        assertThat(EnumerationPersistence.fromNameOrId(Status.class, Status.NEW)).contains(Status.NEW);
        assertThat(EnumerationPersistence.fromNameOrId(Status.class, Other.NEW)).isEmpty();
    }

    @Test
    void rejectsWhatNamesNoConstant() {
        assertThat(EnumerationPersistence.fromNameOrId(Status.class, null)).isEmpty();
        assertThat(EnumerationPersistence.fromNameOrId(Status.class, "")).isEmpty();
        assertThat(EnumerationPersistence.fromNameOrId(Status.class, "REMOVED")).isEmpty();
        assertThat(EnumerationPersistence.fromNameOrId(Status.class, "new")).isEmpty();
        assertThat(EnumerationPersistence.fromNameOrId(Status.class, UUID.randomUUID())).isEmpty();
        // Another enum's id for a same-named constant is a foreign id, not this constant.
        assertThat(EnumerationPersistence.fromNameOrId(Status.class,
                EnumerationPersistence.resolveId(Other.class, Other.NEW))).isEmpty();
    }
}
