package su.onno.period;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks the field of a {@code @Document} that names its organization (legal entity, branch) for
 * period close. A close of {@link PeriodScope#organization(Object)} then applies only to documents
 * whose field holds that organization — usually a reference to an organizations catalog.
 *
 * <pre>
 * &#64;PeriodCloseOrganization
 * &#64;Attribute(displayName = "Organization")
 * private Ref&lt;Organization&gt; organization;
 * </pre>
 *
 * Both the stored and the written value are checked, so a document can be moved neither out of nor
 * into a closed organization. Documents without the field are covered only by global, context and
 * register closes.
 */
@Target(ElementType.FIELD)
@Retention(RetentionPolicy.RUNTIME)
public @interface PeriodCloseOrganization {
}
