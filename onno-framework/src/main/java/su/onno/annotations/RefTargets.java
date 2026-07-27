package su.onno.annotations;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Declares the catalog/document types allowed in a {@link su.onno.types.PolyRef} field.
 *
 * <p>Use together with {@link Attribute} or {@link Dimension}. The metadata scanner rejects a
 * polymorphic reference without an explicit allowlist and rejects targets that are not onno
 * catalogs or documents.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.FIELD)
public @interface RefTargets {

    Class<?>[] value();
}
