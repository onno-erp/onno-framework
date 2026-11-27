package su.onno.rules;

/**
 * Former name of {@link HasBusinessRules}.
 *
 * @deprecated implement {@link HasBusinessRules}. This name is easily confused with Spring's
 * {@code org.springframework.validation.annotation.Validated}, which switches on Jakarta Bean
 * Validation and has nothing to do with business rules.
 */
@Deprecated(since = "5.0")
public interface Validated extends HasBusinessRules {
}
