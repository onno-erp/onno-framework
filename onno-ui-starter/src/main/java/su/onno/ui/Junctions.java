package su.onno.ui;

import su.onno.metadata.AttributeDescriptor;
import su.onno.metadata.CatalogDescriptor;
import su.onno.metadata.InformationRegisterDescriptor;
import su.onno.metadata.MetadataRegistry;

import java.util.ArrayList;
import java.util.List;

/**
 * Resolves the junction class behind a {@link RelatedList} panel into a uniform view, regardless
 * of whether the relationship is stored in a <em>join catalog</em> or an <em>information
 * register</em> (1C's idiomatic M:N junction — two ref dimensions). Both shapes expose the same
 * three things a panel needs: a logical name, the field list to resolve {@code via}/{@code
 * display}/columns against, and the descriptor to read rows from / access-check.
 *
 * <p>Catalog junctions are fully editable (add/remove create/delete join rows). Register junctions
 * are <em>read-only</em> for now: information registers have no generic write REST surface, so a
 * register-backed panel renders rows (both directions) but offers no inline add/remove. In 1C an
 * independent information register would be directly editable and one subordinate to a recorder
 * read-only; supporting the editable case here is a clean follow-up once info registers gain a
 * write endpoint.</p>
 */
final class Junctions {

    enum Kind { CATALOG, REGISTER }

    /**
     * A junction resolved to the fields a panel resolves against and the descriptor it reads from.
     * Exactly one of {@code catalog}/{@code register} is non-null, matching {@code kind}.
     */
    record Junction(Kind kind, String logicalName, List<AttributeDescriptor> fields,
                    CatalogDescriptor catalog, InformationRegisterDescriptor register) {

        boolean isRegister() {
            return kind == Kind.REGISTER;
        }
    }

    private Junctions() {}

    /**
     * The junction backing {@code junctionClass} — a registered catalog or information register —
     * or {@code null} when the class is neither (the panel is then dropped, degrading gracefully).
     * For a register the fields are its dimensions + attributes + resources, so {@code via}/{@code
     * display} (always ref dimensions) and any extra columns all resolve from one list.
     */
    static Junction resolve(MetadataRegistry registry, Class<?> junctionClass) {
        CatalogDescriptor catalog = registry.allCatalogs().stream()
                .filter(c -> c.javaClass().equals(junctionClass))
                .findFirst().orElse(null);
        if (catalog != null) {
            return new Junction(Kind.CATALOG, catalog.logicalName(), catalog.attributes(), catalog, null);
        }
        InformationRegisterDescriptor register = registry.allInformationRegisters().stream()
                .filter(r -> r.javaClass().equals(junctionClass))
                .findFirst().orElse(null);
        if (register != null) {
            List<AttributeDescriptor> fields = new ArrayList<>(register.dimensions());
            fields.addAll(register.attributes());
            fields.addAll(register.resources());
            return new Junction(Kind.REGISTER, register.logicalName(), fields, null, register);
        }
        return null;
    }

    /** A ref field on the junction by field name, or {@code null} if absent or not a ref. */
    static AttributeDescriptor refField(Junction junction, String fieldName) {
        if (fieldName == null) {
            return null;
        }
        return junction.fields().stream()
                .filter(a -> a.fieldName().equals(fieldName) && a.isRef())
                .findFirst().orElse(null);
    }

    /** Any field on the junction by field name (for an explicit columns() entry), or {@code null}. */
    static AttributeDescriptor field(Junction junction, String fieldName) {
        return junction.fields().stream()
                .filter(a -> a.fieldName().equals(fieldName))
                .findFirst().orElse(null);
    }
}
