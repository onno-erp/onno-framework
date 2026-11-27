package su.onno.spring;

import jakarta.validation.Validator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.validation.ValidationAutoConfiguration;
import org.springframework.context.annotation.Bean;
import su.onno.metadata.MetadataRegistry;
import su.onno.metadata.TabularSectionDescriptor;
import su.onno.validation.BeanValidation;

import java.util.ArrayList;
import java.util.List;

/**
 * Hands the application's {@code jakarta.validation.Validator} (Spring Boot's, from
 * {@code spring-boot-starter-validation}) to {@link BeanValidation}, so the Jakarta constraints on
 * onno entities are checked with Spring's message source and Spring-managed
 * {@code ConstraintValidator}s on every onno write. Without a Validator bean the default provider is
 * used; without any provider a warning names the entities whose Jakarta constraints only partly
 * apply. Inactive when {@code jakarta.validation} is not on the classpath.
 */
@AutoConfiguration(after = {ValidationAutoConfiguration.class, OnnoAutoConfiguration.class})
@ConditionalOnClass(name = "jakarta.validation.Validator")
public class OnnoBeanValidationAutoConfiguration {

    private static final Logger log = LoggerFactory.getLogger(OnnoBeanValidationAutoConfiguration.class);

    @Bean
    SmartInitializingSingleton onnoBeanValidationInitializer(ObjectProvider<Validator> validators,
                                                             ObjectProvider<MetadataRegistry> registry) {
        return () -> {
            BeanValidation.use(validators.getIfUnique());
            MetadataRegistry metadata = registry.getIfAvailable();
            if (metadata != null && !BeanValidation.isActive()) {
                List<String> constrained = constrainedEntities(metadata);
                if (!constrained.isEmpty()) {
                    log.warn("Jakarta constraints on {} are enforced only where onno can mirror them "
                            + "(required, length, min/max, pattern, email): no Bean Validation provider is "
                            + "on the classpath. Add spring-boot-starter-validation.", constrained);
                }
            }
        };
    }

    private static List<String> constrainedEntities(MetadataRegistry metadata) {
        List<String> names = new ArrayList<>();
        metadata.allCatalogs().forEach(c -> {
            if (constrained(c.javaClass(), c.tabularSections())) names.add(c.logicalName());
        });
        metadata.allDocuments().forEach(d -> {
            if (constrained(d.javaClass(), d.tabularSections())) names.add(d.logicalName());
        });
        return names;
    }

    private static boolean constrained(Class<?> type, List<TabularSectionDescriptor> sections) {
        return BeanValidation.declaresConstraints(type)
                || sections.stream().anyMatch(s -> BeanValidation.declaresConstraints(s.rowClass()));
    }
}
